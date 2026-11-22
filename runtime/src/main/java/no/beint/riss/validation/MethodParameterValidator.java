package no.beint.riss.validation;

@FunctionalInterface
public interface MethodParameterValidator {
    void validate(Object[] args, ValidationSink sink, RissValidator root);
}
