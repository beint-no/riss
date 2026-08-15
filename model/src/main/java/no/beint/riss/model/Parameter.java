package no.beint.riss.model;

public record Parameter(
        String name,
        String locatedIn,
        String description,
        boolean required,
        Schema schema,
        String style,
        Boolean explode,
        String example
) {
    public Parameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name is required");
        }
        if (locatedIn == null || locatedIn.isBlank()) {
            throw new IllegalArgumentException("parameter.in is required");
        }
        if (schema == null) {
            throw new IllegalArgumentException("parameter.schema is required");
        }
        description = emptyToNull(description);
        style = emptyToNull(style);
        example = emptyToNull(example);
        if ("path".equals(locatedIn)) {
            required = true;
        }
    }

    public static Parameter path(String name, Schema schema) {
        return new Parameter(name, "path", null, true, schema, null, null, null);
    }

    public static Parameter query(String name, Schema schema, boolean required) {
        return new Parameter(name, "query", null, required, schema, null, null, null);
    }

    public static Parameter header(String name, Schema schema, boolean required) {
        return new Parameter(name, "header", null, required, schema, null, null, null);
    }

    public Parameter withDescription(String description) {
        return new Parameter(name, locatedIn, description, required, schema, style, explode, example);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
