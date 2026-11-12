package no.beint.riss.compiler

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import java.nio.file.Files
import java.nio.file.Path

internal data class RissOptions(
    val generatedPackage: String,
    val registryName: String,
    val specName: String,
    val scanPackages: List<String>,
    val paths: List<String>,
    val excludePaths: List<String>,
    val title: String?,
    val version: String,
    val strict: Boolean,
    val classpath: List<Path>,
    val lint: LintOptions = LintOptions(),
) {
    companion object {
        fun from(environment: SymbolProcessorEnvironment): RissOptions {
            val options = environment.options
            return RissOptions(
                generatedPackage = options["riss.package"] ?: "riss.generated",
                registryName = options["riss.registry"] ?: "RissSpec",
                specName = options["riss.specName"] ?: "api",
                scanPackages = csv(options["riss.scanPackages"]),
                paths = csv(options["riss.paths"]),
                excludePaths = csv(options["riss.excludePaths"]),
                title = options["riss.title"]?.takeIf(String::isNotBlank),
                version = options["riss.version"]?.takeIf(String::isNotBlank) ?: "1",
                strict = options["riss.strict"]?.toBoolean() ?: true,
                classpath = classpath(options["riss.classpathFile"]),
                lint = LintOptions(
                    bannedNames = pairs(options["riss.lint.bannedNames"]),
                    bannedNamePatterns = pairs(options["riss.lint.bannedNamePatterns"]).mapKeys { Regex(it.key) },
                    bannedSchemaSuffixes = csv(options["riss.lint.bannedSchemaSuffixes"]),
                    enumPattern = options["riss.lint.enumPattern"]?.takeIf(String::isNotBlank)?.let(::Regex),
                    formatBySuffix = pairs(options["riss.lint.formatBySuffix"]),
                    pageSchemaPrefix = options["riss.lint.pageSchemaPrefix"]?.takeIf(String::isNotBlank),
                    baseline = options["riss.lint.baseline"]?.takeIf(String::isNotBlank)?.let(Path::of),
                    report = options["riss.lint.report"]?.takeIf(String::isNotBlank)?.let(Path::of),
                ),
            )
        }

        private fun pairs(value: String?): Map<String, String> =
            csv(value).associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

        private fun csv(value: String?): List<String> =
            value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

        private fun classpath(file: String?): List<Path> {
            if (file.isNullOrBlank()) return emptyList()
            val path = Path.of(file)
            if (!Files.isRegularFile(path)) return emptyList()
            return Files.readAllLines(path).flatMap { line ->
                line.split(System.getProperty("path.separator"))
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { Path.of(it) }
            }
        }
    }
}
