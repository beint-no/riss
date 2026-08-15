package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RestController
public class RissController {
    private static final byte[] UI = loadUi();

    private final SpecSet spec;
    private final RissProperties properties;

    public RissController(SpecSet spec, RissProperties properties) {
        this.spec = spec;
        this.properties = properties;
    }

    @GetMapping(path = "${riss.path:/openapi}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spec() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(spec.json());
    }

    @GetMapping(path = "${riss.ui-path:/openapi/ui}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> ui() {
        if (!properties.isUiEnabled()) {
            return ResponseEntity.notFound().build();
        }
        var html = new String(UI, StandardCharsets.UTF_8)
                .replace("{{SPEC_PATH}}", properties.getPath())
                .replace("{{TITLE}}", spec.name());
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] loadUi() {
        try (InputStream in = RissController.class.getResourceAsStream("ui.html")) {
            if (in == null) {
                throw new IllegalStateException("Missing Riss UI resource");
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Riss UI", exception);
        }
    }
}
