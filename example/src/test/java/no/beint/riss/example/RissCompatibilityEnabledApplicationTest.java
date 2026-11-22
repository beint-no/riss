package no.beint.riss.example;

import no.beint.riss.example.generated.ExampleSpec;
import no.beint.riss.spring.RissCompatibilityController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "riss.compatibility.enabled=true",
                "server.servlet.context-path=/demo"
        }
)
class RissCompatibilityEnabledApplicationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void compatibilityAliasesAreEnabledThroughAutoConfiguration() throws Exception {
        assertEquals(1, context.getBeansOfType(RissCompatibilityController.class).size());

        var response = get("/demo/openapi.json", HttpResponse.BodyHandlers.ofString());
        var explorerResponse = get("/demo/openapi/ui", HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"openapi\":\"3.1.0\""));
        assertEquals(200, explorerResponse.statusCode());
        assertTrue(explorerResponse.body().contains("const specPath = \"/demo/openapi\";"));
    }

    @Test
    void generatedGzipIsServedByBothRoutesAndSupportsRevalidation() throws Exception {
        var spec = new ExampleSpec();
        var gzip = spec.gzipJson();
        try (var input = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            assertArrayEquals(spec.json(), input.readAllBytes());
        }

        String etag = null;
        for (var path : List.of("/demo/openapi", "/demo/openapi.json")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Accept-Encoding", "gzip");
            if (etag != null) {
                request.header("If-None-Match", etag);
            }
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElseThrow());
            if (etag == null) {
                assertEquals(200, response.statusCode());
                assertArrayEquals(gzip, response.body());
                etag = response.headers().firstValue("ETag").orElseThrow();
            } else {
                assertEquals(304, response.statusCode());
                assertEquals(etag, response.headers().firstValue("ETag").orElseThrow());
                assertEquals(0, response.body().length);
            }
        }
    }

    @Test
    void uiAliasesPreserveServletContextPath() throws Exception {
        for (var path : List.of(
                "/demo/swagger-ui",
                "/demo/swagger-ui/",
                "/demo/swagger-ui.html",
                "/demo/swagger-ui/index.html"
        )) {
            var response = get(path, HttpResponse.BodyHandlers.discarding());

            assertEquals(307, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("/demo/openapi/ui", response.headers().firstValue("Location").orElseThrow());
        }
    }

    private <T> HttpResponse<T> get(String path, HttpResponse.BodyHandler<T> bodyHandler) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                bodyHandler
        );
    }
}
