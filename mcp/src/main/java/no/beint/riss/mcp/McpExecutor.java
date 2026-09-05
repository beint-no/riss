package no.beint.riss.mcp;

import java.io.IOException;

/** Executes an API request through the application's normal validation and authorization boundary. */
@FunctionalInterface
public interface McpExecutor {
    McpResponse execute(McpRequest request) throws IOException, InterruptedException;
}
