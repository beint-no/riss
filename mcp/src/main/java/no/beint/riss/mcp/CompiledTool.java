package no.beint.riss.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class CompiledTool {
    final Map<String, Object> definition;
    final String name;
    private final String method;
    private final String path;
    private final String bodyType;
    private final List<Object> parameters;
    private final Map<String, Object> bodyParts;

    CompiledTool(Object data) {
        var compiled = Json.object(data);
        definition = Json.object(compiled.get("tool"));
        name = Json.string(definition.get("name"));
        method = Json.string(compiled.get("method"));
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method))
            throw new IllegalArgumentException("Invalid catalog method");
        path = Json.string(compiled.get("path"));
        McpCompiler.validatePath(path);
        bodyType = Json.string(compiled.get("bodyType"));
        parameters = Json.list(compiled.get("parameters"));
        bodyParts = Json.objectOrEmpty(compiled.get("bodyParts"));
    }

    McpRequest request(Map<String, Object> arguments, int maxRequestBytes) {
        SchemaCheck.validate(arguments, Json.object(definition.get("inputSchema")));
        var targetPath = path;
        var query = new ArrayList<String>();
        var headers = new LinkedHashMap<String, String>();
        for (var item : parameters) {
            var parameter = Json.object(item);
            var group = Json.string(parameter.get("group"));
            var name = Json.string(parameter.get("name"));
            var values = Json.objectOrEmpty(arguments.get(group));
            if (!values.containsKey(name)) continue;
            var value = values.get(name);
            if (value == null) throw new IllegalArgumentException(group + "." + name + ": null has no HTTP parameter representation");
            var style = Json.string(parameter.get("style"));
            boolean explode = Boolean.TRUE.equals(parameter.get("explode"));
            switch (group) {
                case "path" -> {
                    String encoded = simple(value, explode, true);
                    if (encoded.isEmpty() || encoded.equals(".") || encoded.equals(".."))
                        throw new IllegalArgumentException("Empty or relative path parameter");
                    targetPath = targetPath.replace("{" + name + "}", encoded);
                }
                case "query" -> query(query, name, value, style, explode);
                case "headers" -> {
                    if (!McpCompiler.allowedHeader(name)) throw new IllegalArgumentException("Reserved HTTP header");
                    String text = simple(value, explode, false);
                    if (text.chars().anyMatch(c -> c < 0x20 || c >= 0x7f)) throw new IllegalArgumentException("Header values must be printable ASCII");
                    headers.put(name, text);
                }
                default -> throw new IllegalArgumentException("Invalid catalog parameter group");
            }
        }
        if (targetPath.contains("{") || targetPath.contains("}")) throw new IllegalArgumentException("Unresolved path parameter");
        if (!query.isEmpty()) targetPath += "?" + String.join("&", query);
        byte[] body = new byte[0];
        if (arguments.containsKey("body")) {
            if (bodyType.equals("multipart/form-data")) {
                var multipart = Multipart.encode(Json.object(arguments.get("body")), bodyParts, maxRequestBytes * 2);
                body = multipart.bytes();
                headers.put("Content-Type", multipart.contentType());
            } else {
                body = Json.bytes(arguments.get("body"));
                headers.put("Content-Type", bodyType);
            }
        }
        return new McpRequest(method, targetPath, headers, body);
    }

    private static void query(List<String> query, String name, Object value, String style, boolean explode) {
        String key = encode(name);
        if (value instanceof List<?> list) {
            if (style.equals("form") && explode) list.forEach(item -> query.add(key + "=" + encode(scalar(item))));
            else {
                String delimiter = switch (style) { case "spaceDelimited" -> "%20"; case "pipeDelimited" -> "%7C"; default -> ","; };
                query.add(key + "=" + list.stream().map(item -> encode(scalar(item))).collect(Collectors.joining(delimiter)));
            }
        } else if (value instanceof Map<?, ?>) {
            if (style.equals("deepObject")) Json.object(value).forEach((property, item) -> query.add(encode(name + "[" + property + "]") + "=" + encode(scalar(item))));
            else if (explode) Json.object(value).forEach((property, item) -> query.add(encode(property) + "=" + encode(scalar(item))));
            else query.add(key + "=" + simple(value, false, true));
        } else query.add(key + "=" + encode(scalar(value)));
    }

    private static String simple(Object value, boolean explode, boolean encode) {
        if (value instanceof List<?> list) return list.stream().map(item -> atom(item, encode)).collect(Collectors.joining(","));
        if (value instanceof Map<?, ?>) return Json.object(value).entrySet().stream()
                .map(entry -> atom(entry.getKey(), encode) + (explode ? "=" : ",") + atom(entry.getValue(), encode)).collect(Collectors.joining(","));
        return atom(value, encode);
    }

    private static String atom(Object value, boolean encode) { var scalar = scalar(value); return encode ? encode(scalar) : scalar; }

    private static String scalar(Object value) {
        if (value instanceof String string) return string;
        if (value instanceof java.math.BigDecimal number) return number.toPlainString();
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("HTTP parameter must be a scalar or a flat collection of scalars");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"); }
}
