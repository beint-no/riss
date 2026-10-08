package no.beint.riss.mcp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class SchemaCheck {
    private SchemaCheck() {}

    private static final class State {
        final boolean output;
        McpRuntime.SchemaDiagnostic diagnostic;
        State(boolean output) { this.output = output; }
        String property(String path, String key) { return path + (output ? "/" + pointer(key) : "." + key); }
        String index(String path, int index) { return path + (output ? "/" + index : "[" + index + "]"); }
        IllegalArgumentException fail(String message, String path, String keyword, String expected, Object value) {
            diagnostic = new McpRuntime.SchemaDiagnostic(path, keyword, expected,
                    keyword.equals("required") ? "missing" : typeOf(value));
            return new IllegalArgumentException(message);
        }
    }

    static McpRuntime.SchemaDiagnostic diagnostic(Object value, Map<String, Object> schema) {
        var state = new State(true);
        try { check(value, schema, schema, "", 0, state); return null; }
        catch (IllegalArgumentException failure) {
            return state.diagnostic != null ? state.diagnostic
                    : new McpRuntime.SchemaDiagnostic("", "schema", "compiled_contract", "unknown");
        }
    }

    private static String typeOf(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return matches(value, "integer") ? "integer" : "number";
        return "unknown";
    }

    private static String pointer(String key) { return key.replace("~", "~0").replace("/", "~1"); }

    static void validate(Object value, Map<String, Object> schema) { check(value, schema, schema, "arguments", 0, new State(false)); }

    private static void check(Object value, Object schemaValue, Map<String, Object> root, String path, int depth, State state) {
        if (depth > 96) throw state.fail(path + ": schema nesting limit exceeded", path, "depth", "maximum_depth", value);
        if (Boolean.TRUE.equals(schemaValue)) return;
        if (Boolean.FALSE.equals(schemaValue)) throw state.fail(path + ": value is not allowed", path, "false", "no_value", value);
        var schema = Json.object(schemaValue);
        if (schema.get("$ref") instanceof String ref) {
            if (!ref.startsWith("#/$defs/")) throw new IllegalArgumentException("Unsupported catalog schema reference");
            var key = ref.substring(8).replace("~1", "/").replace("~0", "~");
            check(value, Json.object(root.get("$defs")).get(key), root, path, depth + 1, state);
        }
        for (var keyword : List.of("allOf", "anyOf", "oneOf")) {
            if (!(schema.get(keyword) instanceof List<?> variants)) continue;
            int matches = 0;
            for (var variant : variants) {
                try { check(value, variant, root, path, depth + 1, state); matches++; }
                catch (IllegalArgumentException failure) { if (state.output && keyword.equals("allOf")) throw failure; }
            }
            if (keyword.equals("allOf") && matches != variants.size() || keyword.equals("anyOf") && matches == 0
                    || keyword.equals("oneOf") && matches != 1)
                throw state.fail(path + ": does not satisfy " + keyword, path, keyword, "schema_constraint", value);
        }
        if (schema.get("type") instanceof String type && !matches(value, type))
            throw state.fail(path + ": expected " + type, path, "type", type, value);
        if (schema.get("type") instanceof List<?> types && types.stream().noneMatch(type -> matches(value, Json.string(type))))
            throw state.fail(path + ": unexpected JSON type", path, "type", String.join("|", types.stream().map(Json::string).toList()), value);
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(candidate -> equivalent(candidate, value)))
            throw state.fail(path + ": value is outside the enum", path, "enum", "schema_constraint", value);
        if (schema.containsKey("const") && !equivalent(schema.get("const"), value))
            throw state.fail(path + ": unexpected constant", path, "const", "schema_constraint", value);
        if (value instanceof Map<?, ?>) {
            var object = Json.object(value);
            for (var required : Json.list(schema.getOrDefault("required", List.of())))
                if (!object.containsKey(required)) throw state.fail(path + ": missing " + required, state.property(path, Json.string(required)), "required", "present", null);
            var properties = Json.objectOrEmpty(schema.get("properties"));
            for (var entry : object.entrySet()) {
                if (properties.containsKey(entry.getKey())) check(entry.getValue(), properties.get(entry.getKey()), root, state.property(path, entry.getKey()), depth + 1, state);
                else if (schema.containsKey("additionalProperties")) check(entry.getValue(), schema.get("additionalProperties"), root, state.property(path, state.output ? "<additional-property>" : entry.getKey()), depth + 1, state);
            }
            size(object.size(), schema, "minProperties", "maxProperties", path, value, state);
        }
        if (value instanceof List<?> list) {
            size(list.size(), schema, "minItems", "maxItems", path, value, state);
            if (schema.containsKey("items")) for (int i = 0; i < list.size(); i++) check(list.get(i), schema.get("items"), root, state.index(path, i), depth + 1, state);
        }
        if (value instanceof String string) size(string.codePointCount(0, string.length()), schema, "minLength", "maxLength", path, value, state);
        if (value instanceof Number number) {
            var decimal = new BigDecimal(number.toString());
            bound(decimal, schema, "minimum", -1, false, path, value, state);
            bound(decimal, schema, "maximum", 1, false, path, value, state);
            bound(decimal, schema, "exclusiveMinimum", -1, true, path, value, state);
            bound(decimal, schema, "exclusiveMaximum", 1, true, path, value, state);
        }
    }

    private static void bound(BigDecimal value, Map<String, Object> schema, String key, int invalidDirection, boolean exclusive, String path, Object actual, State state) {
        if (!(schema.get(key) instanceof Number bound)) return;
        int direction = value.compareTo(new BigDecimal(bound.toString()));
        if (direction == invalidDirection || exclusive && direction == 0) throw state.fail(path + ": violates " + key, path, key, "schema_constraint", actual);
    }

    private static void size(int value, Map<String, Object> schema, String min, String max, String path, Object actual, State state) {
        bound(BigDecimal.valueOf(value), schema, min, -1, false, path, actual, state);
        bound(BigDecimal.valueOf(value), schema, max, 1, false, path, actual, state);
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
