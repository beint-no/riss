package no.beint.riss.compiler

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

public class RissProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = RissProcessor(environment)
}

private class RissProcessor(
    environment: SymbolProcessorEnvironment,
) : SymbolProcessor {
    private val codeGenerator = environment.codeGenerator
    private val logger = environment.logger
    private val options = RissOptions.from(environment)
    private var completed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (completed) return emptyList()
        completed = true
        try {
            validateConfiguration()
            val diagnostics = Diagnostics()
            val enumReader = EnumReader(options.classpath, diagnostics)
            val schemas = SchemaFactory(enumReader, diagnostics, options.strict)
            val documentType = resolver.getSymbolsWithAnnotation(Names.RISS_DOCUMENT).firstOrNull()
            val documentAnnotation = documentType?.annotations?.firstOrNull { it.matches(Names.RISS_DOCUMENT) }
            val scanPackages = documentAnnotation?.strings("scanPackages").orEmpty().ifEmpty { options.scanPackages }
            val includePaths = documentAnnotation?.strings("paths").orEmpty().ifEmpty { options.paths }
            val excludePaths = documentAnnotation?.strings("excludePaths").orEmpty().ifEmpty { options.excludePaths }
            val scanner = ControllerScanner(schemas, diagnostics, scanPackages, includePaths, excludePaths)
            val functions = Names.mappings.keys
                .asSequence()
                .flatMap { resolver.getSymbolsWithAnnotation(it) }
                .filterIsInstance<KSFunctionDeclaration>()
                .distinct()
            val operations = scanner.scan(functions)
            if (operations.isEmpty()) {
                diagnostics.error(
                    "RISS-EMPTY",
                    "no HTTP mappings found in ${scanPackages.ifEmpty { listOf("the compilation") }.joinToString()}",
                    documentType,
                )
            }
            val assembled = DocumentAssembler(resolver, enumReader, schemas, diagnostics, options).assemble(operations)
            if (diagnostics.problems.isNotEmpty()) {
                diagnostics.problems.forEach { problem ->
                    logger.error(problem.message, problem.symbol)
                }
                return emptyList()
            }
            SpecEmitter(codeGenerator, options.generatedPackage, options.registryName)
                .emit(assembled.document, assembled.specName, assembled.files)
        } catch (exception: IllegalArgumentException) {
            logger.error(exception.message ?: "Riss compilation failed")
        } catch (exception: IllegalStateException) {
            logger.error(exception.message ?: "Riss compilation failed")
        }
        return emptyList()
    }

    private fun validateConfiguration() {
        require(options.generatedPackage.matches(PACKAGE)) {
            "Invalid generated package '${options.generatedPackage}'"
        }
        require(options.registryName.matches(IDENTIFIER)) {
            "Invalid registry name '${options.registryName}'"
        }
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
