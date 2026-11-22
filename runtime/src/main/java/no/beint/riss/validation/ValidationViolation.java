package no.beint.riss.validation;

import java.util.Objects;

public record ValidationViolation(String field, String message) {
    public ValidationViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
