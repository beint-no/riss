package no.beint.riss.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import no.beint.riss.model.OpenApi
import java.nio.charset.StandardCharsets

internal class SpecEmitter(
    private val codeGenerator: CodeGenerator,
    private val generatedPackage: String,
    private val registryName: String,
) {
    fun emit(document: OpenApi, specName: String, files: List<KSFile>) {
        val dependencies = Dependencies(aggregating = true, *files.toTypedArray())
        val json = OpenApiWriter.write(document)
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "json",
        ).use { it.write(json) }
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "java",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.appendLine("package $generatedPackage;")
            output.appendLine()
            output.appendLine("import java.io.IOException;")
            output.appendLine("import java.io.InputStream;")
            output.appendLine("import java.io.UncheckedIOException;")
            output.appendLine("import no.beint.riss.SpecSet;")
            output.appendLine()
            output.appendLine("public final class $registryName implements SpecSet {")
            output.appendLine("    private static final byte[] JSON = load();")
            output.appendLine()
            output.appendLine("    @Override")
            output.appendLine("    public String name() {")
            output.appendLine("        return \"${escape(specName)}\";")
            output.appendLine("    }")
            output.appendLine()
            output.appendLine("    @Override")
            output.appendLine("    public byte[] json() {")
            output.appendLine("        return JSON;")
            output.appendLine("    }")
            output.appendLine()
            output.appendLine("    private static byte[] load() {")
            output.appendLine("        try (InputStream in = $registryName.class.getResourceAsStream(\"$registryName.json\")) {")
            output.appendLine("            if (in == null) {")
            output.appendLine("                throw new IllegalStateException(\"Missing $registryName.json\");")
            output.appendLine("            }")
            output.appendLine("            return in.readAllBytes();")
            output.appendLine("        } catch (IOException exception) {")
            output.appendLine("            throw new UncheckedIOException(exception);")
            output.appendLine("        }")
            output.appendLine("    }")
            output.appendLine("}")
        }
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = "META-INF/services/no.beint.riss.SpecSet",
            extensionName = "",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.appendLine("$generatedPackage.$registryName")
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
