package no.beint.riss.model;

public record SecurityScheme(
        String type,
        String description,
        String scheme,
        String bearerFormat,
        String name,
        String in
) {
    public SecurityScheme {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("security scheme type is required");
        }
        description = emptyToNull(description);
        scheme = emptyToNull(scheme);
        bearerFormat = emptyToNull(bearerFormat);
        name = emptyToNull(name);
        in = emptyToNull(in);
    }

    public static SecurityScheme httpBearer(String bearerFormat) {
        return new SecurityScheme("http", null, "bearer", bearerFormat, null, null);
    }

    public static SecurityScheme apiKeyHeader(String headerName) {
        return new SecurityScheme("apiKey", null, null, null, headerName, "header");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
