package no.beint.riss.spring;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

final class RissSpecResponse {
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private RissSpecResponse() {
    }

    /** A compiled document with its gzip representation, both encoded once at startup. */
    record Encoded(byte[] raw, String etag, byte[] gzip, String gzipEtag) {
        static Encoded of(byte[] raw) {
            var etag = RissSpecResponse.etag(raw);
            return new Encoded(raw, etag, RissSpecResponse.gzip(raw), etag.substring(0, etag.length() - 1) + "-gzip\"");
        }
    }

    static ResponseEntity<byte[]> json(Encoded document, String ifNoneMatch, String acceptEncoding) {
        if (acceptsGzip(acceptEncoding)) {
            return json(document.gzip(), document.gzipEtag(), ifNoneMatch, "gzip");
        }
        return json(document.raw(), document.etag(), ifNoneMatch, null);
    }

    static ResponseEntity<byte[]> json(byte[] body, String etag, String ifNoneMatch) {
        return json(body, etag, ifNoneMatch, null);
    }

    private static ResponseEntity<byte[]> json(byte[] body, String etag, String ifNoneMatch, String contentEncoding) {
        var response = matches(ifNoneMatch, etag)
                ? ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                : ResponseEntity.ok().contentType(JSON_UTF8);
        response.eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .header("X-Content-Type-Options", "nosniff");
        if (contentEncoding != null) {
            response.header(HttpHeaders.CONTENT_ENCODING, contentEncoding);
        }
        return matches(ifNoneMatch, etag) ? response.build() : response.body(body);
    }

    static ResponseEntity<byte[]> html(byte[] body, String ifNoneMatch) {
        var etag = etag(body);
        var response = matches(ifNoneMatch, etag)
                ? ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                : ResponseEntity.ok().contentType(HTML_UTF8);
        response.eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff");
        return matches(ifNoneMatch, etag) ? response.build() : response.body(body);
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (var part : acceptEncoding.split(",")) {
            var parameters = part.split(";");
            if (!"gzip".equalsIgnoreCase(parameters[0].trim())) {
                continue;
            }
            for (var index = 1; index < parameters.length; index++) {
                var parameter = parameters[index].trim();
                if (parameter.startsWith("q=") && parameter.substring(2).trim().matches("0(\\.0*)?")) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
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

    static String etag(byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] gzip(byte[] body) {
        var buffer = new ByteArrayOutputStream(Math.max(64, body.length / 6));
        try (var out = new GZIPOutputStream(buffer)) {
            out.write(body);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return buffer.toByteArray();
    }
}
