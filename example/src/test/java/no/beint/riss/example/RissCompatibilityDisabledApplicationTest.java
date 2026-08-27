package no.beint.riss.example;

import no.beint.riss.spring.RissCompatibilityController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/demo"
)
class RissCompatibilityDisabledApplicationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    void compatibilityAliasesAreDisabledByDefault() throws Exception {
        assertTrue(context.getBeansOfType(RissCompatibilityController.class).isEmpty());

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/demo/openapi.json")).build(),
                HttpResponse.BodyHandlers.discarding()
        );

        assertEquals(404, response.statusCode());
    }
}
