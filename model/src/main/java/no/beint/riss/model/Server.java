package no.beint.riss.model;

public record Server(String url, String description) {
    public Server {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("server.url is required");
        }
        description = description == null || description.isBlank() ? null : description;
    }
}
