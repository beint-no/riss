package no.beint.riss.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Thread-safe, stateless tools protocol engine. Catalog pages are encoded once at construction. */
public final class McpRuntime {
    public static final List<String> PROTOCOL_VERSIONS = List.of("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26");
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final String CURRENT = PROTOCOL_VERSIONS.getFirst();
    private static final String VERSION_META = "io.modelcontextprotocol/protocolVersion";
    private final Map<String, CompiledTool> tools;
    private final Map<String, byte[]> pages;
    private final String firstCursor;
    private final Map<String, Object> info;
    private final McpExecutor executor;
    private final int maxRequestBytes;

    public static final class Reply {
        private final int status;
        private final byte[] body;
        private final boolean unknownId;
        private Reply(int status, byte[] body) { this(status, body, false); }
        private Reply(int status, byte[] body, boolean unknownId) { this.status = status; this.body = body; this.unknownId = unknownId; }
        public int status() { return status; }
        public byte[] body() { return body.clone(); }
        public int size() { return body.length; }
        public void writeTo(java.io.OutputStream output) throws IOException { output.write(body); }
        Reply forVersion(String version) {
            if (!unknownId || !CURRENT.equals(version)) return this;
            var message = Json.object(Json.parse(body));
            message.remove("id");
            return new Reply(status, Json.bytes(message));
        }
    }

    public McpRuntime(byte[] catalogBytes, McpExecutor executor) {
        this(catalogBytes, executor, MAX_REQUEST_BYTES);
    }

    public McpRuntime(byte[] catalogBytes, McpExecutor executor, int maxRequestBytes) {
        if (maxRequestBytes < 1024 || maxRequestBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Request limit must be between 1 KiB and 64 MiB");
        this.maxRequestBytes = maxRequestBytes;
        if (catalogBytes.length > 64 * 1024 * 1024) throw new IllegalArgumentException("Catalog exceeds 64 MiB");
        var catalog = Json.object(Json.parse(catalogBytes));
        if (!(catalog.get("format") instanceof Number format) || format.intValue() != 1) throw new IllegalArgumentException("Unsupported catalog format");
        this.executor = java.util.Objects.requireNonNull(executor);
        info = Json.map("name", Json.string(catalog.get("name")), "version", Json.string(catalog.get("version")));
        var toolMap = new LinkedHashMap<String, CompiledTool>();
        var chunks = new ArrayList<List<Object>>();
        var chunk = new ArrayList<Object>();
        int size = 0;
        for (var value : Json.list(catalog.get("tools"))) {
            var tool = new CompiledTool(value);
            if (toolMap.putIfAbsent(tool.name, tool) != null) throw new IllegalArgumentException("Duplicate tool: " + tool.name);
            int toolSize = Json.bytes(tool.definition).length;
            if (!chunk.isEmpty() && (size + toolSize > 128 * 1024 || chunk.size() >= 32)) {
                chunks.add(chunk);
                chunk = new ArrayList<>();
                size = 0;
            }
            chunk.add(tool.definition);
            size += toolSize;
        }
        if (!chunk.isEmpty() || chunks.isEmpty()) chunks.add(chunk);
        tools = Map.copyOf(toolMap);
        var digest = digest(catalogBytes);
        firstCursor = digest + ".0";
        var encodedPages = new LinkedHashMap<String, byte[]>();
        for (int index = 0; index < chunks.size(); index++) {
            var page = Json.map("resultType", "complete", "tools", chunks.get(index), "ttlMs", 3600000, "cacheScope", "private");
            if (index + 1 < chunks.size()) page.put("nextCursor", digest + "." + (index + 1));
            encodedPages.put(digest + "." + index, Json.bytes(page));
        }
        pages = Map.copyOf(encodedPages);
    }

    public int toolCount() { return tools.size(); }
    public int maxRequestBytes() { return maxRequestBytes; }

    /** Null headers select stdio; HTTP headers must be supplied without combining duplicate values. */
    public Reply handle(byte[] requestBytes, Map<String, String> headers) {
        var reply = process(requestBytes, headers);
        if (headers != null) for (var entry : headers.entrySet())
            if (entry.getKey().equalsIgnoreCase("MCP-Protocol-Version")) return reply.forVersion(entry.getValue());
        return reply;
    }

    private Reply process(byte[] requestBytes, Map<String, String> headers) {
        if (requestBytes.length > maxRequestBytes) return error(413, null, -32600, "Request exceeds byte limit");
        Object parsed;
        try { parsed = Json.parse(requestBytes); }
        catch (IllegalArgumentException e) { return error(400, null, -32700, "Parse error"); }
        if (!(parsed instanceof Map<?, ?>)) return error(400, null, -32600, "Expected a single JSON-RPC request");
        var request = Json.object(parsed);
        Object id = request.get("id");
        boolean notification = !request.containsKey("id");
        if (!"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String method)
                || !notification && !(id instanceof String || id instanceof Number))
            return error(400, null, -32600, "Invalid JSON-RPC request");
        if (request.containsKey("params") && !(request.get("params") instanceof Map<?, ?>))
            return error(400, id, -32602, "params must be an object");
        var params = Json.objectOrEmpty(request.get("params"));
        if (params.containsKey("_meta") && !(params.get("_meta") instanceof Map<?, ?>))
            return error(400, id, -32602, "_meta must be an object");
        Map<String, Object> meta;
        try { meta = Json.objectOrEmpty(params.get("_meta")); }
        catch (IllegalArgumentException e) { return error(400, id, -32602, "_meta must be an object"); }
        var normalized = new LinkedHashMap<String, String>();
        if (headers != null) headers.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        var version = normalized.getOrDefault("mcp-protocol-version", String.valueOf(meta.getOrDefault(VERSION_META, "2025-03-26")));
        if (headers != null && meta.containsKey(VERSION_META) && !java.util.Objects.equals(meta.get(VERSION_META), normalized.get("mcp-protocol-version")))
            return error(400, id, -32020, "Protocol version header does not match request metadata");
        if (!PROTOCOL_VERSIONS.contains(version)) return unsupported(id, version);
        if (CURRENT.equals(version)) {
            if (headers != null && (!version.equals(normalized.get("mcp-protocol-version")) || !version.equals(meta.get(VERSION_META)) || !method.equals(normalized.get("mcp-method"))
                    || method.equals("tools/call") && !java.util.Objects.equals(params.get("name"), decodeHeader(normalized.get("mcp-name")))))
                return error(400, id, -32020, "Required HTTP headers are missing or do not match the request");
            if (!CURRENT.equals(meta.get(VERSION_META)) || !(meta.get("io.modelcontextprotocol/clientCapabilities") instanceof Map<?, ?>))
                return error(400, id, -32602, "Required request metadata is missing");
        }
        if (notification) {
            if (method.startsWith("notifications/")) return new Reply(202, new byte[0]);
            return error(400, null, -32600, "This method requires a request id");
        }
        try {
            return switch (method) {
                case "initialize" -> initialize(id, params);
                case "server/discover" -> result(id, Json.map("resultType", "complete", "supportedVersions", PROTOCOL_VERSIONS,
                        "capabilities", capabilities(), "_meta", Json.map("io.modelcontextprotocol/serverInfo", info), "ttlMs", 3600000, "cacheScope", "private"));
                case "ping" -> result(id, Json.map("resultType", "complete"));
                case "tools/list" -> list(id, params);
                case "tools/call" -> call(id, params);
                default -> error(CURRENT.equals(version) && headers != null ? 404 : 200, id, -32601, "Method not found");
            };
        } catch (IllegalArgumentException e) {
            return error(200, id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return error(200, id, -32603, "Internal error");
        }
    }

    private Reply initialize(Object id, Map<String, Object> params) {
        var requested = Json.string(params.get("protocolVersion"));
        Json.object(params.get("capabilities"));
        var client = Json.object(params.get("clientInfo"));
        Json.string(client.get("name"));
        Json.string(client.get("version"));
        var negotiated = PROTOCOL_VERSIONS.contains(requested) && !requested.equals(CURRENT) ? requested : "2025-11-25";
        return result(id, Json.map("protocolVersion", negotiated, "capabilities", capabilities(), "serverInfo", info));
    }

    private Reply list(Object id, Map<String, Object> params) {
        var cursor = params.containsKey("cursor") ? Json.string(params.get("cursor")) : firstCursor;
        var page = pages.get(cursor);
        if (page == null) return error(200, id, -32602, "Invalid or stale cursor; restart tools/list without a cursor");
        var prefix = ("{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(id) + ",\"result\":").getBytes(StandardCharsets.UTF_8);
        var response = new byte[prefix.length + page.length + 1];
        System.arraycopy(prefix, 0, response, 0, prefix.length);
        System.arraycopy(page, 0, response, prefix.length, page.length);
        response[response.length - 1] = '}';
        return new Reply(200, response);
    }

    private Reply call(Object id, Map<String, Object> params) {
        var name = Json.string(params.get("name"));
        var tool = tools.get(name);
        if (tool == null) return error(200, id, -32602, "Unknown tool: " + name);
        if (params.containsKey("arguments") && !(params.get("arguments") instanceof Map<?, ?>))
            return error(200, id, -32602, "arguments must be an object");
        McpRequest request;
        try { request = tool.request(Json.objectOrEmpty(params.get("arguments")), maxRequestBytes); }
        catch (IllegalArgumentException e) { return toolError(id, e.getMessage()); }
        try {
            var response = executor.execute(request);
            if (response.status() < 200 || response.status() >= 300) {
                String detail = switch (response.status()) {
                    case 401 -> "API authentication failed";
                    case 403 -> "API permission denied";
                    case 404 -> "API resource not found";
                    case 429 -> "API rate limit exceeded; retry later";
                    default -> "API returned HTTP " + response.status();
                };
                if (List.of(400, 409, 422).contains(response.status())) {
                    var message = validationMessage(response);
                    if (message != null) detail += ": " + message;
                }
                return toolError(id, detail);
            }
            var body = response.body();
            var mediaType = response.contentType().split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            Object value;
            if (body.length == 0) value = null;
            else if (mediaType.equals("application/json") || mediaType.endsWith("+json")) value = Json.parse(body);
            else if (mediaType.startsWith("text/")) value = new String(body, StandardCharsets.UTF_8);
            else value = Json.map("contentType", mediaType, "base64", Base64.getEncoder().encodeToString(body));
            var structured = Json.map("status", response.status(), "result", value);
            if (tool.definition.get("outputSchema") instanceof Map<?, ?> schema) {
                try { SchemaCheck.validate(structured, Json.object(schema)); }
                catch (IllegalArgumentException e) { return toolError(id, "API response does not match the compiled output schema"); }
            }
            return result(id, Json.map("resultType", "complete", "content", List.of(Json.map("type", "text", "text", Json.write(structured))),
                    "structuredContent", structured, "isError", false));
        } catch (IllegalArgumentException e) {
            return toolError(id, "API request or response does not match the compiled contract");
        } catch (IOException e) {
            return toolError(id, "API request failed or exceeded its response/time limit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return toolError(id, "Tool call cancelled");
        }
    }

    private static Map<String, Object> capabilities() { return Json.map("tools", Map.of()); }

    private static String validationMessage(McpResponse response) {
        var type = response.contentType().split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!type.equals("application/json") && !type.endsWith("+json")) return null;
        try {
            var body = Json.object(Json.parse(response.body()));
            for (var field : List.of("detail", "message", "error")) {
                if (body.get(field) instanceof String value && !value.isBlank()) return value.substring(0, Math.min(value.length(), 2048));
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private static Reply toolError(Object id, String message) {
        return result(id, Json.map("resultType", "complete", "content", List.of(Json.map("type", "text", "text", message)), "isError", true));
    }

    private static Reply result(Object id, Object result) { return new Reply(200, Json.bytes(Json.map("jsonrpc", "2.0", "id", id, "result", result))); }

    static Reply error(int status, Object id, int code, String message) {
        return new Reply(status, Json.bytes(Json.map("jsonrpc", "2.0", "id", id, "error", Json.map("code", code, "message", message))), id == null);
    }

    private static Reply unsupported(Object id, String requested) {
        return new Reply(400, Json.bytes(Json.map("jsonrpc", "2.0", "id", id, "error", Json.map("code", -32022,
                "message", "Unsupported protocol version", "data", Json.map("supported", PROTOCOL_VERSIONS, "requested", requested)))));
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 12); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String decodeHeader(String value) {
        if (value == null) return null;
        if (value.startsWith("=?base64?") && value.endsWith("?=")) {
            try { return new String(Base64.getDecoder().decode(value.substring(9, value.length() - 2)), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { return null; }
        }
        return value;
    }
}
