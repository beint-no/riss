package no.beint.riss.model;

public record Tag(String name, String description) {
    public Tag {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tag.name is required");
        }
        description = description == null || description.isBlank() ? null : description;
    }
}
