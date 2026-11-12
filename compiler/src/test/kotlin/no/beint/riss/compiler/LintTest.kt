package no.beint.riss.compiler

import no.beint.riss.model.Info
import no.beint.riss.model.OpenApi
import no.beint.riss.model.Operation
import no.beint.riss.model.Parameter
import no.beint.riss.model.PathItem
import no.beint.riss.model.Response
import no.beint.riss.model.Schema
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LintTest {
    private val string = Schema.builder().type("string").build()
    private val options = LintOptions(
        bannedNames = mapOf("keyword" to "query", "currency" to "currencyCode"),
        bannedSchemaSuffixes = listOf("Request"),
        enumPattern = Regex("[a-z0-9]+(_[a-z0-9]+)*"),
        formatBySuffix = mapOf("At" to "date-time", "Date" to "date"),
        pageSchemaPrefix = "Page",
    )

    private fun document(): OpenApi {
        val list = Operation.builder("orders_list")
            .parameter(Parameter.query("keyword", string, false))
            .parameter(Parameter.query("page", string, false))
            .parameter(Parameter.query("status", Schema.builder().type("string").enumValues(listOf("open", "CLOSED")).build(), false))
            .response("200", Response.json("OK", Schema.ref("#/components/schemas/OrderList")))
            .build()
        val paged = Operation.builder("customers_list")
            .parameter(Parameter.query("page", string, false))
            .parameter(Parameter.query("pageSize", string, false))
            .response("200", Response.json("OK", Schema.ref("#/components/schemas/Page_Customer")))
            .build()
        return OpenApi.builder(Info("Test", "1", null, null))
            .path("/api/orders", PathItem(mapOf("get" to list)))
            .path("/api/customers", PathItem(mapOf("get" to paged)))
            .schema("OrderRequest", Schema.builder().type("object")
                .property("currency", string)
                .property("createdAt", string)
                .property("format", string)
                .property("dueDate", Schema.builder().type("string").format("date").build())
                .build())
            .schema("Page_Customer", Schema.builder().type("object").property("currencyCode", string).build())
            .schema("OrderList", Schema.builder().type("object").build())
            .build()
    }

    @Test
    fun reportsEachRule() {
        assertEquals(
            listOf(
                "NAME GET /api/orders ?keyword",
                "ENUM GET /api/orders ?status",
                "PAGE GET /api/orders",
                "SCHEMA OrderRequest",
                "FORMAT OrderRequest.createdAt",
                "NAME OrderRequest.currency",
            ),
            Lint(options).violations(document()).map { it.key },
        )
    }

    @Test
    fun baselineAcceptsKnownViolationsAndRejectsStaleEntries() {
        val baseline = Files.createTempFile("riss-lint", ".txt")
        val report = Files.createTempFile("riss-lint-report", ".txt")
        Files.write(baseline, listOf(
            "# accepted until the next client release",
            "NAME GET /api/orders ?keyword # Shipping app",
            "ENUM GET /api/orders ?status",
            "PAGE GET /api/orders",
            "SCHEMA OrderRequest",
            "FORMAT OrderRequest.createdAt",
            "NAME OrderRequest.gone",
        ))
        val diagnostics = Diagnostics()
        Lint(options.copy(baseline = baseline, report = report)).check(document(), diagnostics)
        val messages = diagnostics.problems.map { it.message }
        assertEquals(2, messages.size)
        assertTrue(messages[0].startsWith("RISS-LINT NAME OrderRequest.currency: use 'currencyCode' instead of 'currency'"))
        assertTrue(messages[1].startsWith("RISS-LINT NAME OrderRequest.gone: no longer violates a rule"))
        assertEquals(listOf("NAME OrderRequest.currency"), Files.readAllLines(report))
    }
}
