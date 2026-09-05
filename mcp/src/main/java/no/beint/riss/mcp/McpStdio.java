package no.beint.riss.mcp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Newline-delimited stdio transport with bounded concurrent calls and cancellation. */
public final class McpStdio {
    private McpStdio() {}

    public static void serve(McpRuntime runtime, InputStream source, OutputStream output) throws IOException, InterruptedException {
        var calls = new ConcurrentHashMap<String, FutureTask<Void>>();
        var slots = new Semaphore(64);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var input = new BufferedInputStream(source);
        var line = new ByteArrayOutputStream();
        try {
            int value;
            while ((value = input.read()) != -1) {
                if (value != '\n') {
                    if (line.size() >= runtime.maxRequestBytes()) throw new IOException("MCP input line exceeds byte limit");
                    line.write(value);
                    continue;
                }
                if (line.size() == 0) continue;
                var bytes = line.toByteArray();
                line.reset();
                Map<String, Object> request;
                try { request = Json.object(Json.parse(bytes)); }
                catch (IllegalArgumentException e) { write(output, runtime.handle(bytes, null)); continue; }
                if ("2.0".equals(request.get("jsonrpc")) && "notifications/cancelled".equals(request.get("method")) && !request.containsKey("id")) {
                    try {
                        var id = key(Json.object(request.get("params")).get("requestId"));
                        var call = calls.get(id);
                        if (call != null) call.cancel(true);
                    } catch (IllegalArgumentException ignored) { }
                    continue;
                }
                if (!request.containsKey("id")) { write(output, runtime.handle(bytes, null).forVersion(version(request))); continue; }
                if (!slots.tryAcquire()) { write(output, McpRuntime.error(200, request.get("id"), -32000, "Server is busy; retry later")); continue; }
                var key = key(request.get("id"));
                var task = new FutureTask<Void>(() -> {
                    try {
                        var reply = runtime.handle(bytes, null).forVersion(version(request));
                        if (!Thread.currentThread().isInterrupted()) write(output, reply);
                    } catch (IOException ignored) {
                    }
                    return null;
                });
                if (calls.putIfAbsent(key, task) != null) {
                    slots.release();
                    write(output, McpRuntime.error(200, null, -32600, "Duplicate in-flight request id"));
                } else workers.execute(() -> {
                    try { task.run(); }
                    finally { calls.remove(key, task); slots.release(); }
                });
            }
            if (line.size() != 0) throw new IOException("MCP input must end with a newline");
            workers.shutdown();
            if (!workers.awaitTermination(35, TimeUnit.SECONDS)) throw new IOException("MCP calls did not finish before shutdown");
        } finally {
            calls.values().forEach(call -> call.cancel(true));
            workers.shutdownNow();
        }
    }

    private static String key(Object id) {
        if (id instanceof Number number) return "number:" + new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return "json:" + Json.write(id);
    }

    private static String version(Map<String, Object> request) {
        if (request.get("params") instanceof Map<?, ?> params && params.get("_meta") instanceof Map<?, ?> meta)
            return String.valueOf(meta.get("io.modelcontextprotocol/protocolVersion"));
        return "2025-03-26";
    }

    private static void write(OutputStream output, McpRuntime.Reply reply) throws IOException {
        if (reply.size() == 0) return;
        synchronized (output) { reply.writeTo(output); output.write('\n'); output.flush(); }
    }
}
