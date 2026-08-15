package no.beint.riss.model;

public record Info(String title, String version, String description, Contact contact) {
    public Info {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("info.title is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("info.version is required");
        }
        description = emptyToNull(description);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
