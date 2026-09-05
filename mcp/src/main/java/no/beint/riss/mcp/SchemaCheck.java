package no.beint.riss.mcp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class SchemaCheck {
    private SchemaCheck() {}

    static void validate(Object value, Map<String, Object> schema) { check(value, schema, schema, "arguments", 0); }

    private static void check(Object value, Object schemaValue, Map<String, Object> root, String path, int depth) {
        if (depth > 96) throw new IllegalArgumentException(path + ": schema nesting limit exceeded");
        if (Boolean.TRUE.equals(schemaValue)) return;
        if (Boolean.FALSE.equals(schemaValue)) throw new IllegalArgumentException(path + ": value is not allowed");
        var schema = Json.object(schemaValue);
        if (schema.get("$ref") instanceof String ref) {
            if (!ref.startsWith("#/$defs/")) throw new IllegalArgumentException("Unsupported catalog schema reference");
            var key = ref.substring(8).replace("~1", "/").replace("~0", "~");
            check(value, Json.object(root.get("$defs")).get(key), root, path, depth + 1);
        }
        for (var keyword : List.of("allOf", "anyOf", "oneOf")) {
            if (!(schema.get(keyword) instanceof List<?> variants)) continue;
            int matches = 0;
            for (var variant : variants) {
                try { check(value, variant, root, path, depth + 1); matches++; }
                catch (IllegalArgumentException ignored) { }
            }
            if (keyword.equals("allOf") && matches != variants.size() || keyword.equals("anyOf") && matches == 0
                    || keyword.equals("oneOf") && matches != 1)
                throw new IllegalArgumentException(path + ": does not satisfy " + keyword);
        }
        if (schema.get("type") instanceof String type && !matches(value, type))
            throw new IllegalArgumentException(path + ": expected " + type);
        if (schema.get("type") instanceof List<?> types && types.stream().noneMatch(type -> matches(value, Json.string(type))))
            throw new IllegalArgumentException(path + ": unexpected JSON type");
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(candidate -> equivalent(candidate, value)))
            throw new IllegalArgumentException(path + ": value is outside the enum");
        if (schema.containsKey("const") && !equivalent(schema.get("const"), value))
            throw new IllegalArgumentException(path + ": unexpected constant");
        if (value instanceof Map<?, ?>) {
            var object = Json.object(value);
            for (var required : Json.list(schema.getOrDefault("required", List.of())))
                if (!object.containsKey(required)) throw new IllegalArgumentException(path + ": missing " + required);
            var properties = Json.objectOrEmpty(schema.get("properties"));
            for (var entry : object.entrySet()) {
                if (properties.containsKey(entry.getKey())) check(entry.getValue(), properties.get(entry.getKey()), root, path + "." + entry.getKey(), depth + 1);
                else if (schema.containsKey("additionalProperties")) check(entry.getValue(), schema.get("additionalProperties"), root, path + "." + entry.getKey(), depth + 1);
            }
            size(object.size(), schema, "minProperties", "maxProperties", path);
        }
        if (value instanceof List<?> list) {
            size(list.size(), schema, "minItems", "maxItems", path);
            if (schema.containsKey("items")) for (int i = 0; i < list.size(); i++) check(list.get(i), schema.get("items"), root, path + "[" + i + "]", depth + 1);
        }
        if (value instanceof String string) size(string.codePointCount(0, string.length()), schema, "minLength", "maxLength", path);
        if (value instanceof Number number) {
            var decimal = new BigDecimal(number.toString());
            bound(decimal, schema, "minimum", -1, false, path);
            bound(decimal, schema, "maximum", 1, false, path);
            bound(decimal, schema, "exclusiveMinimum", -1, true, path);
            bound(decimal, schema, "exclusiveMaximum", 1, true, path);
        }
    }

    private static void bound(BigDecimal value, Map<String, Object> schema, String key, int invalidDirection, boolean exclusive, String path) {
        if (!(schema.get(key) instanceof Number bound)) return;
        int direction = value.compareTo(new BigDecimal(bound.toString()));
        if (direction == invalidDirection || exclusive && direction == 0) throw new IllegalArgumentException(path + ": violates " + key);
    }

    private static void size(int value, Map<String, Object> schema, String min, String max, String path) {
        bound(BigDecimal.valueOf(value), schema, min, -1, false, path);
        bound(BigDecimal.valueOf(value), schema, max, 1, false, path);
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        return java.util.Objects.equals(left, right);
    }

    private static boolean matches(Object value, String type) {
        return switch (type) {
            case "null" -> value == null;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalArgumentException("Unsupported schema type: " + type);
        };
    }
}
