package no.beint.riss.model;

import java.util.Objects;

public record MediaType(Schema schema, String example) {
    public MediaType {
        Objects.requireNonNull(schema, "schema");
        example = example == null || example.isBlank() ? null : example;
    }

    public MediaType(Schema schema) {
        this(schema, null);
    }
}
