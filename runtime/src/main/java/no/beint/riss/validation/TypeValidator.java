package no.beint.riss.validation;

@FunctionalInterface
public interface TypeValidator {
    void validate(Object target, String prefix, ValidationSink sink, RissValidator root);
}
