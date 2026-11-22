package no.beint.riss.validation;

import java.util.List;
import java.util.Map;

public interface ValidatorSet {
    Map<Class<?>, TypeValidator> typeValidators();

    default List<MethodValidatorEntry> methodValidators() {
        return List.of();
    }

    /**
     * External constrained types referenced via {@code @Valid} by this module.
     * {@link RissValidator} checks at startup that every required type has a registered validator.
     */
    default List<Class<?>> requiredTypes() {
        return List.of();
    }
}
