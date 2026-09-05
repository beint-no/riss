package no.beint.riss.mcp;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class McpBenchmark {
    private static volatile long consumed;

    public static void main(String[] args) throws Exception {
        var source = Files.readAllBytes(Path.of(args[0]));
        var timings = new long[9];
        McpCompiler.Result compiled = null;
        for (int run = 0; run < 15; run++) {
            long start = System.nanoTime();
            compiled = McpCompiler.compile(source);
            if (run >= 6) timings[run - 6] = System.nanoTime() - start;
        }
        Arrays.sort(timings);
        System.out.printf(java.util.Locale.ROOT, "tools=%d catalogBytes=%d compileMedianMs=%.3f%n", compiled.tools(), compiled.catalog().length, timings[4] / 1e6);
        var runtime = new McpRuntime(compiled.catalog(), _ -> { throw new AssertionError("Discovery must not call the API"); });
        var request = Json.bytes(Json.map("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()));
        for (int i = 0; i < 2000; i++) consumed += runtime.handle(request, null).size();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int iterations = 2000;
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) consumed += runtime.handle(request, null).size();
        long elapsed = System.nanoTime() - start;
        allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        System.out.printf("firstPageBytes=%d discoveryNsPerRequest=%d allocatedBytesPerRequest=%d%n",
                runtime.handle(request, null).size(), elapsed / iterations, allocated / iterations);
    }
}
