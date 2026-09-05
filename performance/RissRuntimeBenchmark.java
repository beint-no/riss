package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.management.ManagementFactory;
import java.util.List;

public class RissRuntimeBenchmark {
    private static volatile Object sink;

    public static void main(String[] args) {
        var spec = new SpecSet() {
            public String name() { return "public"; }
            public byte[] json() { return new byte[] {123, 125}; }
        };
        var controller = new RissController(List.of(spec), new RissProperties());
        var request = new MockHttpServletRequest();
        request.setContextPath("/demo");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 20000; i++) sink = controller.ui(request);
        for (int round = 0; round < 3; round++) {
            long allocated = bean.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            for (int i = 0; i < 20000; i++) sink = controller.ui(request);
            long elapsed = System.nanoTime() - start;
            long bytes = bean.getCurrentThreadAllocatedBytes() - allocated;
            System.out.printf("UI: %.0f ns/request, %.0f bytes/request%n", elapsed / 20000.0, bytes / 20000.0);
        }
    }
}
