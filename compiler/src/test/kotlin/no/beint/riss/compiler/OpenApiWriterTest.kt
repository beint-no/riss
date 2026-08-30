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
