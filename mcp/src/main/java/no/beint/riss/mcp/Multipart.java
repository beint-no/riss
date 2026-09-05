package no.beint.riss.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Multipart {
    record Body(String contentType, byte[] bytes) {}

    static Body encode(Map<String, Object> values, Map<String, Object> parts, int maxBytes) {
        String boundary = "riss-" + UUID.randomUUID();
        var output = new ByteArrayOutputStream();
        for (var entry : values.entrySet()) {
            var value = entry.getValue();
            if (value == null) continue;
            var part = Json.object(parts.get(entry.getKey()));
            var items = Boolean.TRUE.equals(part.get("array")) ? Json.list(value) : List.of(value);
            for (var item : items) {
                if (item == null) throw new IllegalArgumentException("Multipart array items cannot be null");
                var name = quoted(entry.getKey());
                String type = Json.string(part.get("contentType"));
                String disposition = "Content-Disposition: form-data; name=\"" + name + "\"";
                byte[] bytes;
                if (Boolean.TRUE.equals(part.get("file"))) {
                    var file = Json.object(item);
                    disposition += "; filename=\"" + quoted(Json.string(file.get("filename"))) + "\"";
                    if (file.containsKey("contentType")) type = Json.string(file.get("contentType"));
                    try { bytes = Base64.getDecoder().decode(Json.string(file.get("base64"))); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid base64 file data for " + entry.getKey()); }
                } else if (type.startsWith("application/json") || type.split(";", 2)[0].endsWith("+json")) bytes = Json.bytes(item);
                else if (item instanceof String || item instanceof Number || item instanceof Boolean) bytes = item.toString().getBytes(StandardCharsets.UTF_8);
                else throw new IllegalArgumentException("Multipart object parts require application/json");
                if (!type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:; charset=utf-8)?"))
                    throw new IllegalArgumentException("Invalid multipart content type");
                output.writeBytes(("--" + boundary + "\r\n" + disposition + "\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.writeBytes(bytes);
                output.writeBytes(new byte[]{'\r', '\n'});
                if (output.size() > maxBytes) throw new IllegalArgumentException("Encoded multipart body exceeds byte limit");
            }
        }
        output.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return new Body("multipart/form-data; boundary=" + boundary, output.toByteArray());
    }

    private static String quoted(String value) {
        if (value.isBlank() || value.chars().anyMatch(c -> c < 0x20 || c == 127)) throw new IllegalArgumentException("Invalid multipart name or filename");
        return value.replace("%", "%25").replace("\"", "%22").replace("\\", "%5C");
    }
}
