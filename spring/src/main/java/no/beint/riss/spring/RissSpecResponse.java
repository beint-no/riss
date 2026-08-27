package no.beint.riss.spring;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class RissSpecResponse {
    private RissSpecResponse() {
    }

    static ResponseEntity<byte[]> json(byte[] body, String etag, String ifNoneMatch) {
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
}
