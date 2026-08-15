package no.beint.riss.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record Response(String description, Map<String, MediaType> content) {
    public Response {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("response description is required");
        }
        content = content == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(content));
    }

    public static Response of(String description) {
        return new Response(description, Map.of());
    }

    public static Response json(String description, Schema schema) {
        return new Response(description, Map.of("application/json", new MediaType(schema)));
    }

    public static Response problem(String description, String schemaRef) {
        return new Response(
                description,
                Map.of("application/problem+json", new MediaType(Schema.ref(schemaRef)))
        );
    }
}
