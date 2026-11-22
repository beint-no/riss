package no.beint.riss.validation;

import java.util.List;
import java.util.Map;

public interface ValidatorSet {
    Map<Class<?>, TypeValidator> typeValidators();

    default List<MethodValidatorEntry> methodValidators() {
        return List.of();
    }
}
