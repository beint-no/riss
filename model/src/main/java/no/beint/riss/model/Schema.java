package no.beint.riss.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A constrained OpenAPI 3.1 / JSON Schema 2020-12 node.
 * Object types belong in {@code components.schemas} and are referenced with {@code $ref}.
 */
public final class Schema {
    private final String ref;
    private final List<String> types;
    private final String format;
    private final String description;
    private final String example;
    private final String defaultValue;
    private final List<String> enumValues;
    private final Map<String, Schema> properties;
    private final List<String> required;
    private final Schema items;
    private final Schema additionalProperties;
    private final Boolean additionalPropertiesAllowed;
    private final List<Schema> oneOf;
    private final List<Schema> anyOf;
    private final Integer minLength;
    private final Integer maxLength;
    private final BigDecimal minimum;
    private final BigDecimal maximum;
    private final BigDecimal multipleOf;
    private final String pattern;
    private final Integer minItems;
    private final Integer maxItems;

    private Schema(Builder builder) {
        this.ref = builder.ref;
        this.types = List.copyOf(builder.types);
        this.format = builder.format;
        this.description = builder.description;
        this.example = builder.example;
        this.defaultValue = builder.defaultValue;
        this.enumValues = List.copyOf(builder.enumValues);
        this.properties = Map.copyOf(builder.properties);
        this.required = List.copyOf(builder.required);
        this.items = builder.items;
        this.additionalProperties = builder.additionalProperties;
        this.additionalPropertiesAllowed = builder.additionalPropertiesAllowed;
        this.oneOf = List.copyOf(builder.oneOf);
        this.anyOf = List.copyOf(builder.anyOf);
        this.minLength = builder.minLength;
        this.maxLength = builder.maxLength;
        this.minimum = builder.minimum;
        this.maximum = builder.maximum;
        this.multipleOf = builder.multipleOf;
        this.pattern = builder.pattern;
        this.minItems = builder.minItems;
        this.maxItems = builder.maxItems;
    }

    public static Schema ref(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("$ref is required");
        }
        return builder().ref(ref).build();
    }

    public static Schema nullableRef(String ref) {
        return builder()
                .anyOf(List.of(ref(ref), builder().types(List.of("null")).build()))
                .build();
    }

    /** JSON Schema 2020-12 empty schema: any JSON value, including null. */
    public static Schema unconstrained() {
        return builder().description("Any JSON value").build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isRef() {
        return ref != null;
    }

    public String ref() {
        return ref;
    }

    public List<String> types() {
        return types;
    }

    public String format() {
        return format;
    }

    public String description() {
        return description;
    }

    public String example() {
        return example;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public List<String> enumValues() {
        return enumValues;
    }

    public Map<String, Schema> properties() {
        return properties;
    }

    public List<String> required() {
        return required;
    }

    public Schema items() {
        return items;
    }

    public Schema additionalProperties() {
        return additionalProperties;
    }

    public Boolean additionalPropertiesAllowed() {
        return additionalPropertiesAllowed;
    }

    public List<Schema> oneOf() {
        return oneOf;
    }

    public List<Schema> anyOf() {
        return anyOf;
    }

    public Integer minLength() {
        return minLength;
    }

    public Integer maxLength() {
        return maxLength;
    }

    public BigDecimal minimum() {
        return minimum;
    }

    public BigDecimal maximum() {
        return maximum;
    }

    public BigDecimal multipleOf() {
        return multipleOf;
    }

    public String pattern() {
        return pattern;
    }

    public Integer minItems() {
        return minItems;
    }

    public Integer maxItems() {
        return maxItems;
    }

    public Schema withDescription(String description) {
        if (Objects.equals(this.description, description)) {
            return this;
        }
        return toBuilder().description(description).build();
    }

    public Schema withExample(String example) {
        if (Objects.equals(this.example, example)) {
            return this;
        }
        return toBuilder().example(example).build();
    }

    public Schema nullable() {
        if (ref != null) {
            return nullableRef(ref);
        }
        if (types.isEmpty() || types.contains("null")) {
            return this;
        }
        var next = new ArrayList<>(types);
        next.add("null");
        return toBuilder().types(next).build();
    }

    public Builder toBuilder() {
        var builder = new Builder()
                .ref(ref)
                .types(types)
                .format(format)
                .description(description)
                .example(example)
                .defaultValue(defaultValue)
                .enumValues(enumValues)
                .items(items)
                .additionalProperties(additionalProperties)
                .oneOf(oneOf)
                .anyOf(anyOf)
                .minLength(minLength)
                .maxLength(maxLength)
                .minimum(minimum)
                .maximum(maximum)
                .multipleOf(multipleOf)
                .pattern(pattern)
                .minItems(minItems)
                .maxItems(maxItems);
        properties.forEach(builder::property);
        required.forEach(builder::require);
        if (additionalPropertiesAllowed != null) {
            builder.additionalPropertiesAllowed(additionalPropertiesAllowed);
        }
        return builder;
    }

    public static final class Builder {
        private String ref;
        private final List<String> types = new ArrayList<>();
        private String format;
        private String description;
        private String example;
        private String defaultValue;
        private final List<String> enumValues = new ArrayList<>();
        private final Map<String, Schema> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();
        private Schema items;
        private Schema additionalProperties;
        private Boolean additionalPropertiesAllowed;
        private final List<Schema> oneOf = new ArrayList<>();
        private final List<Schema> anyOf = new ArrayList<>();
        private Integer minLength;
        private Integer maxLength;
        private BigDecimal minimum;
        private BigDecimal maximum;
        private BigDecimal multipleOf;
        private String pattern;
        private Integer minItems;
        private Integer maxItems;

        public Builder ref(String ref) {
            this.ref = emptyToNull(ref);
            return this;
        }

        public Builder types(List<String> types) {
            this.types.clear();
            if (types != null) {
                this.types.addAll(types);
            }
            return this;
        }

        public Builder type(String type) {
            this.types.clear();
            if (type != null && !type.isBlank()) {
                this.types.add(type);
            }
            return this;
        }

        public Builder format(String format) {
            this.format = emptyToNull(format);
            return this;
        }

        public Builder description(String description) {
            this.description = emptyToNull(description);
            return this;
        }

        public Builder example(String example) {
            this.example = emptyToNull(example);
            return this;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = emptyToNull(defaultValue);
            return this;
        }

        public Builder enumValues(List<String> values) {
            this.enumValues.clear();
            if (values != null) {
                this.enumValues.addAll(values);
            }
            return this;
        }

        public Builder property(String name, Schema schema) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("property name is required");
            }
            this.properties.put(name, Objects.requireNonNull(schema, "schema"));
            return this;
        }

        public Builder require(String name) {
            if (name != null && !name.isBlank() && !required.contains(name)) {
                required.add(name);
            }
            return this;
        }

        public Builder items(Schema items) {
            this.items = items;
            return this;
        }

        public Builder additionalProperties(Schema additionalProperties) {
            this.additionalProperties = additionalProperties;
            this.additionalPropertiesAllowed = additionalProperties == null ? this.additionalPropertiesAllowed : null;
            return this;
        }

        public Builder additionalPropertiesAllowed(boolean allowed) {
            this.additionalPropertiesAllowed = allowed;
            if (!allowed) {
                this.additionalProperties = null;
            }
            return this;
        }

        public Builder oneOf(List<Schema> oneOf) {
            this.oneOf.clear();
            if (oneOf != null) {
                this.oneOf.addAll(oneOf);
            }
            return this;
        }

        public Builder anyOf(List<Schema> anyOf) {
            this.anyOf.clear();
            if (anyOf != null) {
                this.anyOf.addAll(anyOf);
            }
            return this;
        }

        public Builder minLength(Integer minLength) {
            this.minLength = minLength;
            return this;
        }

        public Builder maxLength(Integer maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        public Builder minimum(BigDecimal minimum) {
            this.minimum = minimum;
            return this;
        }

        public Builder maximum(BigDecimal maximum) {
            this.maximum = maximum;
            return this;
        }

        public Builder multipleOf(BigDecimal multipleOf) {
            this.multipleOf = multipleOf;
            return this;
        }

        public Builder pattern(String pattern) {
            this.pattern = emptyToNull(pattern);
            return this;
        }

        public Builder minItems(Integer minItems) {
            this.minItems = minItems;
            return this;
        }

        public Builder maxItems(Integer maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public Schema build() {
            return new Schema(this);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
