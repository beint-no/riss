package no.beint.riss.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bounded Streamable HTTP transport using JDK virtual threads and a separate MCP credential. */
public final class McpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("riss-mcp-deadlines").factory());
    private final Semaphore slots = new Semaphore(64);
    private final byte[] authorization;
    private final Set<String> origins;
    private final McpRuntime runtime;

    public McpServer(McpRuntime runtime, InetSocketAddress address, String token, Set<String> allowedOrigins) throws IOException {
        if (token == null || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalArgumentException("An MCP bearer token is required");
        this.runtime = java.util.Objects.requireNonNull(runtime);
        authorization = token.getBytes(StandardCharsets.US_ASCII);
        origins = Set.copyOf(allowedOrigins);
        server = HttpServer.create(address, 64);
        server.createContext("/mcp", this::handle);
        server.setExecutor(workers);
    }

    public void start() { server.start(); }
    public InetSocketAddress address() { return server.getAddress(); }

    private void handle(HttpExchange exchange) throws IOException {
        if (!slots.tryAcquire()) {
            try (exchange) { send(exchange, McpRuntime.error(429, null, -32000, "Server is busy; retry later")); }
            return;
        }
        var worker = Thread.currentThread();
        var deadline = deadlines.schedule(() -> { worker.interrupt(); exchange.close(); }, 60, TimeUnit.SECONDS);
        try (exchange) {
            if (!exchange.getRequestURI().getRawPath().equals("/mcp") || exchange.getRequestURI().getRawQuery() != null) {
                send(exchange, McpRuntime.error(404, null, -32600, "Unknown MCP endpoint"));
                return;
            }
            var headers = exchange.getRequestHeaders();
            for (var name : headers.keySet()) {
                if (headers.get(name).size() != 1 && (name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("origin")
                        || name.equalsIgnoreCase("content-type") || name.toLowerCase(Locale.ROOT).startsWith("mcp-"))) {
                    send(exchange, McpRuntime.error(400, null, -32600, "Duplicate request header"));
                    return;
                }
            }
            var origin = headers.getFirst("Origin");
            if (origin != null && !origins.contains(origin)) {
                send(exchange, McpRuntime.error(403, null, -32000, "Origin is not allowed"));
                return;
            }
            var supplied = headers.getFirst("Authorization");
            if (supplied == null || !supplied.regionMatches(true, 0, "Bearer ", 0, 7)
                    || !MessageDigest.isEqual(authorization, supplied.substring(7).getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"riss-mcp\"");
                send(exchange, McpRuntime.error(401, null, -32000, "MCP authentication required"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, McpRuntime.error(405, null, -32600, "Use POST for MCP requests"));
                return;
            }
            var contentType = headers.getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                send(exchange, McpRuntime.error(415, null, -32600, "Content-Type must be application/json"));
                return;
            }
            var accept = String.join(",", headers.getOrDefault("Accept", java.util.List.of())).toLowerCase(Locale.ROOT);
            if (!accepts(accept, "application/json") || !accepts(accept, "text/event-stream")) {
                send(exchange, McpRuntime.error(406, null, -32600, "Accept must include application/json and text/event-stream"));
                return;
            }
            var body = exchange.getRequestBody().readNBytes(runtime.maxRequestBytes() + 1);
            var normalized = new LinkedHashMap<String, String>();
            headers.forEach((name, values) -> normalized.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
            send(exchange, runtime.handle(body, normalized));
        } finally {
            deadline.cancel(false);
            slots.release();
            Thread.interrupted();
        }
    }

    private static void send(HttpExchange exchange, McpRuntime.Reply reply) throws IOException {
        reply = reply.forVersion(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(reply.status(), reply.size() == 0 ? -1 : reply.size());
        if (reply.size() > 0) reply.writeTo(exchange.getResponseBody());
    }

    private static boolean accepts(String header, String mediaType) {
        for (var range : header.split(",")) {
            var parts = range.strip().split(";");
            if (!parts[0].strip().equals(mediaType)) continue;
            double quality = 1;
            for (int i = 1; i < parts.length; i++) {
                var parameter = parts[i].strip();
                if (parameter.startsWith("q=")) {
                    try { quality = Double.parseDouble(parameter.substring(2)); }
                    catch (NumberFormatException e) { quality = 0; }
                }
            }
            if (quality > 0 && quality <= 1) return true;
        }
        return false;
    }

    @Override public void close() {
        server.stop(0);
        deadlines.shutdownNow();
        workers.shutdownNow();
    }
}
