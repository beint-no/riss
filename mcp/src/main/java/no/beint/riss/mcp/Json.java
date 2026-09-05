package no.beint.riss.mcp;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private static final int MAX_DEPTH = 96;
    private final String text;
    private int position;

    private Json(String text) { this.text = text; }

    static Object parse(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return parse(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8");
        }
    }

    static Object parse(String text) {
        var parser = new Json(text);
        var result = parser.value(0);
        parser.space();
        if (parser.position != text.length()) throw parser.invalid();
        return result;
    }

    static byte[] bytes(Object value) { return write(value).getBytes(StandardCharsets.UTF_8); }

    static String write(Object value) {
        var out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) map;
    }

    static Map<String, Object> objectOrEmpty(Object value) { return value == null ? Map.of() : object(value); }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a JSON array");
        return (List<Object>) list;
    }

    static String string(Object value) {
        if (!(value instanceof String string)) throw new IllegalArgumentException("Expected a JSON string");
        return string;
    }

    static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting limit exceeded");
        space();
        if (position == text.length()) throw invalid();
        return switch (text.charAt(position)) {
            case '{' -> objectValue(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> stringValue();
            case 't' -> literal("true", true);
            case 'f' -> literal("false", false);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object objectValue(int depth) {
        position++;
        var result = new LinkedHashMap<String, Object>();
        space();
        if (take('}')) return result;
        do {
            space();
            if (position == text.length() || text.charAt(position) != '"') throw invalid();
            var key = stringValue();
            space();
            if (!take(':') || result.containsKey(key)) throw invalid();
            result.put(key, value(depth));
            space();
            if (take('}')) return result;
        } while (take(','));
        throw invalid();
    }

    private Object array(int depth) {
        position++;
        var result = new ArrayList<>();
        space();
        if (take(']')) return result;
        do {
            result.add(value(depth));
            space();
            if (take(']')) return result;
        } while (take(','));
        throw invalid();
    }

    private String stringValue() {
        position++;
        var out = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw invalid();
            if (c == '\\') {
                if (position == text.length()) throw invalid();
                c = switch (text.charAt(position++)) {
                    case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                    case 'r' -> '\r'; case 't' -> '\t'; case 'u' -> hex();
                    default -> throw invalid();
                };
            }
            if (Character.isHighSurrogate(c)) {
                char low;
                if (position + 1 < text.length() && text.charAt(position) == '\\' && text.charAt(position + 1) == 'u') {
                    position += 2;
                    low = hex();
                } else if (position < text.length()) low = text.charAt(position++);
                else throw invalid();
                if (!Character.isLowSurrogate(low)) throw invalid();
                out.append(c).append(low);
            } else {
                if (Character.isLowSurrogate(c)) throw invalid();
                out.append(c);
            }
        }
        throw invalid();
    }

    private char hex() {
        if (position + 4 > text.length()) throw invalid();
        int result = 0;
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(position++);
            int digit = c <= 127 ? Character.digit(c, 16) : -1;
            if (digit < 0) throw invalid();
            result = result * 16 + digit;
        }
        return (char) result;
    }

    private Object literal(String token, Object value) {
        if (!text.startsWith(token, position)) throw invalid();
        position += token.length();
        return value;
    }

    private Number number() {
        int start = position;
        take('-');
        if (!take('0')) digits();
        if (take('.')) digits();
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            digits();
        }
        if (position - start > 256) throw new IllegalArgumentException("JSON number limit exceeded");
        try {
            var number = new BigDecimal(text.substring(start, position));
            if (Math.abs((long) number.scale()) > 1024) throw new IllegalArgumentException("JSON exponent limit exceeded");
            return number;
        }
        catch (NumberFormatException e) { throw invalid(); }
    }

    private void digits() {
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
        if (start == position) throw invalid();
    }

    private boolean take(char c) {
        if (position < text.length() && text.charAt(position) == c) { position++; return true; }
        return false;
    }

    private void space() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
            position++;
        }
    }

    private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid JSON at offset " + position); }

    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting limit exceeded");
        switch (value) {
            case null -> out.append("null");
            case String text -> quote(out, text);
            case Boolean bool -> out.append(bool);
            case Number number -> {
                if (number instanceof Double d && !Double.isFinite(d) || number instanceof Float f && !Float.isFinite(f))
                    throw new IllegalArgumentException("Non-finite JSON number");
                out.append(number);
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean comma = false;
                for (var entry : map.entrySet()) {
                    if (comma) out.append(',');
                    comma = true;
                    quote(out, string(entry.getKey()));
                    out.append(':');
                    append(out, entry.getValue(), depth + 1);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    append(out, list.get(i), depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass().getName());
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) out.append("0123456789abcdef".charAt(c >> shift & 15));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
