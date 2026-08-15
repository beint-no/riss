package no.beint.riss.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record Components(
        Map<String, Schema> schemas,
        Map<String, SecurityScheme> securitySchemes
) {
    public Components {
        schemas = schemas == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(schemas));
        securitySchemes = securitySchemes == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(securitySchemes));
    }

    public static Components empty() {
        return new Components(Map.of(), Map.of());
    }
}
