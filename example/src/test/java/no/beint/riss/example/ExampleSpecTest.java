package no.beint.riss.example;

import no.beint.riss.example.generated.ExampleSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleSpecTest {
    @Test
    void collectionAndElementSizesAreIndependent() {
        var properties = new JsonMapper().readTree(new ExampleSpec().json())
                .at("/components/schemas/FeatureReq/properties");
        var tags = properties.get("tags");
        assertEquals(100, tags.get("maxItems").asInt());
        assertEquals(255, tags.at("/items/maxLength").asInt());
        var labels = properties.get("labels");
        assertEquals(20, labels.get("maxItems").asInt());
        assertFalse(labels.get("items").has("maxLength"));
    }

    @Test
    void compiledSpecIsOpenApi31Json() {
        var json = new String(new ExampleSpec().json(), StandardCharsets.UTF_8);
        var compact = json.replace(" ", "");
        assertTrue(compact.contains("\"openapi\":\"3.1.0\""));
        assertTrue(compact.contains("\"jsonSchemaDialect\":\"https://json-schema.org/draft/2020-12/schema\""));
        assertTrue(compact.contains("\"/api/features\""));
        assertTrue(compact.contains("\"deprecated\":true"));
        assertTrue(compact.contains("\"/api/features/pages\""));
        assertTrue(compact.contains("\"/api/features/probes\""));
        assertTrue(compact.contains("\"CurrencyCode\""));
        assertTrue(compact.contains("\"FeatureKindCode\""));
        assertTrue(compact.contains("\"ProblemDetail\""));
        assertTrue(compact.contains("\"FieldError\""));
        assertTrue(compact.contains("\"fieldErrors\""));
        assertTrue(compact.contains("\"#/components/schemas/FieldError\""));
        assertFalse(json.contains("\n"));
        assertTrue(compact.contains("\"Page_FeatureRes\""));
        assertTrue(compact.contains("\"ProbeResult\""));
        assertTrue(compact.contains("\"oneOf\""));
        assertTrue(compact.contains("\"#/components/schemas/FeatureRes\""));
        assertTrue(compact.contains("\"X-Tenant-Id\""));
        assertTrue(compact.contains("\"bearer-token\""));
        assertTrue(compact.contains("multipart/form-data"));
        assertTrue(compact.contains("\"additionalProperties\":true"));
        assertTrue(json.contains("Any JSON value"));
        assertFalse(compact.contains("swagger"));
        assertFalse(compact.contains("yaml"));
    }
}
