package no.beint.riss.validation;

@FunctionalInterface
public interface ValidationSink {
    void reject(String field, String message);
}
