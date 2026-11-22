package no.beint.riss.validation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RissValidator {
    private static final MethodParameterValidator NOOP_METHOD = (args, sink, root) -> {};

    private final Map<Class<?>, TypeValidator> directTypeValidators;
    private final Map<Class<?>, List<MethodValidatorEntry>> methodEntriesByController;
    private final ConcurrentHashMap<Class<?>, List<TypeValidator>> resolvedTypeValidators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Method, MethodParameterValidator> resolvedMethodValidators = new ConcurrentHashMap<>();

    public RissValidator(Collection<ValidatorSet> sets) {
        var types = new HashMap<Class<?>, TypeValidator>();
        var methods = new HashMap<Class<?>, List<MethodValidatorEntry>>();
        var required = new LinkedHashSet<Class<?>>();
        for (var set : sets) {
            types.putAll(set.typeValidators());
            for (var entry : set.methodValidators()) {
                methods.computeIfAbsent(entry.controllerClass(), ignored -> new ArrayList<>()).add(entry);
            }
            required.addAll(set.requiredTypes());
        }
        this.directTypeValidators = Map.copyOf(types);
        var immutableMethods = new HashMap<Class<?>, List<MethodValidatorEntry>>();
        methods.forEach((k, v) -> immutableMethods.put(k, List.copyOf(v)));
        this.methodEntriesByController = Map.copyOf(immutableMethods);
        if (!required.isEmpty()) {
            var missing = new ArrayList<String>();
            for (var requiredType : required) {
                if (validatorsFor(requiredType).isEmpty()) {
                    missing.add(requiredType.getName());
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                    "Missing Riss validator for " + String.join(", ", missing)
                        + " — apply the 'no.beint.riss.validation' plugin to the module(s) defining them"
                );
            }
        }
    }

    public static RissValidator load() {
        return new RissValidator(ValidatorSets.load());
    }

    public List<ValidationViolation> validate(Object target) {
        if (target == null) {
            return List.of();
        }
        var violations = new ArrayList<ValidationViolation>();
        validate(target, "", (field, message) -> violations.add(new ValidationViolation(field, message)));
        return violations.isEmpty() ? List.of() : List.copyOf(violations);
    }

    public void validateOrThrow(Object target) {
        var violations = validate(target);
        if (!violations.isEmpty()) {
            throw new RissValidationException(violations);
        }
    }

    public void validate(Object target, String prefix, ValidationSink sink) {
        if (target == null) {
            return;
        }
        var validators = validatorsFor(target.getClass());
        if (!validators.isEmpty()) {
            for (var validator : validators) {
                validator.validate(target, prefix, sink, this);
            }
            return;
        }
        if (target instanceof Iterable<?> iterable) {
            int i = 0;
            for (var element : iterable) {
                if (element != null) {
                    validate(element, ValidationSupport.indexedPath(prefix, i), sink);
                }
                i++;
            }
        } else if (target instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    validate(entry.getValue(), ValidationSupport.mapPath(prefix, entry.getKey()), sink);
                }
            }
        }
    }

    public boolean hasMethodValidator(Method method) {
        return methodValidatorFor(method) != NOOP_METHOD;
    }

    public List<ValidationViolation> validateMethodArguments(Method method, Object[] args) {
        var validator = methodValidatorFor(method);
        if (validator == NOOP_METHOD) {
            return List.of();
        }
        var violations = new ArrayList<ValidationViolation>();
        validator.validate(args, (field, message) -> violations.add(new ValidationViolation(field, message)), this);
        return violations.isEmpty() ? List.of() : List.copyOf(violations);
    }

    public void validateMethodArgumentsOrThrow(Method method, Object[] args) {
        var violations = validateMethodArguments(method, args);
        if (!violations.isEmpty()) {
            throw new RissValidationException(violations);
        }
    }

    private List<TypeValidator> validatorsFor(Class<?> type) {
        return resolvedTypeValidators.computeIfAbsent(type, this::resolveTypeValidators);
    }

    private List<TypeValidator> resolveTypeValidators(Class<?> type) {
        var direct = directTypeValidators.get(type);
        var hierarchy = new LinkedHashSet<Class<?>>();
        collectHierarchy(type, hierarchy);
        if (hierarchy.size() == 1) {
            return direct == null ? List.of() : List.of(direct);
        }
        var found = new ArrayList<TypeValidator>();
        for (var candidate : hierarchy) {
            var v = directTypeValidators.get(candidate);
            if (v != null) {
                found.add(v);
            }
        }
        return found.isEmpty() ? List.of() : List.copyOf(found);
    }

    private static void collectHierarchy(Class<?> type, LinkedHashSet<Class<?>> seen) {
        if (type == null || type == Object.class || !seen.add(type)) {
            return;
        }
        collectHierarchy(type.getSuperclass(), seen);
        for (var iface : type.getInterfaces()) {
            collectHierarchy(iface, seen);
        }
    }

    private MethodParameterValidator methodValidatorFor(Method method) {
        return resolvedMethodValidators.computeIfAbsent(method, this::resolveMethodValidator);
    }

    private MethodParameterValidator resolveMethodValidator(Method method) {
        var entries = methodEntriesByController.get(method.getDeclaringClass());
        if (entries == null) {
            return NOOP_METHOD;
        }
        var name = method.getName();
        var paramTypes = method.getParameterTypes();
        for (var entry : entries) {
            if (entry.matches(method.getDeclaringClass(), name, paramTypes)) {
                return entry.validator();
            }
        }
        return NOOP_METHOD;
    }
}
