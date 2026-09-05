package no.beint.riss.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Standalone compiler and server entry point; secrets are read only from environment variables. */
public final class Main {
    private Main() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || arguments[0].equals("--help")) {
            System.err.println("""
                    Riss MCP — JDK-only compiler and tools server
                    compile --spec openapi.json --out catalog.json [--include operation1,operation2] [--read-only true]
                    stdio   --catalog catalog.json --upstream http://localhost:8080 [--api-token-env RISS_API_TOKEN]
                    serve   --catalog catalog.json --upstream http://localhost:8080 [--port 8082] [--bind 127.0.0.1]
                            [--api-token-env RISS_API_TOKEN] [--mcp-token-env RISS_MCP_TOKEN] [--origins https://client.example]
                    Both transports accept --max-request-bytes (default 1048576) and --max-response-bytes (default 8388608).
                    API and MCP tokens are separate. No credentials are written into the catalog or accepted as tool arguments.
                    """);
            return;
        }
        var options = options(arguments);
        switch (arguments[0]) {
            case "compile" -> compile(options);
            case "stdio", "serve" -> run(arguments[0], options);
            default -> throw new IllegalArgumentException("Unknown command: " + arguments[0]);
        }
    }

    private static void compile(Map<String, String> options) throws IOException {
        checkOptions(options, Set.of("spec", "out", "include", "read-only"));
        var result = McpCompiler.compile(read(Path.of(required(options, "spec")), 32 * 1024 * 1024),
                split(options.getOrDefault("include", "")), Boolean.parseBoolean(options.getOrDefault("read-only", "false")));
        var output = Path.of(required(options, "out")).toAbsolutePath();
        Files.createDirectories(output.getParent());
        var bytes = result.catalog();
        var temporary = Files.createTempFile(output.getParent(), ".riss-mcp-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        System.err.println("Compiled " + result.tools() + " tools into " + output + " (" + bytes.length + " bytes)");
        result.excluded().forEach(message -> System.err.println("Excluded: " + message));
    }

    private static void run(String mode, Map<String, String> options) throws Exception {
        checkOptions(options, Set.of("catalog", "upstream", "api-token-env", "mcp-token-env", "port", "bind", "origins", "max-request-bytes", "max-response-bytes"));
        var apiToken = System.getenv(options.getOrDefault("api-token-env", "RISS_API_TOKEN"));
        var headers = apiToken == null || apiToken.isBlank() ? Map.<String, String>of() : Map.of("Authorization", "Bearer " + apiToken);
        int requestLimit = Integer.parseInt(options.getOrDefault("max-request-bytes", "1048576"));
        int responseLimit = Integer.parseInt(options.getOrDefault("max-response-bytes", "8388608"));
        if (responseLimit < 1024 || responseLimit > 64 * 1024 * 1024) throw new IllegalArgumentException("Response limit must be between 1 KiB and 64 MiB");
        try (var executor = new HttpApiExecutor(URI.create(required(options, "upstream")), headers, java.time.Duration.ofSeconds(30), responseLimit)) {
            var runtime = new McpRuntime(read(Path.of(required(options, "catalog")), 64 * 1024 * 1024), executor, requestLimit);
            if (mode.equals("stdio")) {
                McpStdio.serve(runtime, System.in, System.out);
                return;
            }
            var token = System.getenv(options.getOrDefault("mcp-token-env", "RISS_MCP_TOKEN"));
            if (token != null && token.equals(apiToken)) throw new IllegalArgumentException("Use different MCP and API credentials");
            var address = new InetSocketAddress(InetAddress.getByName(options.getOrDefault("bind", "127.0.0.1")), Integer.parseInt(options.getOrDefault("port", "8082")));
            try (var server = new McpServer(runtime, address, token, split(options.getOrDefault("origins", "")))) {
                var stopped = new CountDownLatch(1);
                var hook = new Thread(() -> { server.close(); stopped.countDown(); }, "riss-mcp-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                server.start();
                System.err.println("Riss MCP serving " + runtime.toolCount() + " tools at " + server.address() + "/mcp");
                try { stopped.await(); }
                finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { } }
            }
        }
    }

    private static byte[] read(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Input exceeds byte limit: " + path);
            return bytes;
        }
    }

    private static Map<String, String> options(String[] arguments) {
        var options = new LinkedHashMap<String, String>();
        for (int i = 1; i < arguments.length; i += 2) {
            if (!arguments[i].startsWith("--") || i + 1 >= arguments.length) throw new IllegalArgumentException("Expected --option value");
            if (options.putIfAbsent(arguments[i].substring(2), arguments[i + 1]) != null) throw new IllegalArgumentException("Duplicate option");
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        var value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }

    private static Set<String> split(String value) { return value.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(value.split(","))); }

    private static void checkOptions(Map<String, String> options, Set<String> allowed) {
        for (var key : options.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown option: --" + key);
    }
}
