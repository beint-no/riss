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

    @Test
    fun reportsPathParamsPositiveIdsKebabPathsSummariesAndPageBounds() {
        val unboundedInt = Schema.builder().type("integer").build()
        val positiveInt = Schema.builder().type("integer").minimum(java.math.BigDecimal.ONE).build()
        val boundedPageSize = Schema.builder().type("integer")
            .minimum(java.math.BigDecimal.ONE)
            .maximum(java.math.BigDecimal.valueOf(200))
            .build()

        val badOp = Operation.builder("bad_op")
            .parameter(Parameter.path("orgNumber", unboundedInt))
            .parameter(Parameter.path("orderId", unboundedInt))
            .parameter(Parameter.query("customerId", unboundedInt, false))
            .parameter(Parameter.query("valid", unboundedInt, false))
            .parameter(Parameter.query("page", unboundedInt, false))
            .parameter(Parameter.query("pageSize", positiveInt, false))
            .response("200", Response.json("OK", Schema.ref("#/components/schemas/Page_Order")))
            .build()

        val goodOp = Operation.builder("good_op")
            .summary("List orders")
            .parameter(Parameter.path("id", positiveInt))
            .parameter(Parameter.query("page", positiveInt, false))
            .parameter(Parameter.query("pageSize", boundedPageSize, false))
            .response("200", Response.json("OK", Schema.ref("#/components/schemas/Page_Order")))
            .build()

        val doc = OpenApi.builder(Info("Test", "1", null, null))
            .path("/api/BadPath/{orgNumber}/{orderId}", PathItem(mapOf("get" to badOp)))
            .path("/api/good-path/{id}/export.csv", PathItem(mapOf("get" to goodOp)))
            .schema("Page_Order", Schema.builder().type("object").build())
            .build()

        val strictOptions = LintOptions(
            bannedNames = mapOf("orgNumber" to "organizationNumber"),
            pageSchemaPrefix = "Page",
            positiveIds = true,
            kebabPaths = true,
            requireSummary = true,
        )

        assertEquals(
            listOf(
                "PATH /api/BadPath/{orgNumber}/{orderId}",
                "SUMMARY GET /api/BadPath/{orgNumber}/{orderId}",
                "NAME GET /api/BadPath/{orgNumber}/{orderId} {orgNumber}",
                "ID GET /api/BadPath/{orgNumber}/{orderId} {orderId}",
                "ID GET /api/BadPath/{orgNumber}/{orderId} ?customerId",
                "PAGE GET /api/BadPath/{orgNumber}/{orderId} ?page",
                "PAGE GET /api/BadPath/{orgNumber}/{orderId} ?pageSize",
            ),
            Lint(strictOptions).violations(doc).map { it.key },
        )
    }

    @Test
    fun requireAuthorizationRespectsAnnotatedOpsPublicPathsAndBaseline() {
        val op = Operation.builder("op")
            .summary("Summary")
            .response("200", Response.of("OK"))
            .build()
        val doc = OpenApi.builder(Info("Test", "1", null, null))
            .path("/api/health", PathItem(mapOf("get" to op)))
            .path("/api/public/login", PathItem(mapOf("post" to op)))
            .path("/api/public/nested/callback", PathItem(mapOf("get" to op)))
            .path("/api/orders", PathItem(mapOf("get" to op, "post" to op)))
            .path("/api/admin/users", PathItem(mapOf("get" to op)))
            .build()

        val authOptions = LintOptions(
            requireAuthorization = true,
            publicPaths = listOf("/api/health", "/api/public/**"),
        )
        val authorized = setOf("GET /api/orders")

        val violations = Lint(authOptions).violations(doc, authorized)
        assertEquals(
            listOf(
                LintViolation(
                    "AUTH GET /api/admin/users",
                    "operation must declare an authorization annotation (e.g. @PreAuthorize) or be listed in lintPublicPaths",
                ),
                LintViolation(
                    "AUTH POST /api/orders",
                    "operation must declare an authorization annotation (e.g. @PreAuthorize) or be listed in lintPublicPaths",
                ),
            ),
            violations,
        )

        val baseline = Files.createTempFile("riss-auth-lint", ".txt")
        Files.write(
            baseline,
            listOf(
                "AUTH POST /api/orders # legacy endpoint",
                "AUTH GET /api/orders # now authorized -> stale",
            ),
        )
        val diagnostics = Diagnostics()
        Lint(authOptions.copy(baseline = baseline)).check(doc, diagnostics, authorized)
        val messages = diagnostics.problems.map { it.message }
        assertEquals(2, messages.size)
        assertTrue(messages[0].startsWith("RISS-LINT AUTH GET /api/admin/users: operation must declare an authorization annotation"))
        assertTrue(messages[1].startsWith("RISS-LINT AUTH GET /api/orders: no longer violates a rule"))
    }
}
