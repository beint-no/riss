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
            )
        }

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
