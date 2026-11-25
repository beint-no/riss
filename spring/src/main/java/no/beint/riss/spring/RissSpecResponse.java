package no.beint.riss.spring;

import no.beint.riss.SpecSet;
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

    /** Immutable prebuilt HTTP responses held by the controller for the lifetime of the application. */
    record Encoded(
            String etag,
            ResponseEntity<byte[]> rawOk,
            ResponseEntity<byte[]> rawNotModified,
            String gzipEtag,
            ResponseEntity<byte[]> gzipOk,
            ResponseEntity<byte[]> gzipNotModified
    ) {
        static Encoded of(SpecSet spec) {
            var raw = spec.json();
            var gzip = spec.gzipJson();
            if (gzip == null) {
                gzip = RissSpecResponse.gzip(raw);
            }
            var etag = RissSpecResponse.etag(raw);
            var gzipEtag = RissSpecResponse.etag(gzip);
            return new Encoded(
                    etag,
                    buildJson(raw, etag, null, false),
                    buildJson(null, etag, null, true),
                    gzipEtag,
                    buildJson(gzip, gzipEtag, "gzip", false),
                    buildJson(null, gzipEtag, "gzip", true)
            );
        }
    }

    static ResponseEntity<byte[]> json(Encoded document, String ifNoneMatch, String acceptEncoding) {
        if (acceptsGzip(acceptEncoding)) {
            return matches(ifNoneMatch, document.gzipEtag()) ? document.gzipNotModified() : document.gzipOk();
        }
        return matches(ifNoneMatch, document.etag()) ? document.rawNotModified() : document.rawOk();
    }

    static ResponseEntity<byte[]> json(byte[] body, String etag, String ifNoneMatch) {
        boolean notModified = matches(ifNoneMatch, etag);
        return buildJson(notModified ? null : body, etag, null, notModified);
    }

    private static ResponseEntity<byte[]> buildJson(
            byte[] body,
            String etag,
            String contentEncoding,
            boolean notModified
    ) {
        var response = notModified
                ? ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                : ResponseEntity.ok().contentType(JSON_UTF8);
        response.eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .header("X-Content-Type-Options", "nosniff");
        if (contentEncoding != null) {
            response.header(HttpHeaders.CONTENT_ENCODING, contentEncoding);
        }
        return notModified ? response.build() : response.body(body);
    }

    static ResponseEntity<byte[]> html(byte[] body, String ifNoneMatch) {
        var etag = etag(body);
        boolean notModified = matches(ifNoneMatch, etag);
        var response = notModified
                ? ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                : ResponseEntity.ok().contentType(HTML_UTF8);
        response.eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff");
        return notModified ? response.build() : response.body(body);
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isEmpty()) {
            return false;
        }
        int len = acceptEncoding.length();
        int start = 0;
        while (start < len) {
            int comma = acceptEncoding.indexOf(',', start);
            int partEnd = comma < 0 ? len : comma;
            int semi = indexOfChar(acceptEncoding, ';', start, partEnd);
            int codingStart = skipWhitespace(acceptEncoding, start, semi);
            int codingEnd = trimTrailingWhitespace(acceptEncoding, codingStart, semi);
            if (codingEnd - codingStart == 4 && acceptEncoding.regionMatches(true, codingStart, "gzip", 0, 4)) {
                return !hasZeroQuality(acceptEncoding, semi, partEnd);
            }
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return false;
    }

    private static boolean hasZeroQuality(String header, int semi, int partEnd) {
        int pos = semi;
        while (pos < partEnd) {
            int nextSemi = indexOfChar(header, ';', pos + 1, partEnd);
            int pStart = skipWhitespace(header, pos + 1, nextSemi);
            int pEnd = trimTrailingWhitespace(header, pStart, nextSemi);
            if (pEnd - pStart >= 2
                    && (header.charAt(pStart) == 'q' || header.charAt(pStart) == 'Q')
                    && header.charAt(pStart + 1) == '=') {
                int vStart = skipWhitespace(header, pStart + 2, pEnd);
                if (isZeroQualityValue(header, vStart, pEnd)) {
                    return true;
                }
            }
            pos = nextSemi;
        }
        return false;
    }

    private static boolean isZeroQualityValue(String header, int start, int end) {
        if (start >= end || header.charAt(start) != '0') {
            return false;
        }
        if (start + 1 == end) {
            return true;
        }
        if (header.charAt(start + 1) != '.') {
            return false;
        }
        for (int i = start + 2; i < end; i++) {
            if (header.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isEmpty()) {
            return false;
        }
        int len = ifNoneMatch.length();
        int etagLen = etag.length();
        int start = 0;
        while (start < len) {
            int comma = ifNoneMatch.indexOf(',', start);
            int partEnd = comma < 0 ? len : comma;
            int tStart = skipWhitespace(ifNoneMatch, start, partEnd);
            int tEnd = trimTrailingWhitespace(ifNoneMatch, tStart, partEnd);
            int tLen = tEnd - tStart;
            if (tLen == 1 && ifNoneMatch.charAt(tStart) == '*') {
                return true;
            }
            if (tLen == etagLen && ifNoneMatch.regionMatches(tStart, etag, 0, etagLen)) {
                return true;
            }
            if (tLen == etagLen + 2
                    && ifNoneMatch.charAt(tStart) == 'W'
                    && ifNoneMatch.charAt(tStart + 1) == '/'
                    && ifNoneMatch.regionMatches(tStart + 2, etag, 0, etagLen)) {
                return true;
            }
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return false;
    }

    private static int indexOfChar(String s, char target, int from, int end) {
        int idx = s.indexOf(target, from);
        return (idx < 0 || idx >= end) ? end : idx;
    }

    private static int skipWhitespace(String s, int from, int end) {
        while (from < end && s.charAt(from) <= ' ') {
            from++;
        }
        return from;
    }

    private static int trimTrailingWhitespace(String s, int from, int end) {
        while (end > from && s.charAt(end - 1) <= ' ') {
            end--;
        }
        return end;
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
