package no.beint.riss;

/**
 * A compiled OpenAPI 3.1 document. Implementations are generated at compile time.
 */
public interface SpecSet {
    String name();

    byte[] json();

    /** Returns an optional gzip encoding of {@link #json()}, or null when unavailable. */
    default byte[] gzipJson() {
        return null;
    }
}
