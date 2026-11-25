package no.beint.riss.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/context",
                "spring.mvc.servlet.path=/dispatcher"
        }
)
class RissServletPathApplicationTest {
    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void openApiJsonAndExplorerPreserveContextAndServletPaths() throws Exception {
        var specResponse = get("/context/dispatcher/openapi.json", HttpResponse.BodyHandlers.ofString());
        var explorerResponse = get("/context/dispatcher/openapi/ui", HttpResponse.BodyHandlers.ofString());

        assertEquals(200, specResponse.statusCode());
        assertTrue(specResponse.body().contains("\"openapi\":\"3.1.0\""));
        assertEquals(200, explorerResponse.statusCode());
        assertTrue(explorerResponse.body().contains("const specPath = \"/context/dispatcher/openapi\";"));
    }

    private <T> HttpResponse<T> get(String path, HttpResponse.BodyHandler<T> bodyHandler) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                bodyHandler
        );
    }
}
