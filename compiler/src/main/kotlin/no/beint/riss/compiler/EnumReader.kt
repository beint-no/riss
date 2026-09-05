package no.beint.riss.compiler

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.lang.classfile.ClassFile
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.InvokeInstruction
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
) : AutoCloseable {
    private val entriesByClass = mutableMapOf<String, List<EnumEntryValues>>()
    private val lazyLoader = lazy {
        val urls = classpath.mapNotNull { path -> runCatching { path.toUri().toURL() }.getOrNull() }
        if (urls.isEmpty()) null else URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
    }
    private val loader by lazyLoader

    override fun close() {
        if (lazyLoader.isInitialized()) loader?.close()
        entriesByClass.clear()
    }

    fun values(
        enumClass: KSClassDeclaration,
        property: String?,
        whereProperty: String?,
        whereValue: String?,
        location: String,
    ): List<String> {
        val entries = entriesByClass.getOrPut(enumClass.qualified()) {
            read(enumClass, location) ?: return emptyList()
        }
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
                enumClass,
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
            enumClass,
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
        val loader = loader ?: return null
        return try {
            val type = Class.forName(enumClass.qualified(), true, loader)
            val constants = type.enumConstants ?: return null
            val parameterNames = enumClass.primaryConstructor
                ?.parameters
                ?.mapNotNull { it.name?.asString() }
                .orEmpty()
            val methods = type.methods
            val accessors = parameterNames.mapNotNull { parameter ->
                val getterName = "get${parameter.replaceFirstChar(Char::uppercaseChar)}"
                val method = methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
                    ?: methods.firstOrNull { it.name == parameter && it.parameterCount == 0 }
                    ?: return@mapNotNull null
                parameter to method
            }
            constants.map { constant ->
                val values = linkedMapOf<String, String>()
                accessors.forEach { (parameter, method) ->
                    val raw = method.invoke(constant) ?: return@forEach
                    values[parameter] = when (raw) {
                        is Enum<*> -> raw.name
                        else -> raw.toString()
                    }
                }
                EnumEntryValues((constant as Enum<*>).name, values)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findClass(resource: String): ByteArray? {
        loader?.getResourceAsStream(resource)?.use { return it.readAllBytes() }
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
        return readEnumEntries(enumClass.qualified().replace('.', '/'), parameterNames, bytes)
    }
}

internal fun readEnumEntries(
    owner: String,
    parameterNames: List<String>,
    bytes: ByteArray,
): List<EnumEntryValues>? {
    val entries = mutableListOf<EnumEntryValues>()
    val stack = mutableListOf<String>()
    val initializer = ClassFile.of().parse(bytes).methods()
        .firstOrNull { it.methodName().equalsString("<clinit>") }
        ?.code()
        ?.orElse(null)
        ?: return null

    initializer.forEach { element ->
        when (element) {
            is ConstantInstruction -> stack += when (element.opcode()) {
                Opcode.ACONST_NULL -> ""
                else -> element.constantValue().toString()
            }

            is FieldInstruction -> if (element.opcode() == Opcode.GETSTATIC) {
                stack += element.name().stringValue()
            }

            is InvokeInstruction -> {
                if (
                    element.opcode() == Opcode.INVOKESPECIAL &&
                    element.owner().asInternalName() == owner &&
                    element.name().equalsString("<init>")
                ) {
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
                } else if (element.opcode() != Opcode.INVOKESPECIAL) {
                    stack.clear()
                }
            }

            else -> Unit
        }
    }

    return entries.takeIf { it.isNotEmpty() }
}
