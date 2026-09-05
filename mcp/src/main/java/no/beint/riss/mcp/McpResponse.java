package no.beint.riss.mcp;

/** An API response before conversion into an MCP tool result. */
public record McpResponse(int status, String contentType, byte[] body) {
    public McpResponse {
        if (status < 100 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
        contentType = contentType == null ? "application/octet-stream" : contentType;
        body = body.clone();
    }
    @Override public byte[] body() { return body.clone(); }
}
