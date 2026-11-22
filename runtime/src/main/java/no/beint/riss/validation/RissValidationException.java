package no.beint.riss.validation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class RissValidationException extends RuntimeException {
    private final List<ValidationViolation> violations;

    public RissValidationException(List<ValidationViolation> violations) {
        super(formatMessage(violations));
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    public List<ValidationViolation> violations() {
        return violations;
    }

    private static String formatMessage(List<ValidationViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "Validation failed";
        }
        return violations.stream()
                .map(v -> v.field().isEmpty() ? v.message() : v.field() + ": " + v.message())
                .collect(Collectors.joining(", "));
    }
}
