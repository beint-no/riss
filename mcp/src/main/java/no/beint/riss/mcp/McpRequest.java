package no.beint.riss.mcp;

import java.util.Map;

/** A compiled API request. Authentication is supplied by the executor, never by tool arguments. */
public record McpRequest(String method, String path, Map<String, String> headers, byte[] body) {
    public McpRequest { headers = Map.copyOf(headers); body = body.clone(); }
    @Override public byte[] body() { return body.clone(); }
}
