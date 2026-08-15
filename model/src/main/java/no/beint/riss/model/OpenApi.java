package no.beint.riss.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record OpenApi(
        Info info,
        List<Server> servers,
        Map<String, PathItem> paths,
        Components components,
        List<Tag> tags,
        List<SecurityRequirement> security
) {
    public static final String VERSION = "3.1.0";
    public static final String JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    public OpenApi {
        Objects.requireNonNull(info, "info");
        servers = servers == null ? List.of() : List.copyOf(servers);
        paths = paths == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(paths));
        components = components == null ? Components.empty() : components;
        tags = tags == null ? List.of() : List.copyOf(tags);
        security = security == null ? List.of() : List.copyOf(security);
    }

    public static Builder builder(Info info) {
        return new Builder(info);
    }

    public static final class Builder {
        private final Info info;
        private final List<Server> servers = new ArrayList<>();
        private final Map<String, PathItem> paths = new LinkedHashMap<>();
        private final Map<String, Schema> schemas = new LinkedHashMap<>();
        private final Map<String, SecurityScheme> securitySchemes = new LinkedHashMap<>();
        private final List<Tag> tags = new ArrayList<>();
        private final List<SecurityRequirement> security = new ArrayList<>();

        public Builder(Info info) {
            this.info = info;
        }

        public Builder server(Server server) {
            servers.add(server);
            return this;
        }

        public Builder path(String path, PathItem item) {
            paths.put(path, item);
            return this;
        }

        public Builder schema(String name, Schema schema) {
            schemas.put(name, schema);
            return this;
        }

        public Builder securityScheme(String name, SecurityScheme scheme) {
            securitySchemes.put(name, scheme);
            return this;
        }

        public Builder tag(Tag tag) {
            if (tags.stream().noneMatch(existing -> existing.name().equals(tag.name()))) {
                tags.add(tag);
            }
            return this;
        }

        public Builder security(SecurityRequirement requirement) {
            security.add(requirement);
            return this;
        }

        public OpenApi build() {
            return new OpenApi(
                    info,
                    servers,
                    paths,
                    new Components(schemas, securitySchemes),
                    tags,
                    security
            );
        }
    }
}
