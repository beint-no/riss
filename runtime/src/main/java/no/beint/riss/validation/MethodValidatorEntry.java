package no.beint.riss.validation;

import java.util.Arrays;
import java.util.Objects;

public record MethodValidatorEntry(
        Class<?> controllerClass,
        String methodName,
        Class<?>[] parameterTypes,
        MethodParameterValidator validator
) {
    public MethodValidatorEntry {
        Objects.requireNonNull(controllerClass, "controllerClass");
        Objects.requireNonNull(methodName, "methodName");
        parameterTypes = Objects.requireNonNull(parameterTypes, "parameterTypes").clone();
        Objects.requireNonNull(validator, "validator");
    }

    @Override
    public Class<?>[] parameterTypes() {
        return parameterTypes.clone();
    }

    public boolean matches(Class<?> declaringClass, String name, Class<?>[] types) {
        return controllerClass.equals(declaringClass)
                && methodName.equals(name)
                && Arrays.equals(parameterTypes, types);
    }
}
