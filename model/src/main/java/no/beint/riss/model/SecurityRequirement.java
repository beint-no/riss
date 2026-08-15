package no.beint.riss.model;

import java.util.List;
import java.util.Objects;

public record SecurityRequirement(String name, List<String> scopes) {
    public SecurityRequirement {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("security requirement name is required");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public static SecurityRequirement of(String name) {
        return new SecurityRequirement(name, List.of());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecurityRequirement that
                && name.equals(that.name)
                && scopes.equals(that.scopes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, scopes);
    }
}
