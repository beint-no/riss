package no.beint.riss.compiler

import no.beint.riss.model.Info
import no.beint.riss.model.OpenApi
import no.beint.riss.model.Operation
import no.beint.riss.model.Parameter
import no.beint.riss.model.PathItem
import no.beint.riss.model.Response
import no.beint.riss.model.Schema
import no.beint.riss.model.SecurityRequirement
import no.beint.riss.model.SecurityScheme
import no.beint.riss.model.Tag
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiWriterTest {
    @Test
    fun writesOpenApi31Json() {
        val document = OpenApi.builder(Info("Demo", "1", "A demo", null))
            .security(SecurityRequirement.of("bearer-token"))
            .securityScheme("bearer-token", SecurityScheme.httpBearer("JWT"))
            .schema("Name", Schema.builder().type("string").enumValues(listOf("a", "b")).build())
            .path(
                "/items/{id}",
                PathItem(
                    mapOf(
                        "get" to Operation.builder("getItem")
                            .summary("Get item")
                            .tag("Items")
                            .parameter(Parameter.path("id", Schema.builder().type("integer").format("int32").build()))
                            .response("200", Response.json("OK", Schema.ref("#/components/schemas/Name")))
                            .build(),
                    ),
                ),
            )
            .tag(Tag("Items", "Things"))
            .build()

        val json = OpenApiWriter.writeString(document)
        assertTrue(json.contains("\"openapi\":\"3.1.0\""))
        assertTrue(json.contains("jsonSchemaDialect"))
        assertTrue(json.contains("/items/{id}"))
        assertFalse(json.contains('\n'))
        assertFalse(json.contains("yaml"))
    }

    @Test
    fun phoneExamplesStayStrings() {
        val document = OpenApi.builder(Info("N", "1", null, null))
            .schema("Phone", Schema.builder().type("string").example("+4799999999").build())
            .path(
                "/n",
                PathItem(mapOf("get" to Operation.builder("n").response("204", Response.of("No content")).build())),
            )
            .build()
        val json = OpenApiWriter.writeString(document)
        assertTrue(json.contains("+4799999999"))
        assertTrue(json.contains("\"+4799999999\""))
    }

    @Test
    fun stringAccountNumbersStayStrings() {
        val document = OpenApi.builder(Info("N", "1", null, null))
            .schema("AccountNumber", Schema.builder().type("string").example("3000").build())
            .path(
                "/n",
                PathItem(mapOf("get" to Operation.builder("n").response("204", Response.of("No content")).build())),
            )
            .build()
        val json = OpenApiWriter.writeString(document)
        assertTrue(json.contains("\"3000\""))
        assertFalse(json.contains("\"example\" : 3000") || json.contains("\"example\": 3000"))
    }

    @Test
    fun writesTheClosedJsonValueSetWithoutJackson() {
        val value = linkedMapOf(
            "text" to "quote \" slash \\ controls \b\u000c\n\r\t\u0001 emoji 😀",
            "numbers" to listOf(-2, 3L, BigInteger("4"), BigDecimal("5.25"), 6.5),
            "boolean" to true,
            "nothing" to null,
        )

        assertEquals(
            "{\"boolean\":true,\"nothing\":null,\"numbers\":[-2,3,4,5.25,6.5]," +
                "\"text\":\"quote \\\" slash \\\\ controls \\b\\f\\n\\r\\t\\u0001 emoji 😀\"}",
            CompactJsonWriter.write(value),
        )
        assertFailsWith<IllegalArgumentException> { CompactJsonWriter.write(Double.NaN) }
    }

    @Test
    fun writesRefMetadataAndExclusiveBoundsAndPropertyLimits() {
        val refWithMeta = Schema.ref("#/components/schemas/Item").toBuilder()
            .description("Primary item")
            .defaultValue("{}")
            .example("{\"id\":1}")
            .build()
        val nullableRefWithMeta = refWithMeta.nullable()
        val boundedNumber = Schema.builder()
            .type("number")
            .exclusiveMinimum(BigDecimal.ZERO)
            .exclusiveMaximum(BigDecimal("100.5"))
            .build()
        val boundedMap = Schema.builder()
            .type("object")
            .additionalProperties(Schema.builder().type("string").build())
            .minProperties(1)
            .maxProperties(5)
            .build()
        val document = OpenApi.builder(Info("Meta", "1", null, null))
            .schema("Item", Schema.builder().type("object").build())
            .schema("RefWithMeta", refWithMeta)
            .schema("NullableRefWithMeta", nullableRefWithMeta)
            .schema("BoundedNumber", boundedNumber)
            .schema("BoundedMap", boundedMap)
            .path(
                "/n",
                PathItem(mapOf("get" to Operation.builder("n").response("204", Response.of("No content")).build())),
            )
            .build()

        val json = OpenApiWriter.writeString(document)
        assertTrue(json.contains("\"RefWithMeta\":{\"${'$'}ref\":\"#/components/schemas/Item\",\"default\":\"{}\",\"description\":\"Primary item\",\"example\":\"{\\\"id\\\":1}\"}"))
        assertTrue(json.contains("\"NullableRefWithMeta\":{\"anyOf\":[{\"${'$'}ref\":\"#/components/schemas/Item\"},{\"type\":\"null\"}],\"default\":\"{}\",\"description\":\"Primary item\",\"example\":\"{\\\"id\\\":1}\"}"))
        assertTrue(json.contains("\"BoundedNumber\":{\"exclusiveMaximum\":100.5,\"exclusiveMinimum\":0,\"type\":\"number\"}"))
        assertTrue(json.contains("\"BoundedMap\":{\"additionalProperties\":{\"type\":\"string\"},\"maxProperties\":5,\"minProperties\":1,\"type\":\"object\"}"))
    }

    @Test
    fun byteAndStringOutputAreIdenticalUtf8() {
        val document = OpenApi.builder(Info("Blåbær 😀", "1", null, null))
            .path(
                "/n",
                PathItem(mapOf("get" to Operation.builder("n").response("204", Response.of("No content")).build())),
            )
            .build()

        assertEquals(OpenApiWriter.writeString(document), OpenApiWriter.write(document).decodeToString())
    }
}
