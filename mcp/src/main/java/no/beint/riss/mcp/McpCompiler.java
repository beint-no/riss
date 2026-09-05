package no.beint.riss.mcp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Compiles OpenAPI 3.1 JSON into a portable catalog, without loading application classes. */
public final class McpCompiler {
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final Set<String> BLOCKED_HEADERS = Set.of("authorization", "cookie", "host", "connection",
            "content-length", "content-type", "transfer-encoding", "upgrade", "expect", "proxy-authorization",
            "forwarded", "accept", "te", "trailer");

    private McpCompiler() {}

    public record Result(byte[] catalog, int tools, List<String> excluded) {
        public Result { catalog = catalog.clone(); excluded = List.copyOf(excluded); }
        @Override public byte[] catalog() { return catalog.clone(); }
    }

    public static Result compile(byte[] openApi) { return compile(openApi, Set.of(), false); }

    public static Result compile(byte[] openApi, Set<String> includedOperations, boolean readOnly) {
        var document = Json.object(Json.parse(openApi));
        if (!Json.string(document.get("openapi")).startsWith("3.1."))
            throw new IllegalArgumentException("MCP compilation requires OpenAPI 3.1 JSON");
        var definitions = Json.objectOrEmpty(Json.objectOrEmpty(document.get("components")).get("schemas"));
        var tools = new TreeMap<String, Object>();
        var excluded = new ArrayList<String>();
        var found = new LinkedHashSet<String>();
        for (var pathEntry : new TreeMap<>(Json.object(document.get("paths"))).entrySet()) {
            var path = normalizePath(pathEntry.getKey());
            validatePath(path);
            var pathItem = resolve(document, pathEntry.getValue());
            for (var entry : new TreeMap<>(pathItem).entrySet()) {
                var method = entry.getKey();
                if (!METHODS.contains(method)) continue;
                var operation = Json.object(entry.getValue());
                var name = Json.string(operation.get("operationId"));
                if (!includedOperations.isEmpty() && !includedOperations.contains(name)) continue;
                found.add(name);
                if (readOnly && !Set.of("get", "head", "options").contains(method)) continue;
                try {
                    var tool = compileTool(document, definitions, pathItem, operation, method, path);
                    if (tools.putIfAbsent(name, tool) != null) throw new IllegalStateException("Duplicate operationId: " + name);
                } catch (UnsupportedOperationException e) {
                    if (!includedOperations.isEmpty()) throw new IllegalArgumentException(name + ": " + e.getMessage());
                    excluded.add(name + ": " + e.getMessage());
                }
            }
        }
        if (!found.containsAll(includedOperations)) {
            var missing = new LinkedHashSet<>(includedOperations);
            missing.removeAll(found);
            throw new IllegalArgumentException("Unknown operations: " + missing);
        }
        if (tools.isEmpty()) throw new IllegalArgumentException("No supported operations selected");
        var info = Json.object(document.get("info"));
        var catalog = Json.map("format", 1, "name", info.getOrDefault("title", "API"),
                "version", info.getOrDefault("version", "1"), "tools", new ArrayList<>(tools.values()));
        return new Result(Json.bytes(catalog), tools.size(), excluded);
    }

    private static Map<String, Object> compileTool(Map<String, Object> document, Map<String, Object> definitions,
            Map<String, Object> pathItem, Map<String, Object> operation, String method, String path) {
        var name = Json.string(operation.get("operationId"));
        if (!name.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid MCP operationId: " + name);
        var input = Json.map("type", "object", "properties", new LinkedHashMap<>(), "additionalProperties", false);
        var groups = Json.object(input.get("properties"));
        var requiredGroups = new ArrayList<String>();
        var bindings = new ArrayList<Object>();
        var parameters = new LinkedHashMap<String, Map<String, Object>>();
        for (var source : List.of(pathItem, operation)) {
            for (var value : Json.list(source.getOrDefault("parameters", List.of()))) {
                var parameter = resolve(document, value);
                parameters.put(parameter.get("in") + ":" + parameter.get("name"), parameter);
            }
        }
        for (var parameter : parameters.values()) {
            var location = Json.string(parameter.get("in"));
            var parameterName = Json.string(parameter.get("name"));
            if (credentialParameter(document, location, parameterName)) continue;
            if (location.equals("header") && !allowedHeader(parameterName)) continue;
            if (!Set.of("path", "query", "header").contains(location))
                throw new UnsupportedOperationException("Unsupported parameter location: " + location);
            var groupName = location.equals("header") ? "headers" : location;
            var group = Json.object(groups.computeIfAbsent(groupName,
                    _ -> Json.map("type", "object", "properties", new LinkedHashMap<>(), "additionalProperties", false)));
            var schema = new LinkedHashMap<>(Json.object(parameter.getOrDefault("schema", Map.of())));
            if (parameter.containsKey("description")) schema.putIfAbsent("description", parameter.get("description"));
            if (parameter.containsKey("example")) schema.putIfAbsent("examples", List.of(parameter.get("example")));
            Json.object(group.get("properties")).put(parameterName, schema);
            boolean required = location.equals("path") || Boolean.TRUE.equals(parameter.get("required"));
            if (required) {
                @SuppressWarnings("unchecked") var requiredParameters = (List<String>) group.computeIfAbsent("required", _ -> new ArrayList<String>());
                requiredParameters.add(parameterName);
                if (!requiredGroups.contains(groupName)) requiredGroups.add(groupName);
            }
            var style = parameter.getOrDefault("style", location.equals("query") ? "form" : "simple");
            if (!(location.equals("query") ? Set.of("form", "deepObject", "spaceDelimited", "pipeDelimited") : Set.of("simple")).contains(style))
                throw new UnsupportedOperationException("Unsupported parameter style: " + style);
            bindings.add(Json.map("group", groupName, "name", parameterName, "style", style,
                    "explode", parameter.getOrDefault("explode", style.equals("form")), "required", required));
        }
        String bodyType = "";
        var bodyParts = new LinkedHashMap<String, Object>();
        if (operation.get("requestBody") != null) {
            var request = resolve(document, operation.get("requestBody"));
            var content = Json.object(request.get("content"));
            bodyType = jsonMediaType(content);
            if (bodyType == null && content.containsKey("multipart/form-data")) bodyType = "multipart/form-data";
            if (bodyType == null) throw new UnsupportedOperationException("Unsupported request media type: " + content.keySet());
            var media = Json.object(content.get(bodyType));
            Object bodySchema = media.getOrDefault("schema", Map.of());
            if (bodyType.equals("multipart/form-data")) {
                var root = new LinkedHashMap<>(resolve(document, bodySchema));
                var properties = new LinkedHashMap<String, Object>();
                for (var property : Json.object(root.get("properties")).entrySet()) {
                    var field = resolve(document, property.getValue());
                    boolean array = "array".equals(field.get("type")) || field.get("type") instanceof List<?> types && types.contains("array");
                    var item = array ? resolve(document, field.get("items")) : field;
                    boolean file = "binary".equals(item.get("format"));
                    var encoding = Json.objectOrEmpty(Json.objectOrEmpty(media.get("encoding")).get(property.getKey()));
                    String partType = String.valueOf(encoding.getOrDefault("contentType",
                            file ? "application/octet-stream" : item.containsKey("properties") || item.containsKey("additionalProperties") ? "application/json" : "text/plain; charset=utf-8"));
                    bodyParts.put(property.getKey(), Json.map("file", file, "array", array, "contentType", partType));
                    if (file) {
                        Object converted = Json.map("type", "object", "properties", Json.map("filename", Json.map("type", "string", "minLength", 1),
                                "base64", Json.map("type", "string", "contentEncoding", "base64"), "contentType", Json.map("type", "string")),
                                "required", List.of("filename", "base64"), "additionalProperties", false);
                        if (array) converted = Json.map("type", "array", "items", converted);
                        if (field.get("type") instanceof List<?> types && types.contains("null")) converted = Json.map("anyOf", List.of(converted, Json.map("type", "null")));
                        properties.put(property.getKey(), converted);
                    } else properties.put(property.getKey(), property.getValue());
                }
                root.put("properties", properties);
                root.put("additionalProperties", false);
                bodySchema = root;
            }
            groups.put("body", bodySchema);
            if (Boolean.TRUE.equals(request.get("required"))) requiredGroups.add("body");
        }
        if (!requiredGroups.isEmpty()) input.put("required", requiredGroups);
        var tool = Json.map("name", name, "description", description(operation, method, path),
                "inputSchema", selfContained(input, definitions));
        if (operation.get("summary") instanceof String summary && !summary.isBlank()) tool.put("title", summary);
        if (operation.get("x-mcp-annotations") instanceof Map<?, ?> annotations) {
            var hints = new LinkedHashMap<String, Object>();
            for (var hint : List.of("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")) {
                if (annotations.get(hint) instanceof Boolean value) hints.put(hint, value);
            }
            if (!hints.isEmpty()) tool.put("annotations", hints);
        }
        var resultSchemas = new ArrayList<Object>();
        boolean allJson = true;
        for (var responseEntry : Json.object(operation.get("responses")).entrySet()) {
            if (!responseEntry.getKey().startsWith("2")) continue;
            var response = resolve(document, responseEntry.getValue());
            var content = Json.objectOrEmpty(response.get("content"));
            if (content.isEmpty() || method.equals("head")) { resultSchemas.add(Json.map("type", "null")); continue; }
            var mediaType = jsonMediaType(content);
            if (mediaType == null) { allJson = false; continue; }
            resultSchemas.add(Json.object(content.get(mediaType)).getOrDefault("schema", Map.of()));
        }
        if (allJson && !resultSchemas.isEmpty()) {
            Object resultSchema = resultSchemas.size() == 1 ? resultSchemas.getFirst() : Json.map("anyOf", resultSchemas);
            tool.put("outputSchema", selfContained(Json.map("type", "object", "properties",
                    Json.map("status", Json.map("type", "integer"), "result", resultSchema),
                    "required", List.of("status", "result")), definitions));
        }
        return Json.map("tool", tool, "method", method.toUpperCase(Locale.ROOT), "path", path,
                "parameters", bindings, "bodyType", bodyType, "bodyParts", bodyParts);
    }

    static boolean allowedHeader(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") && !BLOCKED_HEADERS.contains(lower)
                && !lower.startsWith("proxy-") && !lower.startsWith("sec-") && !lower.startsWith("x-forwarded-");
    }

    private static boolean credentialParameter(Map<String, Object> document, String location, String name) {
        var schemes = Json.objectOrEmpty(Json.objectOrEmpty(document.get("components")).get("securitySchemes"));
        for (var value : schemes.values()) {
            var scheme = resolve(document, value);
            if (!"apiKey".equals(scheme.get("type")) || !location.equals(scheme.get("in"))) continue;
            var key = Json.string(scheme.get("name"));
            if (location.equals("header") ? key.equalsIgnoreCase(name) : key.equals(name)) return true;
        }
        return false;
    }

    static void validatePath(String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("?") || path.contains("#")
                || path.contains("\\") || path.contains("%") || path.chars().anyMatch(c -> c <= 0x20 || c == 127))
            throw new IllegalArgumentException("Unsafe API path: " + path);
        for (var segment : path.split("/")) if (segment.equals(".") || segment.equals(".."))
            throw new IllegalArgumentException("Unsafe API path: " + path);
    }

    private static String normalizePath(String path) {
        var result = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '{') { result.append(c); continue; }
            int start = ++i;
            int depth = 1;
            while (i < path.length() && depth > 0) {
                c = path.charAt(i);
                if (c == '\\') i++;
                else if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth > 0) i++;
            }
            if (depth != 0) throw new IllegalArgumentException("Unclosed path parameter: " + path);
            var name = path.substring(start, i).split(":", 2)[0];
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid path parameter: " + name);
            result.append('{').append(name).append('}');
        }
        return result.toString();
    }

    private static String description(Map<String, Object> operation, String method, String path) {
        var summary = String.valueOf(operation.getOrDefault("summary", ""));
        var description = String.valueOf(operation.getOrDefault("description", ""));
        if (summary.isBlank() && description.isBlank()) return method.toUpperCase(Locale.ROOT) + " " + path;
        return summary.isBlank() ? description : description.isBlank() || summary.equals(description) ? summary : summary + "\n\n" + description;
    }

    private static String jsonMediaType(Map<String, Object> content) {
        if (content.containsKey("application/json")) return "application/json";
        return content.keySet().stream().filter(it -> it.startsWith("application/") && it.endsWith("+json")).sorted().findFirst().orElse(null);
    }

    private static Map<String, Object> resolve(Map<String, Object> document, Object value) {
        var result = Json.object(value);
        var visited = new LinkedHashSet<String>();
        while (result.containsKey("$ref")) {
            var ref = Json.string(result.get("$ref"));
            if (!ref.startsWith("#/components/") || !visited.add(ref))
                throw new IllegalArgumentException("Unsupported or cyclic document reference: " + ref);
            Object target = document;
            for (var token : ref.substring(2).split("/")) target = Json.object(target).get(token.replace("~1", "/").replace("~0", "~"));
            result = Json.object(target);
        }
        return result;
    }

    private static Map<String, Object> selfContained(Map<String, Object> schema, Map<String, Object> definitions) {
        var pending = new ArrayDeque<String>();
        var result = Json.object(rewrite(schema, pending));
        var included = new TreeMap<String, Object>();
        while (!pending.isEmpty()) {
            var ref = pending.removeFirst();
            if (included.containsKey(ref)) continue;
            var key = ref.replace("~1", "/").replace("~0", "~");
            if (!definitions.containsKey(key)) throw new IllegalArgumentException("Missing schema: " + ref);
            included.put(ref, null);
            included.put(ref, rewrite(definitions.get(key), pending));
        }
        if (!included.isEmpty()) {
            var defs = new LinkedHashMap<String, Object>();
            included.forEach((key, value) -> defs.put(key.replace("~1", "/").replace("~0", "~"), value));
            result.put("$defs", defs);
        }
        return result;
    }

    private static Object rewrite(Object value, ArrayDeque<String> pending) {
        if (value instanceof Boolean) return value;
        var out = new TreeMap<String, Object>();
        Json.object(value).forEach((key, item) -> {
            switch (key) {
                case "$ref" -> {
                    var ref = Json.string(item);
                    if (!ref.startsWith("#/components/schemas/")) throw new IllegalArgumentException("Unsupported schema reference: " + ref);
                    var name = ref.substring("#/components/schemas/".length());
                    pending.add(name);
                    out.put(key, "#/$defs/" + name);
                }
                case "properties", "patternProperties", "dependentSchemas", "$defs" -> {
                    var schemas = new TreeMap<String, Object>();
                    Json.object(item).forEach((name, schema) -> schemas.put(name, rewrite(schema, pending)));
                    out.put(key, schemas);
                }
                case "items", "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "propertyNames", "contains", "not", "if", "then", "else", "contentSchema" -> out.put(key, rewrite(item, pending));
                case "allOf", "anyOf", "oneOf", "prefixItems" -> out.put(key, Json.list(item).stream().map(schema -> rewrite(schema, pending)).toList());
                case "$id", "$dynamicRef", "$anchor", "$dynamicAnchor" -> throw new UnsupportedOperationException("Schema scope/dynamic references require an explicit adapter: " + key);
                case "x-mcp-header" -> throw new UnsupportedOperationException("Custom MCP parameter-header mirroring is not supported");
                default -> out.put(key, item);
            }
        });
        return out;
    }
}
