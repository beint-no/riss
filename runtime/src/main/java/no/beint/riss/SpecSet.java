package no.beint.riss;

/**
 * A compiled OpenAPI 3.1 document. Implementations are generated at compile time.
 */
public interface SpecSet {
    String name();

    byte[] json();
}
