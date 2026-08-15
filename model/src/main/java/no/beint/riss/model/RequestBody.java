package no.beint.riss.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RequestBody(String description, boolean required, Map<String, MediaType> content) {
    public RequestBody {
        description = description == null || description.isBlank() ? null : description;
        content = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(content, "content")));
        if (content.isEmpty()) {
            throw new IllegalArgumentException("request body content is required");
        }
    }
}
