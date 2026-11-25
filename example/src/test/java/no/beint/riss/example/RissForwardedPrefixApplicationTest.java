package no.beint.riss.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.forward-headers-strategy=framework",
                "spring.mvc.forwarded-headers.use-forwarded-prefix=true"
        }
)
class RissForwardedPrefixApplicationTest {
    private static final String FORWARDED_PREFIX_HEADER = "X-Forwarded-Prefix";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void explorerPathCannotBecomeSchemeRelative() throws Exception {
        var response = get(
                "/openapi/ui",
                "//attacker.example",
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("const specPath = \"/attacker.example/openapi\";"));
    }

    @Test
    void forwardedPrefixIsEncodedBeforeRenderingExplorerHtml() throws Exception {
        var hostilePrefix = "/\";</script><script>globalThis.rissInjected=true</script>";
        var response = get("/openapi/ui", hostilePrefix, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(hostilePrefix));
        assertFalse(response.body().contains("</script><script>"));
        assertTrue(response.body().contains("/%22;%3C/script%3E%3Cscript%3EglobalThis.rissInjected=true%3C/script%3E/openapi"));
    }

    @Test
    void validPercentEncodingInForwardedPrefixIsPreserved() throws Exception {
        var forwardedPrefix = "/gateway%2Ftenant%20one";
        var explorerResponse = get("/openapi/ui", forwardedPrefix, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, explorerResponse.statusCode());
        assertTrue(explorerResponse.body().contains("/gateway%2Ftenant%20one/openapi"));
    }

    private <T> HttpResponse<T> get(
            String path,
            String forwardedPrefix,
            HttpResponse.BodyHandler<T> bodyHandler
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(FORWARDED_PREFIX_HEADER, forwardedPrefix)
                        .build(),
                bodyHandler
        );
    }
}
