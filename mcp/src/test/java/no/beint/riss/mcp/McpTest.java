package no.beint.riss.mcp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class McpTest {
    private static int checks;
    private static final byte[] SPEC = """
            {"openapi":"3.1.0","info":{"title":"Test API","version":"v1"},
             "components":{"schemas":{"Node":{"type":"object","properties":{"name":{"type":"string"},"child":{"$ref":"#/components/schemas/Node"}},"required":["name"]}}},
             "paths":{
               "/api/items/{id}":{"get":{"operationId":"getItem","summary":"Get item","parameters":[
                 {"name":"id","in":"path","required":true,"schema":{"type":"string"}},
                 {"name":"id","in":"query","schema":{"type":"integer"}},
                 {"name":"q","in":"query","schema":{"type":"string"}},
                 {"name":"tags","in":"query","schema":{"type":"array","items":{"type":"string"}}},
                 {"name":"filter","in":"query","style":"deepObject","explode":true,"schema":{"type":"object","additionalProperties":{"type":"string"}}},
                 {"name":"X-Tenant-Id","in":"header","schema":{"type":"integer"}},
                 {"name":"Authorization","in":"header","required":true,"schema":{"type":"string"}}
               ],"responses":{"200":{"description":"OK","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}}}}},
               "/api/items":{"post":{"operationId":"createItem","requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}},"responses":{"201":{"description":"Created","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}}}}}
             }}
            """.getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        json();
        compiler();
        multipart();
        protocol();
        pagingAndConcurrency();
        requestExecutors();
        http();
        stdio();
        System.out.println("Passed " + checks + " MCP checks (JDK only)");
    }

    private static void json() {
        for (var invalid : List.of("", "null null", "[1,]", "{\"a\":1,}", "{\"a\":null,\"a\":2}", "01", "+1", "1.", "1e", "NaN", "\"\\uD800\"", "\"\\uDC00\"", "\"\n\"", "[".repeat(100)))
            rejects(() -> Json.parse(invalid));
        rejects(() -> Json.parse(new byte[]{(byte) 0xc0, (byte) 0xaf}));
        rejects(() -> Json.parse("1e2147483647"));
        rejects(() -> Json.parse("\"\\uＦFFF\""));
        var value = Json.parse("{\"x\":[true,false,null,1.2e3,\"\\uD83D\\uDE00\",\"æ\"]}");
        equal(Json.parse(Json.bytes(value)), value);
        var random = new Random(781);
        for (int run = 0; run < 200; run++) {
            var string = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                int point = random.nextInt(0x110000);
                if (point < 0xd800 || point > 0xdfff) string.appendCodePoint(point);
            }
            equal(Json.parse(Json.bytes(string.toString())), string.toString());
        }
    }

    private static void requestExecutors() throws Exception {
        var runtime = new McpRuntime(McpCompiler.compile(SPEC).catalog(), _ -> {
            throw new AssertionError("Request-scoped call used the default executor");
        });
        var request = rpc("tools/call", Json.map("name", "getItem", "arguments", Json.map("path", Json.map("id", "1"))));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 100; i++) {
                var identity = "user-" + i;
                pending.add(pool.submit(() -> {
                    var reply = result(runtime.handle(request, Map.of(), _ ->
                            new McpResponse(200, "application/json", Json.bytes(Json.map("name", identity)))));
                    return Json.string(Json.object(Json.object(reply.get("structuredContent")).get("result")).get("name"));
                }));
            }
            for (int i = 0; i < pending.size(); i++) equal(pending.get(i).get(), "user-" + i);
        }
        equal(runtime.toolCount(), 2);
    }

    private static void compiler() {
        var compiled = McpCompiler.compile(SPEC);
        equal(compiled.tools(), 2);
        equal(compiled.excluded(), List.of());
        check(Arrays.equals(compiled.catalog(), McpCompiler.compile(SPEC).catalog()));
        check(!new String(compiled.catalog(), StandardCharsets.UTF_8).contains("#/components/"));
        check(!new String(compiled.catalog(), StandardCharsets.UTF_8).contains("Authorization"));
        equal(McpCompiler.compile(SPEC, Set.of(), true).tools(), 1);
        equal(McpCompiler.compile(SPEC, Set.of("createItem"), false).tools(), 1);
        rejects(() -> McpCompiler.compile(SPEC, Set.of("missing"), false));
        var source = new String(SPEC, StandardCharsets.UTF_8);
        rejects(() -> McpCompiler.compile(source.replace("#/components/schemas/Node", "https://example.com/schema").getBytes(StandardCharsets.UTF_8)));
        rejects(() -> McpCompiler.compile(source.replace("/api/items/{id}", "//other/items/{id}").getBytes(StandardCharsets.UTF_8)));
        var regexPath = source.replace("/api/items/{id}", "/api/items/{id:[0-9]{1,9}}");
        var regexCatalog = new String(McpCompiler.compile(regexPath.getBytes(StandardCharsets.UTF_8)).catalog(), StandardCharsets.UTF_8);
        check(regexCatalog.contains("/api/items/{id}"));
        var literal = Json.object(Json.parse(SPEC));
        var node = Json.object(Json.object(Json.object(literal.get("components")).get("schemas")).get("Node"));
        Json.object(node.get("properties")).put("$ref", Json.map("type", "string"));
        node.put("examples", List.of(Json.map("$ref", "literal value")));
        var literalCatalog = new String(McpCompiler.compile(Json.bytes(literal)).catalog(), StandardCharsets.UTF_8);
        check(literalCatalog.contains("\"$ref\":\"literal value\""));
        check(literalCatalog.contains("\"$ref\":{\"type\":\"string\"}"));
    }

    private static void protocol() {
        var captured = new AtomicReference<McpRequest>();
        var calls = new AtomicInteger();
        var runtime = new McpRuntime(McpCompiler.compile(SPEC).catalog(), request -> {
            captured.set(request);
            calls.incrementAndGet();
            return new McpResponse(200, "application/json", Json.bytes(Json.map("name", "test")));
        });
        var initialized = result(runtime.handle(rpc("initialize", Json.map("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Json.map("name", "test", "version", "1"))), Map.of()));
        equal(initialized.get("protocolVersion"), "2025-11-25");
        for (var version : McpRuntime.PROTOCOL_VERSIONS) {
            var params = version.equals("2026-07-28") ? modern(Map.of()) : Map.<String, Object>of();
            equal(runtime.handle(rpc("tools/list", params), Map.of("MCP-Protocol-Version", version, "Mcp-Method", "tools/list")).status(), 200);
        }
        var discovered = result(runtime.handle(rpc("server/discover", modern(Map.of())), modernHeaders("server/discover", null)));
        equal(discovered.get("supportedVersions"), McpRuntime.PROTOCOL_VERSIONS);
        equal(discovered.get("resultType"), "complete");
        equal(code(runtime.handle(rpc("ping", Map.of()), Map.of("MCP-Protocol-Version", "unknown"))), -32022);
        equal(code(runtime.handle(rpc("tools/list", modern(Map.of())), Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call"))), -32020);
        equal(code(runtime.handle(rpc("tools/list", modern(Map.of())), Map.of("MCP-Protocol-Version", "2025-11-25"))), -32020);
        equal(code(runtime.handle(rpc("tools/list", Json.map("_meta", null)), Map.of())), -32602);
        equal(code(runtime.handle("[]".getBytes(StandardCharsets.UTF_8), null)), -32600);
        equal(code(runtime.handle("{".getBytes(StandardCharsets.UTF_8), null)), -32700);
        check(!Json.object(Json.parse(runtime.handle("{".getBytes(StandardCharsets.UTF_8), modernHeaders("tools/list", null)).body())).containsKey("id"));
        check(Json.object(Json.parse(runtime.handle("{".getBytes(StandardCharsets.UTF_8), null).body())).containsKey("id"));
        equal(code(runtime.handle(rpc("missing", Map.of()), null)), -32601);
        equal(code(runtime.handle(rpc("tools/call", Json.map("name", "missing")), null)), -32602);
        equal(code(runtime.handle(Json.bytes(Json.map("jsonrpc", "2.0", "method", "tools/call", "params", Json.map("name", "createItem"))), null)), -32600);
        equal(calls.get(), 0);
        equal(runtime.handle(Json.bytes(Json.map("jsonrpc", "2.0", "method", "notifications/initialized")), null).status(), 202);
        var arguments = Json.map("path", Json.map("id", "a/b?c#d"), "query", Json.map("id", 42, "q", "æ & = +", "tags", List.of("a,b", "c d"), "filter", Json.map("x", "a&b")), "headers", Json.map("X-Tenant-Id", 7));
        var called = result(runtime.handle(rpc("tools/call", Json.map("name", "getItem", "arguments", arguments)), null));
        equal(called.get("isError"), false);
        equal(captured.get().path(), "/api/items/a%2Fb%3Fc%23d?id=42&q=%C3%A6%20%26%20%3D%20%2B&tags=a%2Cb&tags=c%20d&filter%5Bx%5D=a%26b");
        equal(captured.get().headers(), Map.of("X-Tenant-Id", "7"));
        equal(Json.object(called.get("structuredContent")).get("result"), Json.map("name", "test"));
        for (var invalid : List.of(Json.map(), Json.map("path", Json.map("id", "..")), Json.map("path", Json.map("id", "a"), "headers", Json.map("Authorization", "secret")), Json.map("path", Json.map("id", "a"), "query", Json.map("id", "bad")))) {
            equal(result(runtime.handle(rpc("tools/call", Json.map("name", "getItem", "arguments", invalid)), null)).get("isError"), true);
        }
        equal(calls.get(), 1);
        var body = Json.map("name", "root", "child", Json.map("name", "child"));
        equal(result(runtime.handle(rpc("tools/call", Json.map("name", "createItem", "arguments", Json.map("body", body))), null)).get("isError"), false);
        equal(Json.parse(captured.get().body()), body);
        equal(captured.get().headers().get("Content-Type"), "application/json");
        equal(result(runtime.handle(rpc("tools/call", Json.map("name", "createItem", "arguments", Json.map("body", Json.map("child", body)))), null)).get("isError"), true);
        var denied = new McpRuntime(McpCompiler.compile(SPEC).catalog(), _ -> new McpResponse(403, "text/html", "private debug detail".getBytes(StandardCharsets.UTF_8)));
        var denial = denied.handle(rpc("tools/call", Json.map("name", "getItem", "arguments", Json.map("path", Json.map("id", "1")))), null);
        equal(result(denial).get("isError"), true);
        check(!new String(denial.body(), StandardCharsets.UTF_8).contains("private debug detail"));
        var validation = new McpRuntime(McpCompiler.compile(SPEC).catalog(), _ -> new McpResponse(422, "application/problem+json", Json.bytes(Json.map("detail", "Customer is archived", "stackTrace", "private stack"))));
        var invalidCall = validation.handle(rpc("tools/call", Json.map("name", "getItem", "arguments", Json.map("path", Json.map("id", "1")))), null);
        check(new String(invalidCall.body(), StandardCharsets.UTF_8).contains("Customer is archived"));
        check(!new String(invalidCall.body(), StandardCharsets.UTF_8).contains("private stack"));
    }

    private static void multipart() {
        var spec = Json.object(Json.parse(SPEC));
        var schema = Json.map("type", "object", "properties", Json.map("request", Json.map("$ref", "#/components/schemas/Node"),
                "files", Json.map("type", "array", "items", Json.map("type", "string", "format", "binary"))), "required", List.of("request", "files"));
        var operation = Json.object(Json.object(Json.object(spec.get("paths")).get("/api/items")).get("post"));
        operation.put("requestBody", Json.map("required", true, "content", Json.map("multipart/form-data", Json.map("schema", schema))));
        var captured = new AtomicReference<McpRequest>();
        var runtime = new McpRuntime(McpCompiler.compile(Json.bytes(spec)).catalog(), request -> {
            captured.set(request);
            return new McpResponse(201, "application/json", Json.bytes(Json.map("name", "created")));
        });
        var file = Json.map("filename", "a\"b.txt", "base64", "aGVsbG8=", "contentType", "text/plain");
        var body = Json.map("request", Json.map("name", "root"), "files", List.of(file, file));
        var reply = result(runtime.handle(rpc("tools/call", Json.map("name", "createItem", "arguments", Json.map("body", body))), null));
        equal(reply.get("isError"), false);
        check(captured.get().headers().get("Content-Type").startsWith("multipart/form-data; boundary=riss-"));
        var text = new String(captured.get().body(), StandardCharsets.UTF_8);
        check(text.contains("name=\"request\"\r\nContent-Type: application/json\r\n\r\n{\"name\":\"root\"}"));
        check(text.contains("filename=\"a%22b.txt\""));
        equal(text.split("hello", -1).length, 3);
        file.put("filename", "injected\r\nHeader: bad");
        equal(result(runtime.handle(rpc("tools/call", Json.map("name", "createItem", "arguments", Json.map("body", body))), null)).get("isError"), true);
        file.put("filename", "file");
        file.put("base64", "invalid!");
        equal(result(runtime.handle(rpc("tools/call", Json.map("name", "createItem", "arguments", Json.map("body", body))), null)).get("isError"), true);
    }

    private static void pagingAndConcurrency() throws Exception {
        var spec = Json.object(Json.parse(SPEC));
        var paths = Json.object(spec.get("paths"));
        var template = Json.object(Json.object(paths.get("/api/items")).get("post"));
        for (int i = 0; i < 80; i++) {
            var operation = Json.object(Json.parse(Json.bytes(template)));
            operation.put("operationId", "create_" + i);
            paths.put("/api/items/" + i, Json.map("post", operation));
        }
        var runtime = new McpRuntime(McpCompiler.compile(Json.bytes(spec)).catalog(), _ -> { throw new AssertionError("Discovery invoked API"); });
        var names = new ArrayList<>();
        Map<String, Object> params = Map.of();
        do {
            var page = result(runtime.handle(rpc("tools/list", params), null));
            Json.list(page.get("tools")).forEach(tool -> names.add(Json.object(tool).get("name")));
            params = page.containsKey("nextCursor") ? Json.map("cursor", page.get("nextCursor")) : null;
        } while (params != null);
        equal(names.size(), 82);
        equal(Set.copyOf(names).size(), 82);
        equal(code(runtime.handle(rpc("tools/list", Json.map("cursor", "bad")), null)), -32602);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new ArrayList<java.util.concurrent.Future<McpRuntime.Reply>>();
            for (int i = 0; i < 100; i++) pending.add(pool.submit(() -> runtime.handle(rpc("tools/list", Map.of()), null)));
            for (var future : pending) equal(future.get().status(), 200);
        }
    }

    private static void http() throws Exception {
        var captured = new AtomicReference<Map<String, String>>();
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var backendWorkers = Executors.newVirtualThreadPerTaskExecutor();
        backend.setExecutor(backendWorkers);
        backend.createContext("/api/items", exchange -> {
            try (exchange) {
                captured.set(Json.map("authorization", exchange.getRequestHeaders().getFirst("Authorization"), "tenant", exchange.getRequestHeaders().getFirst("X-Tenant-Id"))
                        .entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue()))));
                int status = exchange.getRequestURI().getPath().endsWith("redirect") ? 302 : 200;
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/never-follow");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                var body = Json.bytes(Json.map("name", "served"));
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        backend.createContext("/large", exchange -> {
            try (exchange) { var body = new byte[4096]; exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
        });
        backend.createContext("/slow", exchange -> {
            try (exchange) { try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        });
        backend.start();
        URI upstream = URI.create("http://127.0.0.1:" + backend.getAddress().getPort());
        try (var executor = new HttpApiExecutor(upstream, Map.of("Authorization", "Bearer upstream-secret"));
             var client = HttpClient.newHttpClient();
             var server = new McpServer(new McpRuntime(McpCompiler.compile(SPEC).catalog(), executor), new InetSocketAddress("127.0.0.1", 0), "mcp-secret", Set.of("https://trusted.example"))) {
            server.start();
            var uri = URI.create("http://127.0.0.1:" + server.address().getPort() + "/mcp");
            var call = rpc("tools/call", Json.map("name", "getItem", "arguments", Json.map("path", Json.map("id", "1"), "headers", Json.map("X-Tenant-Id", 9))));
            var reply = client.send(post(uri, call).build(), HttpResponse.BodyHandlers.ofByteArray());
            equal(reply.statusCode(), 200);
            equal(Json.object(Json.object(Json.parse(reply.body())).get("result")).get("isError"), false);
            equal(captured.get(), Map.of("authorization", "Bearer upstream-secret", "tenant", "9"));
            equal(client.send(post(uri, call).setHeader("Authorization", "Bearer wrong").build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 401);
            equal(client.send(post(uri, call).setHeader("Authorization", "bearer mcp-secret").build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 200);
            equal(client.send(post(uri, call).header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 403);
            equal(client.send(post(uri, call).setHeader("Content-Type", "text/plain").build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 415);
            equal(client.send(post(uri, call).setHeader("Accept", "text/html").build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 406);
            equal(client.send(post(uri, call).setHeader("Accept", "application/json;q=0, text/event-stream").build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 406);
            equal(client.send(post(uri, call).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 405);
            equal(client.send(post(URI.create(uri + "/extra"), call).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 404);
            equal(client.send(post(uri, new byte[McpRuntime.MAX_REQUEST_BYTES + 1]).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), 413);
            equal(executor.execute(new McpRequest("GET", "/api/items/redirect", Map.of(), new byte[0])).status(), 302);
            try (var limited = new HttpApiExecutor(upstream, Map.of(), Duration.ofMillis(150), 100)) {
                rejectsIo(() -> limited.execute(new McpRequest("GET", "/large", Map.of(), new byte[0])));
                rejectsIo(() -> limited.execute(new McpRequest("GET", "/slow", Map.of(), new byte[0])));
            }
        } finally { backend.stop(0); backendWorkers.shutdownNow(); }
    }

    private static HttpRequest.Builder post(URI uri, byte[] body) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer mcp-secret")
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25").POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private static void stdio() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        var runtime = new McpRuntime(McpCompiler.compile(SPEC).catalog(), _ -> {
            started.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException e) { cancelled.countDown(); throw e; }
            throw new AssertionError("Expected cancellation");
        });
        try (var input = new java.io.PipedInputStream(); var client = new java.io.PipedOutputStream(input);
             var replies = new java.io.PipedInputStream(); var output = new java.io.PipedOutputStream(replies);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var serving = pool.submit(() -> { McpStdio.serve(runtime, input, output); return null; });
            client.write(rpc("tools/call", Json.map("name", "getItem", "arguments", Json.map("path", Json.map("id", "1")))));
            client.write('\n'); client.flush();
            check(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            client.write(Json.bytes(Json.map("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Json.map("requestId", "test"))));
            client.write('\n'); client.flush();
            check(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS));
            client.write(Json.bytes(Json.map("jsonrpc", "2.0", "id", "ping", "method", "ping")));
            client.write('\n'); client.flush();
            var read = pool.submit(() -> new java.io.BufferedReader(new java.io.InputStreamReader(replies, StandardCharsets.UTF_8)).readLine());
            equal(Json.object(Json.parse(read.get(3, java.util.concurrent.TimeUnit.SECONDS))).get("id"), "ping");
            client.close();
            serving.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static byte[] rpc(String method, Map<String, Object> params) { return Json.bytes(Json.map("jsonrpc", "2.0", "id", "test", "method", method, "params", params)); }
    private static Map<String, Object> modern(Map<String, Object> params) { var copy = new java.util.LinkedHashMap<>(params); copy.put("_meta", Json.map("io.modelcontextprotocol/protocolVersion", "2026-07-28", "io.modelcontextprotocol/clientCapabilities", Map.of())); return copy; }
    private static Map<String, String> modernHeaders(String method, String name) { var map = new java.util.LinkedHashMap<String, String>(); map.put("MCP-Protocol-Version", "2026-07-28"); map.put("Mcp-Method", method); if (name != null) map.put("Mcp-Name", name); return map; }
    private static Map<String, Object> result(McpRuntime.Reply reply) { return Json.object(Json.object(Json.parse(reply.body())).get("result")); }
    private static int code(McpRuntime.Reply reply) { return ((Number) Json.object(Json.object(Json.parse(reply.body())).get("error")).get("code")).intValue(); }
    private static void equal(Object actual, Object expected) { checks++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + " but got " + actual); }
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check failed"); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
    private interface IoAction { void run() throws IOException, InterruptedException; }
    private static void rejectsIo(IoAction action) throws InterruptedException { checks++; try { action.run(); } catch (IOException expected) { return; } throw new AssertionError("Expected I/O rejection"); }
}
