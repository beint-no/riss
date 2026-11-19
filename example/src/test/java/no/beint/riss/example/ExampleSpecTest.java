package no.beint.riss.example;

import no.beint.riss.example.generated.ExampleSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleSpecTest {
    private static JsonNode spec() {
        return new JsonMapper().readTree(new ExampleSpec().json());
    }

    @Test
    void collectionAndElementSizesAreIndependent() {
        var properties = spec().at("/components/schemas/FeatureReq/properties");
        var tags = properties.get("tags");
        assertEquals(100, tags.get("maxItems").asInt());
        assertEquals(255, tags.at("/items/maxLength").asInt());
        var labels = properties.get("labels");
        assertEquals(20, labels.get("maxItems").asInt());
        assertFalse(labels.get("items").has("maxLength"));
    }

    @Test
    void refPropertiesPreserveSchemaMetadataIncludingNullables() {
        var properties = spec().at("/components/schemas/ContractReq/properties");

        var primary = properties.get("primaryAddress");
        assertEquals("#/components/schemas/AddressDto", primary.get("$ref").asString());
        assertEquals("Primary address", primary.get("description").asString());
        assertEquals("{}", primary.get("default").asString());
        assertEquals("{\"street\":\"Main\"}", primary.get("example").asString());

        var billing = properties.get("billingAddress");
        assertEquals(2, billing.get("anyOf").size());
        assertEquals("#/components/schemas/AddressDto", billing.at("/anyOf/0/$ref").asString());
        assertEquals("null", billing.at("/anyOf/1/type").asString());
        assertEquals("Optional billing address", billing.get("description").asString());
        assertEquals("{\"street\":\"Billing\"}", billing.get("example").asString());

        var probe = properties.get("probeResult");
        assertEquals("#/components/schemas/ProbeResult", probe.get("$ref").asString());
        assertEquals("Required probe result", probe.get("description").asString());

        var optionalProbe = properties.get("optionalProbeResult");
        assertEquals("#/components/schemas/ProbeResult", optionalProbe.at("/anyOf/0/$ref").asString());
        assertEquals("null", optionalProbe.at("/anyOf/1/type").asString());
        assertEquals("Optional probe result", optionalProbe.get("description").asString());
    }

    @Test
    void arrayMetadataDoesNotLeakOntoItems() {
        var properties = spec().at("/components/schemas/ContractReq/properties");

        var tags = properties.get("describedTags");
        assertEquals("Feature tags", tags.get("description").asString());
        assertEquals("[\"alpha\"]", tags.get("example").asString());
        assertFalse(tags.get("items").has("description"));
        assertFalse(tags.get("items").has("example"));

        var currencies = properties.get("currencies");
        assertEquals("Supported currencies", currencies.get("description").asString());
        assertEquals("#/components/schemas/CurrencyCode", currencies.at("/items/$ref").asString());
        assertFalse(currencies.get("items").has("description"));

        var notes = properties.get("notes");
        assertEquals("All notes", notes.get("description").asString());
        assertEquals("One note", notes.at("/items/description").asString());
    }

    @Test
    void numericAndBeanValidationConstraintsAreAccurate() {
        var properties = spec().at("/components/schemas/ContractReq/properties");

        var amount = properties.get("amount");
        assertEquals(0, BigDecimal.ZERO.compareTo(amount.get("exclusiveMinimum").decimalValue()));
        assertFalse(amount.has("minimum"));

        var count = properties.get("count");
        assertEquals(1, count.get("minimum").asInt());
        assertFalse(count.has("exclusiveMinimum"));

        var exclusiveRate = properties.get("exclusiveRate");
        assertEquals(0, new BigDecimal("0.5").compareTo(exclusiveRate.get("exclusiveMinimum").decimalValue()));
        assertEquals(0, new BigDecimal("99.5").compareTo(exclusiveRate.get("exclusiveMaximum").decimalValue()));
        assertFalse(exclusiveRate.has("minimum"));
        assertFalse(exclusiveRate.has("maximum"));

        var inclusiveRate = properties.get("inclusiveRate");
        assertEquals(0, new BigDecimal("1.0").compareTo(inclusiveRate.get("minimum").decimalValue()));
        assertEquals(0, new BigDecimal("10.0").compareTo(inclusiveRate.get("maximum").decimalValue()));
        assertFalse(inclusiveRate.has("exclusiveMinimum"));
        assertFalse(inclusiveRate.has("exclusiveMaximum"));

        var bigId = properties.get("bigId");
        assertEquals(3_000_000_000L, bigId.get("minimum").asLong());
        assertEquals(9_000_000_000L, bigId.get("maximum").asLong());

        assertEquals(1, properties.at("/blankChecked/minLength").asInt());
        assertEquals(3, properties.at("/sizedBlankChecked/minLength").asInt());
        assertEquals(50, properties.at("/sizedBlankChecked/maxLength").asInt());

        assertEquals(1, properties.at("/nonEmptyList/minItems").asInt());
        assertEquals(1, properties.at("/nonEmptyText/minLength").asInt());
        assertEquals(1, properties.at("/nonEmptyMap/minProperties").asInt());

        var scores = properties.get("scores");
        assertEquals(1, scores.get("minProperties").asInt());
        assertEquals(10, scores.get("maxProperties").asInt());
        assertEquals(1, scores.at("/additionalProperties/minimum").asInt());
    }

    @Test
    void classLevelApiResponsesAndResponseStatusWork() {
        var paths = spec().get("paths");

        var createResponses = paths.get("/api/contracts").at("/post/responses");
        assertTrue(createResponses.has("201"));
        assertFalse(createResponses.has("200"));
        assertEquals("#/components/schemas/ContractReq", createResponses.at("/201/content/application~1json/schema/$ref").asString());
        assertEquals("Controller forbidden", createResponses.at("/403/description").asString());
        assertEquals("Controller not found", createResponses.at("/404/description").asString());

        var deleteResponses = paths.get("/api/contracts/{id}").at("/delete/responses");
        assertTrue(deleteResponses.has("204"));
        assertFalse(deleteResponses.has("200"));
        assertFalse(deleteResponses.get("204").has("content"));
        assertEquals("Controller forbidden", deleteResponses.at("/403/description").asString());
        assertEquals("Contract not found", deleteResponses.at("/404/description").asString());

        var jobResponses = paths.get("/api/jobs").at("/post/responses");
        assertTrue(jobResponses.has("202"));
        assertFalse(jobResponses.has("200"));
        assertEquals("Accepted", jobResponses.at("/202/description").asString());
    }

    @Test
    void rissPropertyRefHonorsNullableDescriptionAndExample() {
        var properties = spec().at("/components/schemas/ProblemDetail/properties");

        var fieldErrors = properties.get("fieldErrors");
        assertEquals("array", fieldErrors.get("type").asString());
        assertEquals("#/components/schemas/FieldError", fieldErrors.at("/items/$ref").asString());
        assertEquals("Validation errors", fieldErrors.get("description").asString());
        assertEquals("[]", fieldErrors.get("example").asString());

        var primaryError = properties.get("primaryError");
        assertEquals("#/components/schemas/FieldError", primaryError.at("/anyOf/0/$ref").asString());
        assertEquals("null", primaryError.at("/anyOf/1/type").asString());
        assertEquals("First validation error", primaryError.get("description").asString());
        assertEquals("{\"field\":\"status\"}", primaryError.get("example").asString());

        var rootError = properties.get("rootError");
        assertEquals("#/components/schemas/FieldError", rootError.get("$ref").asString());
        assertEquals("Root error", rootError.get("description").asString());
        assertEquals("{\"field\":\"name\"}", rootError.get("example").asString());
    }

    @Test
    void valueClassNullabilityEnumPriorityComponentIdAndRequestPartWork() {
        var properties = spec().at("/components/schemas/ContractReq/properties");

        assertEquals("string", properties.at("/tenantCode/type").asString());
        assertEquals(2, properties.at("/optionalTenantCode/type").size());
        assertEquals("string", properties.at("/optionalTenantCode/type/0").asString());
        assertEquals("null", properties.at("/optionalTenantCode/type/1").asString());

        assertEquals("Class enum description", properties.at("/defaultPriority/description").asString());
        assertEquals("Field enum description", properties.at("/overridePriority/description").asString());

        assertTrue(properties.has("componentId"));
        assertEquals("string", properties.at("/componentId/type").asString());

        var paths = spec().get("paths");
        var requiredUpload = paths.get("/api/features/{id}/asset").at("/post/requestBody");
        assertTrue(requiredUpload.get("required").asBoolean());

        var optionalUpload = paths.get("/api/contracts/optional-upload").at("/post/requestBody");
        assertFalse(optionalUpload.get("required").asBoolean());
        assertEquals(
                "Optional upload note",
                optionalUpload.at("/content/multipart~1form-data/schema/properties/note/description").asString()
        );
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
