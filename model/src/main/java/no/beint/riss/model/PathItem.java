package no.beint.riss.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PathItem {
    private final Map<String, Operation> operations;

    public PathItem(Map<String, Operation> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("path item needs at least one operation");
        }
        var copy = new LinkedHashMap<String, Operation>();
        operations.forEach((method, operation) -> copy.put(method.toLowerCase(Locale.ROOT), operation));
        this.operations = Map.copyOf(copy);
    }

    public Map<String, Operation> operations() {
        return operations;
    }

    public Optional<Operation> operation(String method) {
        return Optional.ofNullable(operations.get(method.toLowerCase(Locale.ROOT)));
    }

    public PathItem with(String method, Operation operation) {
        var copy = new LinkedHashMap<>(operations);
        copy.put(method.toLowerCase(Locale.ROOT), operation);
        return new PathItem(copy);
    }
}
