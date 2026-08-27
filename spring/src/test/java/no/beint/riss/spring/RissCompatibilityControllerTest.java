package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RissCompatibilityControllerTest {
    private static final byte[] PUBLIC_JSON = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Public\"}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SITE_JSON = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Site\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void singleDocumentUsesCompatibilityAliases() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON)), properties(null));

        for (var path : List.of("/openapi.json", "/v3/api-docs", "/api-docs")) {
            mvc.perform(get(path).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(PUBLIC_JSON));
        }
        for (var path : List.of("/swagger-ui", "/swagger-ui/", "/swagger-ui.html", "/swagger-ui/index.html")) {
            mvc.perform(get(path))
                    .andExpect(status().isPermanentRedirect())
                    .andExpect(result -> assertHeader(result.getResponse().getHeader(HttpHeaders.LOCATION), "/openapi/ui"));
        }
    }

    @Test
    void configuredPrimaryDocumentUsesNamedPaths() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON), spec("site", SITE_JSON)), properties("public"));

        mvc.perform(get("/openapi.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PUBLIC_JSON));
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(result -> assertHeader(
                        result.getResponse().getHeader(HttpHeaders.LOCATION),
                        "/openapi/public/ui"
                ));
    }

    @Test
    void uiAliasesPreserveServletContextPath() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON)), properties(null));

        mvc.perform(get("/demo/swagger-ui.html").contextPath("/demo"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(result -> assertHeader(
                        result.getResponse().getHeader(HttpHeaders.LOCATION),
                        "/demo/openapi/ui"
                ));
    }

    @Test
    void severalDocumentsRequireConfiguredPrimaryDocument() {
        assertThrows(
                IllegalStateException.class,
                () -> new RissCompatibilityController(
                        List.of(spec("public", PUBLIC_JSON), spec("site", SITE_JSON)),
                        properties(null)
                )
        );
    }

    @Test
    void aliasReturnsNotModifiedWhenEtagMatches() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON)), properties(null));
        var etag = mvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        mvc.perform(get("/v3/api-docs").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void uiAliasesReturnNotFoundWhenUiIsDisabled() throws Exception {
        var properties = properties(null);
        properties.setUiEnabled(false);
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON)), properties);

        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isNotFound());
    }

    private static MockMvc mvc(List<SpecSet> specs, RissProperties properties) {
        return MockMvcBuilders.standaloneSetup(new RissCompatibilityController(specs, properties)).build();
    }

    private static void assertHeader(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected header '" + expected + "' but was '" + actual + "'");
        }
    }

    private static RissProperties properties(String primaryDocument) {
        var properties = new RissProperties();
        properties.getCompatibility().setPrimaryDocument(primaryDocument);
        return properties;
    }

    private static SpecSet spec(String name, byte[] json) {
        return new SpecSet() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public byte[] json() {
                return json;
            }
        };
    }
}
