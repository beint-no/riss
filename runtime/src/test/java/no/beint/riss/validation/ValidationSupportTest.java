package no.beint.riss.validation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationSupportTest {
    @Test
    void emailValidationMatchesExpectedSemantics() {
        assertTrue(ValidationSupport.isValidOptionalEmail(null));
        assertTrue(ValidationSupport.isValidOptionalEmail(""));
        assertFalse(ValidationSupport.isValidEmail(""));
        assertTrue(ValidationSupport.isValidEmail("user+tag@example.com"));
        assertTrue(ValidationSupport.isValidEmail("a@localhost"));
        assertTrue(ValidationSupport.isValidEmail("first.last@sub.domain.no"));

        assertFalse(ValidationSupport.isValidEmail("plain"));
        assertFalse(ValidationSupport.isValidEmail("@example.com"));
        assertFalse(ValidationSupport.isValidEmail("user@"));
        assertFalse(ValidationSupport.isValidEmail("a@b@c.com"));
        assertFalse(ValidationSupport.isValidEmail(".user@example.com"));
        assertFalse(ValidationSupport.isValidEmail("user.@example.com"));
        assertFalse(ValidationSupport.isValidEmail("us..er@example.com"));
        assertFalse(ValidationSupport.isValidEmail("user@-example.com"));
        assertFalse(ValidationSupport.isValidEmail("user@example-.com"));
        assertFalse(ValidationSupport.isValidEmail("user@ex..ample.com"));
        assertFalse(ValidationSupport.isValidEmail("user name@example.com"));
    }

    @Test
    void digitsValidationChecksIntegerAndFractionBounds() {
        assertTrue(ValidationSupport.checkDigits(new BigDecimal("123.45"), 3, 2));
        assertTrue(ValidationSupport.checkDigits(new BigDecimal("0.00"), 3, 2));
        assertTrue(ValidationSupport.checkDigits(new BigDecimal("-999.99"), 3, 2));
        assertFalse(ValidationSupport.checkDigits(new BigDecimal("1000.00"), 3, 2));
        assertFalse(ValidationSupport.checkDigits(new BigDecimal("12.345"), 3, 2));
        assertTrue(ValidationSupport.checkDigits(new BigDecimal("1E+2"), 3, 0));
        assertFalse(ValidationSupport.checkDigits(new BigDecimal("1E+3"), 3, 0));
        assertFalse(ValidationSupport.checkDigits("not-a-number", 3, 2));
        assertFalse(ValidationSupport.checkDigits(Double.NaN, 3, 2));
    }

    @Test
    void numericBoundsAndSignChecks() {
        assertTrue(ValidationSupport.minLong(10, 10));
        assertFalse(ValidationSupport.minLong(9, 10));
        assertTrue(ValidationSupport.maxLong(10L, 10L));
        assertFalse(ValidationSupport.maxLong(11L, 10L));

        var half = new BigDecimal("0.5");
        assertTrue(ValidationSupport.decimalMin(new BigDecimal("0.5"), half, true));
        assertFalse(ValidationSupport.decimalMin(new BigDecimal("0.5"), half, false));
        assertTrue(ValidationSupport.decimalMin(new BigDecimal("0.51"), half, false));

        assertTrue(ValidationSupport.decimalMax(new BigDecimal("0.5"), half, true));
        assertFalse(ValidationSupport.decimalMax(new BigDecimal("0.5"), half, false));
        assertTrue(ValidationSupport.decimalMax(new BigDecimal("0.49"), half, false));

        assertTrue(ValidationSupport.isPositive(1));
        assertFalse(ValidationSupport.isPositive(0));
        assertTrue(ValidationSupport.isPositiveOrZero(0));
        assertFalse(ValidationSupport.isPositiveOrZero(-1));
        assertTrue(ValidationSupport.isNegative(new BigDecimal("-0.01")));
        assertFalse(ValidationSupport.isNegative(BigDecimal.ZERO));
        assertTrue(ValidationSupport.isNegativeOrZero(BigDecimal.ZERO));
        assertFalse(ValidationSupport.isPositive(Double.NaN));
    }

    @Test
    void temporalAndLengthChecks() {
        assertTrue(ValidationSupport.isPast(LocalDate.now().minusDays(1)));
        assertFalse(ValidationSupport.isPast(LocalDate.now()));
        assertTrue(ValidationSupport.isPastOrPresent(LocalDate.now()));
        assertFalse(ValidationSupport.isPastOrPresent(LocalDate.now().plusDays(1)));

        assertTrue(ValidationSupport.isFuture(LocalDate.now().plusDays(1)));
        assertFalse(ValidationSupport.isFuture(LocalDate.now()));
        assertTrue(ValidationSupport.isFutureOrPresent(LocalDate.now()));
        assertTrue(ValidationSupport.isPast(Instant.now().minusSeconds(60)));
        assertTrue(ValidationSupport.isFuture(YearMonth.now().plusMonths(1)));

        assertEquals(3, ValidationSupport.lengthOf("abc"));
        assertEquals(2, ValidationSupport.lengthOf(List.of("a", "b")));
        assertEquals(1, ValidationSupport.lengthOf(Map.of("k", "v")));
        assertEquals(2, ValidationSupport.lengthOf(new String[]{"x", "y"}));
        assertEquals(3, ValidationSupport.lengthOf(new int[]{1, 2, 3}));
    }

    @Test
    void validatorChecksHierarchyAndThrowsFormattedException() {
        ValidatorSet set = () -> Map.of(
                BaseBean.class, (target, prefix, sink, root) -> {
                    var base = (BaseBean) target;
                    if (!ValidationSupport.isNotBlank(base.baseName)) {
                        sink.reject(ValidationSupport.path(prefix, "baseName"), "must not be blank");
                    }
                },
                ChildBean.class, (target, prefix, sink, root) -> {
                    var child = (ChildBean) target;
                    if (!ValidationSupport.isPositive(child.count)) {
                        sink.reject(ValidationSupport.path(prefix, "count"), "must be greater than 0");
                    }
                }
        );

        var validator = new RissValidator(List.of(set));
        var violations = validator.validate(new ChildBean("", 0));
        assertEquals(2, violations.size());
        assertThrows(RissValidationException.class, () -> validator.validateOrThrow(new ChildBean("", 0)));
    }

    static class BaseBean {
        final String baseName;

        BaseBean(String baseName) {
            this.baseName = baseName;
        }
    }

    static class ChildBean extends BaseBean {
        final int count;

        ChildBean(String baseName, int count) {
            super(baseName);
            this.count = count;
        }
    }
}
