package no.beint.riss.spring;

import no.beint.riss.validation.RissValidationException;
import no.beint.riss.validation.ValidationViolation;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fallback handler for {@link RissValidationException} when an application does not register a custom one.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public final class RissValidationExceptionHandler {
    @ExceptionHandler(RissValidationException.class)
    public ProblemDetail handleRissValidationException(RissValidationException exception) {
        var violations = exception.violations();
        var detail = violations.isEmpty()
                ? "Validation failed"
                : violations.stream().map(ValidationViolation::message).distinct().reduce((a, b) -> a + "; " + b).orElse("Validation failed");
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation failed");
        List<Map<String, String>> fieldErrors = violations.stream().map(v -> {
            var entry = new LinkedHashMap<String, String>();
            entry.put("field", v.field());
            entry.put("message", v.message());
            return (Map<String, String>) entry;
        }).toList();
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }
}
