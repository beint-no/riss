package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RissControllerTest {
    private static final byte[] PUBLIC_JSON = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Public\"}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PEPPOL_JSON = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Peppol\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void singleDocumentUsesRootPathsOnly() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON)));

        mvc.perform(get("/openapi").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PUBLIC_JSON));
        mvc.perform(get("/openapi/ui").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var body = result.getResponse().getContentAsString();
                    if (!body.contains("const specPath = \"/openapi\";") || !body.contains("id=\"token\"")) {
                        throw new AssertionError(body);
                    }
                });
        mvc.perform(get("/openapi/public").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mvc.perform(get("/openapi/public/ui").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void severalDocumentsUseNamedPaths() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON), spec("peppol", PEPPOL_JSON)));

        mvc.perform(get("/openapi").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var body = result.getResponse().getContentAsString();
                    if (!body.contains("\"name\":\"public\"") || !body.contains("\"json\":\"/openapi/public\"")
                            || !body.contains("\"name\":\"peppol\"") || !body.contains("\"ui\":\"/openapi/peppol/ui\"")) {
                        throw new AssertionError(body);
                    }
                });
        mvc.perform(get("/openapi/public").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PUBLIC_JSON));
        mvc.perform(get("/openapi/peppol").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PEPPOL_JSON));
        mvc.perform(get("/openapi/peppol/ui").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
        mvc.perform(get("/openapi/missing").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void specReturnsNotModifiedWhenEtagMatches() throws Exception {
        var mvc = mvc(List.of(spec("public", PUBLIC_JSON)));
        var etag = mvc.perform(get("/openapi").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        mvc.perform(get("/openapi").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void uiCanBeDisabled() throws Exception {
        var properties = new RissProperties();
        properties.setUiEnabled(false);
        var mvc = MockMvcBuilders.standaloneSetup(new RissController(List.of(spec("public", PUBLIC_JSON)), properties)).build();

        mvc.perform(get("/openapi").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mvc.perform(get("/openapi/ui").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    private static MockMvc mvc(List<SpecSet> specs) {
        return MockMvcBuilders.standaloneSetup(new RissController(specs, new RissProperties())).build();
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
