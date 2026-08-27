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
                "riss.compatibility.enabled=true",
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
    void redirectsAndExplorerPreserveContextAndServletPaths() throws Exception {
        var redirectResponse = get("/context/dispatcher/swagger-ui.html", HttpResponse.BodyHandlers.discarding());
        var explorerResponse = get("/context/dispatcher/openapi/ui", HttpResponse.BodyHandlers.ofString());

        assertEquals(307, redirectResponse.statusCode());
        assertEquals(
                "/context/dispatcher/openapi/ui",
                redirectResponse.headers().firstValue("Location").orElseThrow()
        );
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
