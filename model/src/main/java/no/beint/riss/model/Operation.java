package no.beint.riss.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Operation(
        String operationId,
        String summary,
        String description,
        List<String> tags,
        List<Parameter> parameters,
        RequestBody requestBody,
        Map<String, Response> responses,
        List<SecurityRequirement> security,
        boolean deprecated
) {
    public Operation {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        summary = emptyToNull(summary);
        description = emptyToNull(description);
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        responses = responses == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(responses));
        security = security == null ? List.of() : List.copyOf(security);
        if (responses.isEmpty()) {
            throw new IllegalArgumentException("operation '" + operationId + "' needs at least one response");
        }
    }

    public Operation withParameters(List<Parameter> parameters) {
        return new Operation(
                operationId,
                summary,
                description,
                tags,
                parameters,
                requestBody,
                responses,
                security,
                deprecated
        );
    }

    public Operation withResponses(Map<String, Response> responses) {
        return new Operation(
                operationId,
                summary,
                description,
                tags,
                parameters,
                requestBody,
                responses,
                security,
                deprecated
        );
    }

    public static Builder builder(String operationId) {
        return new Builder(operationId);
    }

    public static final class Builder {
        private final String operationId;
        private String summary;
        private String description;
        private final List<String> tags = new ArrayList<>();
        private final List<Parameter> parameters = new ArrayList<>();
        private RequestBody requestBody;
        private final Map<String, Response> responses = new LinkedHashMap<>();
        private final List<SecurityRequirement> security = new ArrayList<>();
        private boolean deprecated;

        public Builder(String operationId) {
            this.operationId = operationId;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder tag(String tag) {
            if (tag != null && !tag.isBlank() && !tags.contains(tag)) {
                tags.add(tag);
            }
            return this;
        }

        public Builder parameter(Parameter parameter) {
            parameters.add(parameter);
            return this;
        }

        public Builder requestBody(RequestBody requestBody) {
            this.requestBody = requestBody;
            return this;
        }

        public Builder response(String code, Response response) {
            responses.put(code, response);
            return this;
        }

        public Builder security(SecurityRequirement requirement) {
            security.add(requirement);
            return this;
        }

        public Builder deprecated(boolean deprecated) {
            this.deprecated = deprecated;
            return this;
        }

        public Operation build() {
            return new Operation(
                    operationId,
                    summary,
                    description,
                    tags,
                    parameters,
                    requestBody,
                    responses,
                    security,
                    deprecated
            );
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
