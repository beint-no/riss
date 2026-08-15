package no.beint.riss.compiler

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

internal data class EnumEntryValues(
    val name: String,
    val values: Map<String, String>,
)

internal class EnumReader(
    private val classpath: List<Path>,
    private val diagnostics: Diagnostics,
) {
    fun values(
        enumClass: KSClassDeclaration,
        property: String?,
        whereProperty: String?,
        whereValue: String?,
        location: String,
    ): List<String> {
        val entries = read(enumClass, location) ?: return emptyList()
        val filtered = if (whereProperty.isNullOrBlank() || whereValue.isNullOrBlank()) {
            entries
        } else {
            entries.filter { it.values[whereProperty] == whereValue }
        }
        if (property.isNullOrBlank()) {
            return filtered.map { it.name }
        }
        val missing = filtered.filter { property !in it.values }
        if (missing.isNotEmpty()) {
            diagnostics.error(
                "RISS-ENUM",
                location,
                "enum ${enumClass.qualified()} is missing constructor value '$property' on ${missing.joinToString { it.name }}",
            )
            return emptyList()
        }
        return filtered.map { it.values.getValue(property) }
    }

    private fun read(enumClass: KSClassDeclaration, location: String): List<EnumEntryValues>? {
        val fromSource = enumClass.containingFile?.let { readSource(enumClass, it) }
        if (!fromSource.isNullOrEmpty()) {
            return fromSource
        }
        val reflected = readReflectively(enumClass)
        if (!reflected.isNullOrEmpty()) {
            return reflected
        }
        val binary = enumClass.qualified().replace('.', '/') + ".class"
        val bytes = findClass(binary)
        if (bytes != null) {
            val parsed = readClass(enumClass, bytes)
            if (parsed != null) {
                return parsed
            }
        }
        val names = enumClass.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()
        if (names.isNotEmpty() && enumClass.containingFile != null) {
            return names.map { EnumEntryValues(it, emptyMap()) }
        }
        diagnostics.error(
            "RISS-ENUM",
            location,
            "cannot read compile-time values for enum ${enumClass.qualified()}",
        )
        return null
    }

    private fun readSource(enumClass: KSClassDeclaration, file: KSFile): List<EnumEntryValues>? {
        val parameterNames = enumClass.primaryConstructor
            ?.parameters
            ?.mapNotNull { it.name?.asString() }
            .orEmpty()
        val text = file.filePath.let { path ->
            runCatching { Files.readString(Path.of(path)) }.getOrNull()
        } ?: return null
        val known = enumClass.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .associateBy { it.simpleName.asString() }
        if (known.isEmpty()) {
            return null
        }
        val simple = enumClass.simpleName.asString()
        val start = text.indexOf("enum class $simple").takeIf { it >= 0 }
            ?: text.indexOf("enum $simple").takeIf { it >= 0 }
            ?: return null
        val bodyStart = text.indexOf('{', start)
        if (bodyStart < 0) {
            return null
        }
        val body = text.substring(bodyStart + 1)
        val entries = mutableListOf<EnumEntryValues>()
        val regex = Regex("""([A-Z][A-Z0-9_]*)\s*(?:\((.*?)\))?""", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(body)) {
            val name = match.groupValues[1]
            val entry = known[name] ?: continue
            val args = match.groupValues.getOrNull(2).orEmpty()
            val values = linkedMapOf<String, String>()
            parseArguments(args).forEachIndexed { index, value ->
                val parameter = parameterNames.getOrNull(index) ?: return@forEachIndexed
                values[parameter] = value
            }
            entry.annotation(Names.JSON_PROPERTY)?.string("value")?.let { values["value"] = it }
            entries += EnumEntryValues(name, values)
            if (entries.size == known.size) {
                break
            }
        }
        return entries.takeIf { it.size == known.size }
    }

    private fun parseArguments(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var escape = false
        for (character in raw) {
            when {
                escape -> {
                    current.append(character)
                    escape = false
                }
                quote != null && character == '\\' -> {
                    current.append(character)
                    escape = true
                }
                quote != null && character == quote -> {
                    current.append(character)
                    quote = null
                }
                quote == null && (character == '"' || character == '\'') -> {
                    quote = character
                    current.append(character)
                }
                quote == null && (character == '(' || character == '[' || character == '{') -> {
                    depth++
                    current.append(character)
                }
                quote == null && (character == ')' || character == ']' || character == '}') -> {
                    depth--
                    current.append(character)
                }
                quote == null && character == ',' && depth == 0 -> {
                    values += decodeArgument(current.toString())
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        if (current.isNotBlank()) {
            values += decodeArgument(current.toString())
        }
        return values
    }

    private fun decodeArgument(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
                trimmed.substring(1, trimmed.length - 1)
            trimmed.contains('.') -> trimmed.substringAfterLast('.')
            else -> trimmed
        }
    }

    private fun readReflectively(enumClass: KSClassDeclaration): List<EnumEntryValues>? {
        val name = enumClass.qualified()
        val urls = classpath.mapNotNull { path ->
            runCatching { path.toUri().toURL() }.getOrNull()
        }.toTypedArray()
        if (urls.isEmpty()) return null
        return try {
            URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
                val type = Class.forName(name, true, loader)
                val constants = type.enumConstants ?: return@use null
                val parameterNames = enumClass.primaryConstructor
                    ?.parameters
                    ?.mapNotNull { it.name?.asString() }
                    .orEmpty()
                constants.map { constant ->
                    val values = linkedMapOf<String, String>()
                    parameterNames.forEach { parameter ->
                        val method = type.methods.firstOrNull { method ->
                            method.name.equals("get${parameter.replaceFirstChar(Char::uppercaseChar)}", ignoreCase = false) &&
                                method.parameterCount == 0
                        } ?: type.methods.firstOrNull { method ->
                            method.name == parameter && method.parameterCount == 0
                        }
                        val raw = method?.invoke(constant) ?: return@forEach
                        values[parameter] = when (raw) {
                            is Enum<*> -> raw.name
                            else -> raw.toString()
                        }
                    }
                    EnumEntryValues((constant as Enum<*>).name, values)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findClass(resource: String): ByteArray? {
        classpath.forEach { root ->
            if (Files.isDirectory(root)) {
                val file = root.resolve(resource)
                if (Files.isRegularFile(file)) {
                    return Files.readAllBytes(file)
                }
            } else if (Files.isRegularFile(root) && root.fileName.toString().endsWith(".jar")) {
                JarFile(root.toFile()).use { jar ->
                    val entry = jar.getJarEntry(resource) ?: return@use
                    jar.getInputStream(entry).use { return it.readAllBytes() }
                }
            }
        }
        return null
    }

    private fun readClass(enumClass: KSClassDeclaration, bytes: ByteArray): List<EnumEntryValues>? {
        val parameterNames = enumClass.primaryConstructor
            ?.parameters
            ?.mapNotNull { it.name?.asString() }
            .orEmpty()
        if (parameterNames.isEmpty()) {
            return enumClass.declarations
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.ENUM_ENTRY }
                .map { EnumEntryValues(it.simpleName.asString(), emptyMap()) }
                .toList()
                .takeIf { it.isNotEmpty() }
        }
        val visitor = EnumClassVisitor(enumClass.qualified().replace('.', '/'), parameterNames)
        ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return visitor.entries.takeIf { it.isNotEmpty() }
    }
}

private class EnumClassVisitor(
    private val owner: String,
    private val parameterNames: List<String>,
) : ClassVisitor(Opcodes.ASM9) {
    val entries = mutableListOf<EnumEntryValues>()

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        if (name != "<clinit>") return null
        return object : MethodVisitor(Opcodes.ASM9) {
            private val stack = mutableListOf<String>()

            override fun visitLdcInsn(value: Any?) {
                stack += value?.toString().orEmpty()
            }

            override fun visitIntInsn(opcode: Int, operand: Int) {
                stack += operand.toString()
            }

            override fun visitInsn(opcode: Int) {
                val value = when (opcode) {
                    Opcodes.ICONST_M1 -> "-1"
                    Opcodes.ICONST_0 -> "0"
                    Opcodes.ICONST_1 -> "1"
                    Opcodes.ICONST_2 -> "2"
                    Opcodes.ICONST_3 -> "3"
                    Opcodes.ICONST_4 -> "4"
                    Opcodes.ICONST_5 -> "5"
                    Opcodes.ACONST_NULL -> ""
                    else -> return
                }
                stack += value
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                if (opcode == Opcodes.GETSTATIC) {
                    stack += name
                }
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                if (opcode == Opcodes.INVOKESPECIAL && owner == this@EnumClassVisitor.owner && name == "<init>") {
                    val declared = parameterNames.size
                    if (stack.size >= declared + 2) {
                        val args = stack.takeLast(declared)
                        val enumName = stack.getOrNull(stack.size - declared - 2).orEmpty()
                        val values = linkedMapOf<String, String>()
                        parameterNames.forEachIndexed { index, parameter ->
                            values[parameter] = args.getOrElse(index) { "" }
                        }
                        if (enumName.isNotBlank()) {
                            entries += EnumEntryValues(enumName, values)
                        }
                    }
                    stack.clear()
                } else if (opcode != Opcodes.INVOKESPECIAL) {
                    stack.clear()
                }
            }
        }
    }
}
