package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import no.beint.riss.SpecSets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static final byte[] UI = loadUi();

    private final List<SpecSet> specs;
    private final RissProperties properties;
    private final Map<String, String> etags;
    private final byte[] catalogJson;
    private final String catalogEtag;

    public RissController(RissProperties properties) {
        this(SpecSets.load(), properties);
    }

    RissController(List<SpecSet> specs, RissProperties properties) {
        this.specs = List.copyOf(specs);
        this.properties = properties;
        var tags = new LinkedHashMap<String, String>();
        for (var spec : this.specs) {
            tags.put(spec.name(), etag(spec.json()));
        }
        this.etags = Map.copyOf(tags);
        this.catalogJson = catalogBytes(this.specs);
        this.catalogEtag = etag(this.catalogJson);
    }

    @GetMapping(path = "/openapi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spec(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        if (specs.size() == 1) {
            return json(specs.getFirst(), ifNoneMatch);
        }
        return catalog(ifNoneMatch);
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
    public ResponseEntity<byte[]> namedUi(@PathVariable("name") String name) {
        if (!properties.isUiEnabled() || specs.size() <= 1) {
            return ResponseEntity.notFound().build();
        }
        return find(name)
                .map(spec -> ui(spec, "/openapi/" + spec.name()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private java.util.Optional<SpecSet> find(String name) {
        return specs.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    private ResponseEntity<byte[]> json(SpecSet spec, String ifNoneMatch) {
        return bytes(spec.json(), etags.get(spec.name()), ifNoneMatch);
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

    private ResponseEntity<byte[]> catalog(String ifNoneMatch) {
        return bytes(catalogJson, catalogEtag, ifNoneMatch);
    }

    private ResponseEntity<byte[]> bytes(byte[] body, String etag, String ifNoneMatch) {
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private static byte[] catalogBytes(List<SpecSet> specs) {
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
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null) {
            return false;
        }
        for (var part : ifNoneMatch.split(",")) {
            var candidate = part.trim();
            if ("*".equals(candidate) || candidate.equals(etag) || candidate.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private static String etag(byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
