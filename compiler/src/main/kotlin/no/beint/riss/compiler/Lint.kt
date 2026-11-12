package no.beint.riss.compiler

import no.beint.riss.model.OpenApi
import no.beint.riss.model.Operation
import no.beint.riss.model.Schema
import java.nio.file.Files
import java.nio.file.Path

/**
 * Naming and shape rules for the assembled document. A violation fails the build unless its key is in the baseline;
 * a baseline key that no longer matches fails too, so the baseline can only shrink.
 */
internal data class LintOptions(
    val bannedNames: Map<String, String> = emptyMap(),
    val bannedSchemaSuffixes: List<String> = emptyList(),
    val enumPattern: Regex? = null,
    val formatBySuffix: Map<String, String> = emptyMap(),
    val pageSchemaPrefix: String? = null,
    val baseline: Path? = null,
    val report: Path? = null,
) {
    val enabled: Boolean
        get() = bannedNames.isNotEmpty() || bannedSchemaSuffixes.isNotEmpty() || enumPattern != null ||
            formatBySuffix.isNotEmpty() || pageSchemaPrefix != null
}

internal data class LintViolation(val key: String, val message: String)

internal class Lint(private val options: LintOptions) {
    fun violations(document: OpenApi): List<LintViolation> {
        val found = mutableListOf<LintViolation>()
        document.paths().toSortedMap().forEach { (path, item) ->
            item.operations().toSortedMap().forEach { (method, operation) ->
                val location = "${method.uppercase()} $path"
                val query = operation.parameters().filter { it.locatedIn() == "query" }
                query.forEach { parameter -> value("$location ?${parameter.name()}", parameter.name(), parameter.schema(), found) }
                page(location, operation, query.map { it.name() }.toSet(), found)
            }
        }
        document.components().schemas().toSortedMap().forEach { (name, schema) ->
            options.bannedSchemaSuffixes.firstOrNull { name.substringBefore('_').endsWith(it) }?.let {
                found += LintViolation("SCHEMA $name", "schema names must not end in $it")
            }
            enum(name, schema, found)
            schema.properties().toSortedMap().forEach { (property, propertySchema) ->
                value("$name.$property", property, propertySchema, found)
            }
        }
        return found
    }

    /** Reports violations outside the baseline and baseline entries that no longer apply. */
    fun check(document: OpenApi, diagnostics: Diagnostics) {
        val violations = violations(document)
        val baseline = options.baseline?.takeIf(Files::isRegularFile)?.let(::readBaseline).orEmpty()
        val fresh = violations.filter { it.key !in baseline }
        val stale = baseline - violations.map { it.key }.toSet()
        options.report?.let { report ->
            Files.createDirectories(report.parent)
            Files.write(report, fresh.map { it.key })
        }
        fresh.take(MAX_REPORTED).forEach { diagnostics.error("RISS-LINT", it.key, it.message) }
        if (fresh.size > MAX_REPORTED) {
            diagnostics.error("RISS-LINT", "${fresh.size - MAX_REPORTED} more violations" + options.report?.let { "; all keys are in $it" }.orEmpty())
        }
        stale.sorted().take(MAX_REPORTED).forEach {
            diagnostics.error("RISS-LINT", it, "no longer violates a rule; remove it from the lint baseline")
        }
    }

    private fun value(key: String, name: String, schema: Schema, found: MutableList<LintViolation>) {
        options.bannedNames[name]?.let { preferred ->
            found += LintViolation("NAME $key", "use '$preferred' instead of '$name'")
        }
        enum(key, schema, found)
        if (schema.ref() == null) {
            options.formatBySuffix.entries.firstOrNull { (suffix, _) -> name.endsWithWord(suffix) }?.let { (suffix, format) ->
                if (schema.format() != format) {
                    found += LintViolation("FORMAT $key", "a name ending in '$suffix' must have format $format, or be renamed")
                }
            }
        }
    }

    private fun enum(key: String, schema: Schema, found: MutableList<LintViolation>) {
        val pattern = options.enumPattern ?: return
        val values = schema.enumValues().ifEmpty { schema.items()?.enumValues().orEmpty() }
        val invalid = values.filterNot(pattern::matches)
        if (invalid.isNotEmpty()) {
            found += LintViolation(
                "ENUM $key",
                "enum values must match ${pattern.pattern}: ${invalid.take(4).joinToString()}${if (invalid.size > 4) ", …" else ""}",
            )
        }
    }

    private fun page(location: String, operation: Operation, query: Set<String>, found: MutableList<LintViolation>) {
        val prefix = options.pageSchemaPrefix ?: return
        if ("page" !in query) return
        val response = operation.responses()["200"]?.content()?.values?.firstOrNull()?.schema()?.ref()?.substringAfterLast('/')
        if ("pageSize" !in query || response?.startsWith(prefix) != true) {
            found += LintViolation("PAGE $location", "an operation taking 'page' must also take 'pageSize' and return $prefix")
        }
    }

    /** `createdAt` ends with the word `At`; `format` and `flat` do not. */
    private fun String.endsWithWord(suffix: String): Boolean =
        this == suffix.replaceFirstChar(Char::lowercaseChar) ||
            (endsWith(suffix) && length > suffix.length && !this[length - suffix.length - 1].isUpperCase())

    private fun readBaseline(path: Path): Set<String> =
        Files.readAllLines(path).map { it.substringBefore(" #").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    private companion object {
        const val MAX_REPORTED = 40
    }
}
