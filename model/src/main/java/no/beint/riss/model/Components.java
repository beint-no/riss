package no.beint.riss.model;

import java.util.Map;

public record Components(
        Map<String, Schema> schemas,
        Map<String, SecurityScheme> securitySchemes
) {
    public Components {
        schemas = schemas == null ? Map.of() : Map.copyOf(schemas);
        securitySchemes = securitySchemes == null
                ? Map.of()
                : Map.copyOf(securitySchemes);
    }

    public static Components empty() {
        return new Components(Map.of(), Map.of());
    }
}
