package no.beint.riss.compiler

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import no.beint.riss.model.Schema
import java.math.BigDecimal

internal enum class SchemaUsage {
    ROOT,
    NESTED,
}

internal class SchemaFactory(
    private val enumReader: EnumReader,
    private val diagnostics: Diagnostics,
    private val strict: Boolean,
) {
    private val components = linkedMapOf<String, Schema>()
    private val names = linkedMapOf<String, String>()
    private val inProgress = mutableSetOf<String>()

    fun components(): Map<String, Schema> = components.toMap()

    fun named(name: String, schema: Schema) {
        components[name] = schema
    }

    fun schema(
        type: KSType,
        location: String,
        vararg annotated: KSAnnotated?,
        usage: SchemaUsage = SchemaUsage.NESTED,
        bindings: Bindings = Bindings.EMPTY,
    ): Schema {
        val sources = annotated.filterNotNull()
        val swagger = swaggerSchema(*sources.toTypedArray())
        swagger?.string("ref")?.let { return applySwagger(Schema.ref(normalizeRef(it)), swagger) }
        val resolved = unwrap(bindings.resolve(type), bindings)
        if (resolved.isError || resolved.declaration.simpleName.asString() == "<ERROR>") {
            diagnostics.error("RISS-TYPE", location, "unresolved type", type.declaration)
            return Schema.builder().type("object").build()
        }
        val declaration = resolved.declaration
        if (declaration is KSTypeParameter || isStarLike(resolved)) {
            return untyped(resolved, location, usage, sources, swagger)
        }
        val qualified = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        if (qualified in ANY_TYPES || qualified in JSON_TREES) {
            return untyped(resolved, location, usage, sources, swagger, qualified)
        }
        asCollection(resolved)?.let { collection ->
            val items = collection.item?.let { schema(it, location, *sources.toTypedArray(), bindings = bindings) }
                ?: Schema.unconstrained()
            return applyNullability(
                applyConstraints(Schema.builder().type("array").items(items).build(), sources, swagger),
                resolved,
            )
        }
        asMap(resolved)?.let { map ->
            val value = map.value?.let { bindings.resolve(it) }
            val valueName = value?.declaration?.qualifiedName?.asString()
            val additional = when {
                value == null || value.declaration is KSTypeParameter || valueName in ANY_TYPES || valueName in JSON_TREES ->
                    Schema.builder().type("object").additionalPropertiesAllowed(true).build()
                else ->
                    Schema.builder()
                        .type("object")
                        .additionalProperties(schema(value, location, bindings = bindings))
                        .build()
            }
            return applyNullability(applyConstraints(additional, sources, swagger), resolved)
        }
        primitive(qualified, resolved)?.let { primitive ->
            return applyNullability(applyConstraints(primitive, sources, swagger), resolved)
        }
        val classDeclaration = declaration as? KSClassDeclaration
            ?: run {
                diagnostics.error("RISS-TYPE", location, "unsupported type $qualified", declaration)
                return Schema.builder().type("object").build()
            }
        if (Modifier.VALUE in classDeclaration.modifiers || Modifier.INLINE in classDeclaration.modifiers) {
            val underlying = classDeclaration.primaryConstructor?.parameters?.singleOrNull()?.type?.resolve()
            if (underlying != null) {
                return schema(underlying, location, classDeclaration, *sources.toTypedArray(), usage = usage, bindings = bindings)
            }
        }
        jsonValueType(classDeclaration)?.let { valueType ->
            if (classDeclaration.classKind != ClassKind.ENUM_CLASS) {
                return applyNullability(schema(valueType, location, classDeclaration, bindings = bindings), resolved)
            }
        }
        if (classDeclaration.classKind == ClassKind.ENUM_CLASS) {
            return applyNullability(enumSchema(classDeclaration, location, swagger), resolved)
        }
        if (Modifier.SEALED in classDeclaration.modifiers) {
            return applyNullability(sealedSchema(classDeclaration, location, resolved, bindings), resolved)
        }
        val implementation = swagger?.type("implementation")
        val implementationName = implementation?.declaration?.qualifiedName?.asString()
        if (implementation != null &&
            implementationName != null &&
            implementationName != qualified &&
            implementationName !in IGNORE_IMPLEMENTATIONS
        ) {
            return schema(implementation, location, *sources.toTypedArray(), usage = usage, bindings = bindings)
        }
        val nextBindings = bindings.enter(classDeclaration, resolved)
        val name = componentName(classDeclaration, resolved, nextBindings)
        if (name in inProgress || name in components) {
            return applyNullability(Schema.ref(componentRef(name)), resolved)
        }
        inProgress += name
        val built = objectSchema(classDeclaration, location, nextBindings)
        components.putIfAbsent(name, built)
        inProgress -= name
        return applyNullability(Schema.ref(componentRef(name)), resolved)
    }

    fun unwrap(type: KSType, bindings: Bindings = Bindings.EMPTY): KSType {
        val qualified = type.declaration.qualifiedName?.asString() ?: return type
        if (qualified in WRAPPERS && type.arguments.isNotEmpty()) {
            val inner = type.arguments.first().type?.resolve() ?: return type
            return unwrap(bindings.resolve(inner), bindings)
        }
        return type
    }

    private fun untyped(
        type: KSType,
        location: String,
        usage: SchemaUsage,
        sources: List<KSAnnotated>,
        swagger: com.google.devtools.ksp.symbol.KSAnnotation?,
        qualified: String? = type.declaration.qualifiedName?.asString(),
    ): Schema {
        if (usage == SchemaUsage.ROOT &&
            strict &&
            qualified in ANY_TYPES
        ) {
            diagnostics.error(
                "RISS-TYPE",
                location,
                "public contract cannot use $qualified as a request or response type",
                type.declaration,
            )
        }
        return applyNullability(applyConstraints(Schema.unconstrained(), sources, swagger), type)
    }

    private fun sealedSchema(
        declaration: KSClassDeclaration,
        location: String,
        type: KSType,
        bindings: Bindings,
    ): Schema {
        val nextBindings = bindings.enter(declaration, type)
        val name = componentName(declaration, type, nextBindings)
        if (name in inProgress || name in components) {
            return Schema.ref(componentRef(name))
        }
        inProgress += name
        val subtypes = declaration.getSealedSubclasses().toList()
        if (subtypes.isEmpty()) {
            diagnostics.error(
                "RISS-SCHEMA",
                location,
                "${declaration.qualified()} is sealed but has no visible subclasses",
                declaration,
            )
            inProgress -= name
            return Schema.builder().type("object").build()
        }
        val variants = subtypes.map { subtype ->
            schema(
                declaredType(subtype),
                "${declaration.qualified()}.${subtype.simpleName.asString()}",
                bindings = nextBindings,
            )
        }
        val built = Schema.builder()
            .description(swaggerSchema(declaration)?.string("description"))
            .oneOf(variants)
            .build()
        components.putIfAbsent(name, built)
        inProgress -= name
        return Schema.ref(componentRef(name))
    }

    private fun declaredType(declaration: KSClassDeclaration): KSType =
        if (declaration.typeParameters.isEmpty()) {
            declaration.asType(emptyList())
        } else {
            declaration.asStarProjectedType()
        }

    private fun objectSchema(declaration: KSClassDeclaration, location: String, bindings: Bindings): Schema {
        val builder = Schema.builder().type("object")
        swaggerSchema(declaration)?.string("description")?.let(builder::description)
        properties(declaration).forEach { property ->
            val propertyLocation = "${declaration.qualified()}.${property.name}"
            val schema = schema(
                property.type,
                propertyLocation,
                *property.annotated.toTypedArray(),
                bindings = bindings,
            )
            builder.property(property.name, schema)
            if (property.required) {
                builder.require(property.name)
            }
        }
        if (builder.build().properties().isEmpty() &&
            strict &&
            declaration.classKind in setOf(ClassKind.CLASS, ClassKind.INTERFACE)
        ) {
            diagnostics.error(
                "RISS-SCHEMA",
                location,
                "${declaration.qualified()} has no serialisable properties",
                declaration,
            )
        }
        return builder.build()
    }

    private fun enumSchema(
        declaration: KSClassDeclaration,
        location: String,
        swagger: com.google.devtools.ksp.symbol.KSAnnotation?,
    ): Schema {
        val jsonValue = jsonValueType(declaration)
        val propertyName = jsonValuePropertyName(declaration)
        val values = if (propertyName != null) {
            enumReader.values(declaration, propertyName, null, null, location)
        } else {
            declaration.declarations
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.ENUM_ENTRY }
                .map { entry ->
                    entry.annotation(Names.JSON_PROPERTY)?.string("value")
                        ?: entry.simpleName.asString()
                }
                .toList()
        }
        val builder = Schema.builder()
            .type(primitiveKind(jsonValue?.declaration?.qualifiedName?.asString()) ?: "string")
            .enumValues(values)
        swagger?.string("description")?.let(builder::description)
        swaggerSchema(declaration)?.string("description")?.let(builder::description)
        return builder.build()
    }

    private fun properties(declaration: KSClassDeclaration, seen: MutableSet<String> = mutableSetOf()): List<PropertyModel> {
        if (!seen.add(declaration.qualified())) {
            return emptyList()
        }
        val own = ownProperties(declaration)
        val inherited = declaration.superTypes.flatMap { annotation ->
            val superType = annotation.resolve()
            val superDeclaration = superType.declaration as? KSClassDeclaration ?: return@flatMap emptyList()
            if (superDeclaration.classKind != ClassKind.CLASS) return@flatMap emptyList()
            if (superDeclaration.qualified() in SKIP_SUPERTYPES) return@flatMap emptyList()
            properties(superDeclaration, seen)
        }
        val names = own.map { it.name }.toSet()
        return own + inherited.filter { it.name !in names }
    }

    private fun ownProperties(declaration: KSClassDeclaration): List<PropertyModel> {
        val constructor = declaration.primaryConstructor
        if (constructor != null && constructor.parameters.isNotEmpty()) {
            return constructor.parameters.mapNotNull { parameter ->
                val rawName = parameter.name?.asString() ?: return@mapNotNull null
                val sources = annotationSources(declaration, parameter)
                val name = jsonName(sources, rawName)
                if (sources.any(::ignored) || ignoredName(name)) return@mapNotNull null
                PropertyModel(
                    name = name,
                    type = parameter.type.resolve(),
                    required = required(sources, parameter.type.resolve(), parameter),
                    annotated = sources,
                )
            }
        }
        return declaration.getDeclaredProperties().mapNotNull { property ->
            val sources = listOfNotNull(property, property.getter)
            val name = jsonName(sources, property.simpleName.asString())
            if (sources.any(::ignored) || ignoredName(name) || property.modifiers.contains(Modifier.JAVA_STATIC)) {
                return@mapNotNull null
            }
            val type = property.type.resolve()
            PropertyModel(name, type, required(sources, type, null), sources)
        }.toList()
    }

    private fun annotationSources(owner: KSClassDeclaration, parameter: KSValueParameter): List<KSAnnotated> {
        val property = owner.getDeclaredProperties().firstOrNull { it.simpleName.asString() == parameter.name?.asString() }
        return listOfNotNull(parameter, property, property?.getter)
    }

    private fun required(sources: List<KSAnnotated>, type: KSType, parameter: KSValueParameter?): Boolean {
        if (sources.any { has(it, Names.NOT_NULL) || has(it, Names.NOT_BLANK) || has(it, Names.NOT_EMPTY) }) {
            return true
        }
        if (type.nullability == Nullability.NULLABLE) {
            return false
        }
        if (parameter?.hasDefault == true) {
            return false
        }
        return type.nullability == Nullability.NOT_NULL
    }

    private fun jsonName(sources: List<KSAnnotated>, fallback: String): String =
        sources.firstNotNullOfOrNull { it.annotation(Names.JSON_PROPERTY)?.string("value") }
            ?: sources.firstNotNullOfOrNull { it.annotation(Names.TOOLS_JSON_PROPERTY)?.string("value") }
            ?: fallback

    private fun ignored(annotated: KSAnnotated): Boolean =
        has(annotated, Names.JSON_IGNORE) ||
            has(annotated, Names.TOOLS_JSON_IGNORE) ||
            annotated.annotation(Names.SWAGGER_SCHEMA)?.bool("hidden") == true

    private fun ignoredName(name: String): Boolean =
        name == "Companion" || name.startsWith("component")

    private fun has(annotated: KSAnnotated, name: String): Boolean = annotated.annotation(name) != null

    private fun applyNullability(schema: Schema, type: KSType): Schema =
        if (type.nullability == Nullability.NULLABLE) schema.nullable() else schema

    private fun applyConstraints(
        schema: Schema,
        sources: List<KSAnnotated>,
        swagger: com.google.devtools.ksp.symbol.KSAnnotation?,
    ): Schema {
        var next = applySwagger(schema, swagger)
        if (sources.isEmpty()) return next
        sources.firstNotNullOfOrNull { it.annotation(Names.SIZE) }?.let { size ->
            next = next.toBuilder()
                .minLength(size.int("min")?.takeIf { it > 0 })
                .maxLength(size.int("max")?.takeIf { it < Int.MAX_VALUE })
                .minItems(size.int("min")?.takeIf { it > 0 })
                .maxItems(size.int("max")?.takeIf { it < Int.MAX_VALUE })
                .build()
        }
        sources.firstNotNullOfOrNull { it.annotation(Names.MIN)?.int("value") }?.let {
            next = next.toBuilder().minimum(it.toBigDecimal()).build()
        }
        sources.firstNotNullOfOrNull { it.annotation(Names.MAX)?.int("value") }?.let {
            next = next.toBuilder().maximum(it.toBigDecimal()).build()
        }
        sources.firstNotNullOfOrNull { it.annotation(Names.DECIMAL_MIN)?.string("value") }?.let {
            next = next.toBuilder().minimum(BigDecimal(it)).build()
        }
        sources.firstNotNullOfOrNull { it.annotation(Names.DECIMAL_MAX)?.string("value") }?.let {
            next = next.toBuilder().maximum(BigDecimal(it)).build()
        }
        sources.firstNotNullOfOrNull { it.annotation(Names.PATTERN)?.string("regexp") }?.let {
            next = next.toBuilder().pattern(it).build()
        }
        if (sources.any { has(it, Names.EMAIL) }) {
            next = next.toBuilder().format("email").build()
        }
        if (sources.any { has(it, Names.POSITIVE) }) {
            val integer = next.types().contains("integer")
            next = next.toBuilder().minimum(if (integer) BigDecimal.ONE else BigDecimal("0.01")).build()
        }
        if (sources.any { has(it, Names.POSITIVE_OR_ZERO) }) {
            next = next.toBuilder().minimum(BigDecimal.ZERO).build()
        }
        return next
    }

    private fun applySwagger(
        schema: Schema,
        swagger: com.google.devtools.ksp.symbol.KSAnnotation?,
    ): Schema {
        swagger ?: return schema
        val builder = schema.toBuilder()
        swagger.string("description")?.let(builder::description)
        swagger.string("example")?.let(builder::example)
        swagger.string("format")?.let(builder::format)
        swagger.string("pattern")?.let(builder::pattern)
        swagger.string("defaultValue")?.let(builder::defaultValue)
        swagger.int("minLength")?.takeIf { it > 0 }?.let(builder::minLength)
        swagger.int("maxLength")?.takeIf { it > 0 && it < Int.MAX_VALUE }?.let(builder::maxLength)
        swagger.string("minimum")?.let { builder.minimum(BigDecimal(it)) }
        swagger.string("maximum")?.let { builder.maximum(BigDecimal(it)) }
        swagger.double("multipleOf")?.takeIf { it > 0 }?.let { builder.multipleOf(BigDecimal.valueOf(it)) }
        val allowable = swagger.strings("allowableValues")
        if (allowable.isNotEmpty()) {
            builder.enumValues(allowable)
        }
        return builder.build()
    }

    private fun primitive(qualified: String, type: KSType): Schema? {
        primitiveArrays(qualified)?.let { return it }
        val kind = primitiveKind(qualified) ?: return when (qualified) {
            "org.springframework.web.multipart.MultipartFile",
            "jakarta.servlet.http.Part",
            "org.springframework.core.io.Resource",
            -> Schema.builder().type("string").format("binary").build()
            else -> null
        }
        val builder = Schema.builder().type(kind)
        when (qualified) {
            "kotlin.Int", "java.lang.Integer", "int" -> builder.format("int32")
            "kotlin.Long", "java.lang.Long", "long" -> builder.format("int64")
            "kotlin.Float", "java.lang.Float", "float" -> builder.format("float")
            "kotlin.Double", "java.lang.Double", "double" -> builder.format("double")
            "java.util.UUID" -> builder.format("uuid")
            "java.net.URI", "java.net.URL" -> builder.format("uri")
            "java.time.LocalDate", "java.sql.Date" -> builder.format("date")
            "java.time.LocalTime", "java.time.OffsetTime" -> builder.format("time")
            "java.time.Instant",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.LocalDateTime",
            "java.util.Date",
            "java.sql.Timestamp",
            "kotlin.time.Instant",
            -> builder.format("date-time")
            "kotlin.ByteArray", "byte[]" -> builder.format("byte")
        }
        if (qualified == "java.time.YearMonth") {
            builder.pattern("^\\d{4}-\\d{2}$")
        }
        return builder.build()
    }

    private fun primitiveKind(qualified: String?): String? = when (qualified) {
        "kotlin.String", "java.lang.String", "kotlin.Char", "java.lang.Character", "char" -> "string"
        "kotlin.Boolean", "java.lang.Boolean", "boolean" -> "boolean"
        "kotlin.Int", "java.lang.Integer", "int",
        "kotlin.Long", "java.lang.Long", "long",
        "kotlin.Short", "java.lang.Short", "short",
        "kotlin.Byte", "java.lang.Byte", "byte",
        "java.math.BigInteger",
        -> "integer"
        "kotlin.Double", "java.lang.Double", "double",
        "kotlin.Float", "java.lang.Float", "float",
        "java.math.BigDecimal",
        "java.lang.Number",
        -> "number"
        "java.util.UUID",
        "java.net.URI",
        "java.net.URL",
        "java.time.LocalDate",
        "java.time.LocalTime",
        "java.time.LocalDateTime",
        "java.time.Instant",
        "java.time.OffsetDateTime",
        "java.time.OffsetTime",
        "java.time.ZonedDateTime",
        "java.time.Year",
        "java.time.YearMonth",
        "java.time.Duration",
        "java.time.Period",
        "java.util.Date",
        "java.sql.Date",
        "java.sql.Timestamp",
        "kotlin.time.Instant",
        "kotlin.ByteArray",
        "byte[]",
        -> "string"
        else -> null
    }

    private fun primitiveArrays(qualified: String): Schema? {
        val items = when (qualified) {
            "kotlin.IntArray" -> Schema.builder().type("integer").format("int32").build()
            "kotlin.LongArray" -> Schema.builder().type("integer").format("int64").build()
            "kotlin.ShortArray" -> Schema.builder().type("integer").build()
            "kotlin.DoubleArray" -> Schema.builder().type("number").format("double").build()
            "kotlin.FloatArray" -> Schema.builder().type("number").format("float").build()
            "kotlin.BooleanArray" -> Schema.builder().type("boolean").build()
            "kotlin.CharArray" -> Schema.builder().type("string").build()
            else -> null
        } ?: return null
        return Schema.builder().type("array").items(items).build()
    }

    private fun asCollection(type: KSType): GenericArgs? {
        if (classifierIn(type, COLLECTION_TYPES) || type.declaration.simpleName.asString().endsWith("Array") && type.arguments.isNotEmpty()) {
            return GenericArgs(item = typeArgument(type, 0))
        }
        return null
    }

    private fun asMap(type: KSType): MapArgs? {
        if (!classifierIn(type, MAP_TYPES)) return null
        val key = typeArgument(type, 0)
        val keyName = key?.declaration?.qualifiedName?.asString()
        if (key != null && keyName != "kotlin.String" && keyName != "java.lang.String" && key.declaration !is KSTypeParameter) {
            diagnostics.error(
                "RISS-TYPE",
                null,
                "${type.declaration.qualifiedName?.asString()} keys must be String",
                type.declaration,
            )
        }
        return MapArgs(value = typeArgument(type, 1))
    }

    private fun classifierIn(type: KSType, names: Set<String>): Boolean {
        val qualified = type.declaration.qualifiedName?.asString() ?: return false
        if (qualified in names) return true
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        return declaration.superTypes.any { annotation ->
            annotation.resolve().declaration.qualifiedName?.asString() in names
        }
    }

    private fun typeArgument(type: KSType, index: Int): KSType? {
        val argument = type.arguments.getOrNull(index) ?: return null
        if (argument.variance == Variance.STAR || argument.type == null) return null
        return argument.type?.resolve()
    }

    private fun isStarLike(type: KSType): Boolean {
        val name = type.declaration.simpleName.asString()
        return name == "*" || name == "<STAR>"
    }

    private fun jsonValueType(declaration: KSClassDeclaration): KSType? {
        declaration.getDeclaredProperties().forEach { property ->
            if (hasJsonValue(property) || property.getter?.let(::hasJsonValue) == true) {
                return property.type.resolve()
            }
        }
        declaration.declarations.filterIsInstance<KSFunctionDeclaration>().forEach { function ->
            if (has(function, Names.JSON_VALUE) || has(function, Names.TOOLS_JSON_VALUE)) {
                return function.returnType?.resolve()
            }
        }
        return null
    }

    private fun jsonValuePropertyName(declaration: KSClassDeclaration): String? =
        declaration.getDeclaredProperties()
            .firstOrNull { hasJsonValue(it) || it.getter?.let(::hasJsonValue) == true }
            ?.simpleName
            ?.asString()
            ?: declaration.primaryConstructor
                ?.parameters
                ?.firstOrNull(::hasJsonValue)
                ?.name
                ?.asString()

    private fun hasJsonValue(annotated: KSAnnotated): Boolean =
        has(annotated, Names.JSON_VALUE) || has(annotated, Names.TOOLS_JSON_VALUE)

    private fun componentName(declaration: KSClassDeclaration, type: KSType, bindings: Bindings): String {
        val swaggerName = swaggerSchema(declaration)?.string("name")
        val base = swaggerName ?: declaration.simpleName.asString()
        val specialized = specialize(base, declaration, type, bindings)
        val identity = declaration.qualified() + specializationKey(declaration, type, bindings)
        names[identity]?.let { return it }
        val chosen = uniqueName(specialized)
        names[identity] = chosen
        return chosen
    }

    private fun specialize(
        base: String,
        declaration: KSClassDeclaration,
        type: KSType,
        bindings: Bindings,
    ): String {
        if (declaration.typeParameters.isEmpty()) return base
        val args = declaration.typeParameters.mapIndexed { index, parameter ->
            val bound = typeArgument(type, index)?.let(bindings::resolve) ?: bindings.get(parameter.name.asString())
            typeArgName(bound, bindings)
        }
        return if (args.all { it == "Any" }) base else base + args.joinToString("") { "_$it" }
    }

    private fun specializationKey(
        declaration: KSClassDeclaration,
        type: KSType,
        bindings: Bindings,
    ): String {
        if (declaration.typeParameters.isEmpty()) return ""
        return declaration.typeParameters.mapIndexed { index, parameter ->
            val bound = typeArgument(type, index)?.let(bindings::resolve) ?: bindings.get(parameter.name.asString())
            typeArgName(bound, bindings)
        }.joinToString(",", prefix = "<", postfix = ">")
    }

    private fun typeArgName(type: KSType?, bindings: Bindings): String {
        if (type == null || type.declaration is KSTypeParameter) return "Any"
        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified in ANY_TYPES || qualified in JSON_TREES) return "Any"
        val simple = type.declaration.simpleName.asString()
        val args = type.arguments.mapNotNull { argument ->
            if (argument.variance == Variance.STAR || argument.type == null) {
                "Any"
            } else {
                typeArgName(bindings.resolve(argument.type!!.resolve()), bindings)
            }
        }
        return if (args.isEmpty()) simple else simple + args.joinToString("") { "_$it" }
    }

    private fun uniqueName(preferred: String): String {
        if (preferred !in components && preferred !in names.values) {
            return preferred
        }
        var index = 2
        var next = "${preferred}_$index"
        while (next in components || next in names.values) {
            index++
            next = "${preferred}_$index"
        }
        return next
    }

    private fun swaggerSchema(vararg annotated: KSAnnotated?): com.google.devtools.ksp.symbol.KSAnnotation? =
        annotated.filterNotNull().firstNotNullOfOrNull { source ->
            source.annotation(Names.SWAGGER_SCHEMA)
                ?: source.annotation(Names.SWAGGER_ARRAY_SCHEMA)?.annotation("schema")
        }

    private fun normalizeRef(ref: String): String =
        if (ref.startsWith("#/")) ref else "#/components/schemas/$ref"

    private fun componentRef(name: String): String = "#/components/schemas/$name"

    internal class Bindings(
        private val map: Map<String, KSType>,
    ) {
        fun resolve(type: KSType): KSType {
            val declaration = type.declaration
            if (declaration is KSTypeParameter) {
                val bound = map[declaration.name.asString()] ?: return type
                return if (type.isMarkedNullable) bound.makeNullable() else bound
            }
            return type
        }

        fun get(name: String): KSType? = map[name]

        fun enter(declaration: KSClassDeclaration, type: KSType): Bindings {
            if (declaration.typeParameters.isEmpty()) return this
            val next = LinkedHashMap(map)
            declaration.typeParameters.forEachIndexed { index, parameter ->
                val argument = type.arguments.getOrNull(index)
                val argumentType = argument?.type?.resolve()
                val resolved = when {
                    argument == null || argument.variance == Variance.STAR || argumentType == null -> null
                    argumentType.declaration is KSTypeParameter ->
                        map[(argumentType.declaration as KSTypeParameter).name.asString()]
                    else -> argumentType
                }
                if (resolved != null && resolved.declaration !is KSTypeParameter) {
                    next[parameter.name.asString()] = resolved
                } else {
                    next.remove(parameter.name.asString())
                }
            }
            return Bindings(next)
        }

        companion object {
            val EMPTY = Bindings(emptyMap())
        }
    }

    private data class PropertyModel(
        val name: String,
        val type: KSType,
        val required: Boolean,
        val annotated: List<KSAnnotated>,
    )

    private data class GenericArgs(val item: KSType?)

    private data class MapArgs(val value: KSType?)

    private companion object {
        val IGNORE_IMPLEMENTATIONS = setOf(
            "java.lang.Void",
            "void",
            "kotlin.Unit",
            "kotlin.Nothing",
        )
        val ANY_TYPES = setOf(
            "kotlin.Any",
            "java.lang.Object",
            "kotlin.Nothing",
        )
        val JSON_TREES = setOf(
            "com.fasterxml.jackson.databind.JsonNode",
            "com.fasterxml.jackson.databind.node.ObjectNode",
            "com.fasterxml.jackson.databind.node.ArrayNode",
            "tools.jackson.databind.JsonNode",
            "tools.jackson.databind.node.ObjectNode",
            "tools.jackson.databind.node.ArrayNode",
        )
        val SKIP_SUPERTYPES = setOf(
            "kotlin.Any",
            "java.lang.Object",
            "java.lang.Record",
            "java.lang.Enum",
            "kotlin.Enum",
        )
        val WRAPPERS = setOf(
            "org.springframework.http.ResponseEntity",
            "org.springframework.http.HttpEntity",
            "org.springframework.http.RequestEntity",
            "java.util.Optional",
            "kotlin.Result",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.CompletionStage",
            "reactor.core.publisher.Mono",
        )
        val COLLECTION_TYPES = setOf(
            "kotlin.collections.List",
            "kotlin.collections.MutableList",
            "kotlin.collections.Set",
            "kotlin.collections.MutableSet",
            "kotlin.collections.Collection",
            "kotlin.collections.MutableCollection",
            "kotlin.collections.Iterable",
            "kotlin.Array",
            "java.util.List",
            "java.util.Set",
            "java.util.Collection",
            "java.lang.Iterable",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.NavigableSet",
            "java.util.SortedSet",
        )
        val MAP_TYPES = setOf(
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.NavigableMap",
            "java.util.SortedMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentMap",
        )
    }
}
