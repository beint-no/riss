package no.beint.riss.spring;

import jakarta.servlet.http.HttpServletRequest;
import no.beint.riss.SpecSet;
import no.beint.riss.SpecSets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves compiled specs on a fixed convention:
 * {@code GET /openapi} and {@code GET /openapi/ui} when there is one document,
 * {@code GET /openapi/{name}} and {@code GET /openapi/{name}/ui} when an app
 * publishes more than one document.
 */
@RestController
public class RissController {
    private static final byte[][] UI = loadUi();
    private static final int UI_SIZE = Arrays.stream(UI).mapToInt(part -> part.length).sum();

    private final List<SpecSet> specs;
    private final RissProperties properties;
    private final Map<String, String> etags;

    public RissController(RissProperties properties) {
        this(SpecSets.load(), properties);
    }

    RissController(List<SpecSet> specs, RissProperties properties) {
        this.specs = List.copyOf(specs);
        this.properties = properties;
        var tags = new LinkedHashMap<String, String>();
        for (var spec : this.specs) {
            tags.put(spec.name(), RissSpecResponse.etag(spec.json()));
        }
        this.etags = Map.copyOf(tags);
    }

    @GetMapping(path = "/openapi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spec(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request
    ) {
        if (specs.size() == 1) {
            return json(specs.getFirst(), ifNoneMatch);
        }
        return catalog(request, ifNoneMatch);
    }

    @GetMapping(path = "/openapi/ui", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> ui(HttpServletRequest request) {
        if (!properties.isUiEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (specs.size() == 1) {
            return ui(RissRequestPath.resolve(request, "/openapi"));
        }
        return catalogUi(request);
    }

    @GetMapping(path = "/openapi/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> namedSpec(
            @PathVariable("name") String name,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        if (specs.size() <= 1) {
            return ResponseEntity.notFound().build();
        }
        return find(name)
                .map(spec -> json(spec, ifNoneMatch))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/openapi/{name}/ui", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> namedUi(@PathVariable("name") String name, HttpServletRequest request) {
        if (!properties.isUiEnabled() || specs.size() <= 1) {
            return ResponseEntity.notFound().build();
        }
        return find(name)
                .map(spec -> ui(RissRequestPath.resolve(request, "/openapi/" + spec.name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private java.util.Optional<SpecSet> find(String name) {
        return specs.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    private ResponseEntity<byte[]> json(SpecSet spec, String ifNoneMatch) {
        return bytes(spec.json(), etags.get(spec.name()), ifNoneMatch);
    }

    private ResponseEntity<byte[]> ui(String specPath) {
        var path = specPath.getBytes(StandardCharsets.UTF_8);
        var body = new byte[UI_SIZE + path.length * (UI.length - 1)];
        var offset = 0;
        for (var index = 0; index < UI.length; index++) {
            if (index > 0) {
                System.arraycopy(path, 0, body, offset, path.length);
                offset += path.length;
            }
            System.arraycopy(UI[index], 0, body, offset, UI[index].length);
            offset += UI[index].length;
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private ResponseEntity<byte[]> catalog(HttpServletRequest request, String ifNoneMatch) {
        var body = catalogBytes(specs, request);
        return bytes(body, RissSpecResponse.etag(body), ifNoneMatch);
    }

    private ResponseEntity<byte[]> bytes(byte[] body, String etag, String ifNoneMatch) {
        return RissSpecResponse.json(body, etag, ifNoneMatch);
    }

    private static byte[] catalogBytes(List<SpecSet> specs, HttpServletRequest request) {
        var json = new StringBuilder("{\"specs\":[");
        for (var index = 0; index < specs.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            var spec = specs.get(index);
            var name = jsonEscape(spec.name());
            var specPath = jsonEscape(RissRequestPath.resolve(request, "/openapi/" + spec.name()));
            json.append("{\"name\":\"").append(name)
                    .append("\",\"json\":\"").append(specPath)
                    .append("\",\"ui\":\"").append(specPath)
                    .append("/ui\"}");
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<byte[]> catalogUi(HttpServletRequest request) {
        var html = new StringBuilder("""
                <!doctype html><html lang="en"><meta charset="utf-8">
                <title>API documents</title>
                <body><h1>API documents</h1><ul>
                """);
        specs.forEach(spec -> {
            var name = escape(spec.name());
            var specPath = escape(RissRequestPath.resolve(request, "/openapi/" + spec.name()));
            html.append("<li><a href=\"").append(specPath)
                    .append("/ui\">")
                    .append(name)
                    .append("</a> · <a href=\"").append(specPath)
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

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[][] loadUi() {
        try (InputStream in = RissController.class.getResourceAsStream("ui.html")) {
            if (in == null) {
                throw new IllegalStateException("Missing Riss UI resource");
            }
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\Q{{SPEC_PATH}}\\E", -1))
                    .map(part -> part.getBytes(StandardCharsets.UTF_8))
                    .toArray(byte[][]::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Riss UI", exception);
        }
    }
}
