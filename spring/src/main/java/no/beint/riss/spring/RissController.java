package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import no.beint.riss.SpecSets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves compiled specs on a fixed convention:
 * {@code GET /openapi} and {@code GET /openapi/ui} when there is one document,
 * {@code GET /openapi/{name}} and {@code GET /openapi/{name}/ui} when an app
 * publishes more than one document.
 */
@RestController
public class RissController {
    private static final byte[] UI = loadUi();

    private final List<SpecSet> specs;
    private final RissProperties properties;

    public RissController(RissProperties properties) {
        this.specs = SpecSets.load();
        this.properties = properties;
    }

    @GetMapping(path = "/openapi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spec() {
        if (specs.size() == 1) {
            return json(specs.getFirst());
        }
        return catalog();
    }

    @GetMapping(path = "/openapi/ui", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> ui() {
        if (!properties.isUiEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (specs.size() == 1) {
            return ui(specs.getFirst(), "/openapi");
        }
        return catalogUi();
    }

    @GetMapping(path = "/openapi/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> namedSpec(@PathVariable("name") String name) {
        return find(name)
                .map(this::json)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/openapi/{name}/ui", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> namedUi(@PathVariable("name") String name) {
        if (!properties.isUiEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return find(name)
                .map(spec -> ui(spec, "/openapi/" + spec.name()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private java.util.Optional<SpecSet> find(String name) {
        return specs.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    private ResponseEntity<byte[]> json(SpecSet spec) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(spec.json());
    }

    private ResponseEntity<byte[]> ui(SpecSet spec, String specPath) {
        var html = new String(UI, StandardCharsets.UTF_8)
                .replace("{{SPEC_PATH}}", specPath)
                .replace("{{TITLE}}", spec.name());
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> catalog() {
        var json = new StringBuilder("{\"specs\":[");
        for (var index = 0; index < specs.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            var name = specs.get(index).name().replace("\\", "\\\\").replace("\"", "\\\"");
            json.append("{\"name\":\"").append(name)
                    .append("\",\"json\":\"/openapi/").append(name)
                    .append("\",\"ui\":\"/openapi/").append(name)
                    .append("/ui\"}");
        }
        json.append("]}");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> catalogUi() {
        var html = new StringBuilder("""
                <!doctype html><html lang="en"><meta charset="utf-8">
                <title>API documents</title>
                <body><h1>API documents</h1><ul>
                """);
        specs.forEach(spec -> {
            var name = escape(spec.name());
            html.append("<li><a href=\"/openapi/")
                    .append(name)
                    .append("/ui\">")
                    .append(name)
                    .append("</a> · <a href=\"/openapi/")
                    .append(name)
                    .append("\">JSON</a></li>");
        });
        html.append("</ul></body></html>");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
