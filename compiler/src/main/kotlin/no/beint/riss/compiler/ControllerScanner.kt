package no.beint.riss.compiler

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import no.beint.riss.model.MediaType
import no.beint.riss.model.Operation
import no.beint.riss.model.Parameter
import no.beint.riss.model.RequestBody
import no.beint.riss.model.Response
import no.beint.riss.model.Schema

internal data class ScannedOperation(
    val method: String,
    val path: String,
    var operation: Operation,
    val controller: KSClassDeclaration,
    val function: KSFunctionDeclaration,
    val file: KSFile?,
)

internal class ControllerScanner(
    private val schemas: SchemaFactory,
    private val diagnostics: Diagnostics,
    private val scanPackages: List<String>,
    private val includePaths: List<String>,
    private val excludePaths: List<String>,
) {
    fun scan(functions: Sequence<KSFunctionDeclaration>): List<ScannedOperation> {
        val usedIds = mutableSetOf<String>()
        val operations = mutableListOf<ScannedOperation>()
        functions.forEach { function ->
            val controller = function.parentDeclaration as? KSClassDeclaration ?: return@forEach
            if (!includedPackage(controller.packageNameString())) return@forEach
            if (ignored(controller) || ignored(function)) return@forEach
            val mapping = mapping(function) ?: return@forEach
            val prefixes = classPaths(controller)
            val paths = mapping.paths.ifEmpty { listOf("") }
            prefixes.forEach { prefix ->
                paths.forEach { path ->
                    val pattern = PathPatterns.combine(prefix, path)
                    if (!PathPatterns.included(pattern, includePaths, excludePaths)) return@forEach
                    mapping.methods.forEach { method ->
                        val operation = operation(controller, function, mapping, method, pattern, usedIds)
                        operations += ScannedOperation(
                            method = method,
                            path = pattern,
                            operation = operation,
                            controller = controller,
                            function = function,
                            file = function.containingFile ?: controller.containingFile,
                        )
                    }
                }
            }
        }
        return mergeDuplicates(operations)
    }

    private fun operation(
        controller: KSClassDeclaration,
        function: KSFunctionDeclaration,
        mapping: Mapping,
        method: String,
        path: String,
        usedIds: MutableSet<String>,
    ): Operation {
        val swagger = function.annotation(Names.SWAGGER_OPERATION)
        val location = "${controller.qualified()}.${function.simpleName.asString()}"
        val id = operationId(controller, function, swagger?.string("operationId"), usedIds)
        val builder = Operation.builder(id)
            .summary(swagger?.string("summary") ?: humanize(function.simpleName.asString()))
            .description(swagger?.string("description"))
            .deprecated(deprecated(function, swagger))
        tags(controller, function).forEach(builder::tag)
        val pathVariables = pathVariableNames(path)
        val seenPath = mutableSetOf<String>()
        function.parameters.forEach { parameter ->
            when {
                ignoredParameter(parameter) || hiddenParameter(parameter) -> {}
                parameter.annotation(Names.PATH_VARIABLE) != null -> {
                    val parsed = pathParameter(parameter, pathVariables, location)
                    if (parsed != null) {
                        builder.parameter(parsed)
                        seenPath += parsed.name
                    }
                }
                parameterObject(parameter) -> flattenQuery(parameter, location).forEach(builder::parameter)
                parameter.annotation(Names.REQUEST_PARAM) != null ||
                    parameter.annotation(Names.REQUEST_PART) != null -> {
                    if (!isFile(parameter.type.resolve())) {
                        builder.parameter(queryOrPartParameter(parameter, location))
                    }
                }
                parameter.annotation(Names.REQUEST_HEADER) != null ->
                    builder.parameter(headerParameter(parameter, location))
            }
        }
        pathVariables.filterNot(seenPath::contains).forEach { name ->
            diagnostics.error("RISS-PATH", location, "path variable {$name} has no @PathVariable", function)
        }
        requestBody(function, mapping, method, location)?.let(builder::requestBody)
        responses(function, mapping, location).forEach { (code, response) -> builder.response(code, response) }
        return builder.build()
    }

    private fun requestBody(
        function: KSFunctionDeclaration,
        mapping: Mapping,
        method: String,
        location: String,
    ): RequestBody? {
        val files = function.parameters.filter { parameter ->
            (parameter.annotation(Names.REQUEST_PARAM) != null || parameter.annotation(Names.REQUEST_PART) != null) &&
                isFile(parameter.type.resolve())
        }
        if (files.isNotEmpty()) {
            val objectSchema = Schema.builder().type("object")
            files.forEach { parameter ->
                val name = parameterName(parameter, Names.REQUEST_PARAM)
                    ?: parameterName(parameter, Names.REQUEST_PART)
                    ?: parameter.name?.asString()
                    ?: "file"
                val type = parameter.type.resolve()
                val schema = schemas.schema(type, "$location.$name", parameter)
                objectSchema.property(name, schema)
                if (type.nullability != Nullability.NULLABLE && parameter.annotation(Names.REQUEST_PARAM)?.bool("required") != false) {
                    objectSchema.require(name)
                }
            }
            return RequestBody(
                null,
                true,
                mapOf(
                    (mapping.consumes.singleOrNull() ?: "multipart/form-data") to MediaType(objectSchema.build()),
                ),
            )
        }
        val body = function.parameters.firstOrNull { it.annotation(Names.REQUEST_BODY) != null } ?: return null
        val type = body.type.resolve()
        val swaggerBody = body.annotation(Names.SWAGGER_REQUEST_BODY)
        val content = swaggerBody?.annotations("content")?.firstOrNull()
        val implementation = content?.annotation("schema")?.type("implementation")
        val implementationName = implementation?.declaration?.qualifiedName?.asString()
        val schema = if (implementation != null && implementationName !in IGNORE_IMPLEMENTATIONS) {
            schemas.schema(implementation, location, body, usage = SchemaUsage.ROOT)
        } else {
            schemas.schema(type, location, body, usage = SchemaUsage.ROOT)
        }
        val mediaTypes = listOfNotNull(content?.string("mediaType"))
            .ifEmpty { mapping.consumes }
            .ifEmpty { listOf("application/json") }
        val example = content?.annotations("examples")?.firstOrNull()?.string("value")
        val required = swaggerBody?.bool("required")
            ?: body.annotation(Names.REQUEST_BODY)?.bool("required")
            ?: (type.nullability != Nullability.NULLABLE)
        return RequestBody(
            swaggerBody?.string("description"),
            required,
            mediaTypes.associateWith { MediaType(schema, example) },
        )
    }

    private fun responses(
        function: KSFunctionDeclaration,
        mapping: Mapping,
        location: String,
    ): Map<String, Response> {
        val declared = swaggerResponses(function)
        val result = linkedMapOf<String, Response>()
        declared.forEach { annotation ->
            val code = annotation.string("responseCode") ?: "200"
            val description = annotation.string("description") ?: defaultDescription(code)
            val contents = annotation.annotations("content")
            if (contents.isEmpty()) {
                val success = if (code.startsWith("2")) successResponse(function, mapping, location) else Response.of(description)
                result[code] = if (code.startsWith("2") && success.content().isNotEmpty()) {
                    Response(description, success.content())
                } else {
                    Response.of(description)
                }
            } else {
                val media = linkedMapOf<String, MediaType>()
                contents.forEach { content ->
                    val mediaType = content.string("mediaType") ?: mapping.produces.singleOrNull() ?: "application/json"
                    val schemaAnnotation = content.annotation("schema")
                    val implementation = schemaAnnotation?.type("implementation")
                    val implementationName = implementation?.declaration?.qualifiedName?.asString()
                    val schema = schemaAnnotation?.string("ref")?.let { Schema.ref(normalizeRef(it)) }
                        ?: implementation?.takeIf { implementationName !in IGNORE_IMPLEMENTATIONS }
                            ?.let { schemas.schema(it, location, usage = SchemaUsage.ROOT) }
                        ?: successSchema(function, location)
                    media[mediaType] = MediaType(schema)
                }
                result[code] = Response(description, media)
            }
        }
        if (result.keys.none { it.startsWith("2") }) {
            result["200"] = successResponse(function, mapping, location)
        }
        return result
    }

    private fun successResponse(
        function: KSFunctionDeclaration,
        mapping: Mapping,
        location: String,
    ): Response {
        val type = schemas.unwrap(function.returnType?.resolve() ?: return Response.of("OK"))
        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified == null || qualified in VOID_TYPES) {
            return Response.of("OK")
        }
        val media = mapping.produces.singleOrNull() ?: "application/json"
        return Response("OK", mapOf(media to MediaType(schemas.schema(type, location, usage = SchemaUsage.ROOT))))
    }

    private fun successSchema(function: KSFunctionDeclaration, location: String): Schema {
        val type = schemas.unwrap(function.returnType?.resolve() ?: return Schema.builder().type("object").build())
        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified == null || qualified in VOID_TYPES) {
            return Schema.builder().type("object").build()
        }
        return schemas.schema(type, location, usage = SchemaUsage.ROOT)
    }

    private fun swaggerResponses(function: KSFunctionDeclaration): List<KSAnnotation> {
        val nested = function.annotation(Names.SWAGGER_API_RESPONSES)?.annotations("value").orEmpty()
        val direct = function.annotationsNamed(Names.SWAGGER_API_RESPONSE)
        val operation = function.annotation(Names.SWAGGER_OPERATION)?.annotations("responses").orEmpty()
        return (direct + nested + operation).distinctBy { it.string("responseCode") }
    }

    private fun pathParameter(
        parameter: KSValueParameter,
        pathVariables: Set<String>,
        location: String,
    ): Parameter? {
        val name = parameterName(parameter, Names.PATH_VARIABLE) ?: parameter.name?.asString() ?: return null
        if (name !in pathVariables) {
            diagnostics.error("RISS-PATH", location, "@PathVariable $name is not in the path", parameter)
        }
        val type = parameter.type.resolve()
        val swagger = parameter.annotation(Names.SWAGGER_PARAMETER)
        return Parameter(
            name,
            "path",
            swagger?.string("description"),
            true,
            schemas.schema(type, "$location.$name", parameter),
            style(swagger),
            explode(swagger),
            swagger?.string("example"),
        )
    }

    private fun queryOrPartParameter(parameter: KSValueParameter, location: String): Parameter {
        val annotation = parameter.annotation(Names.REQUEST_PARAM) ?: parameter.annotation(Names.REQUEST_PART)
        val name = parameterName(parameter, Names.REQUEST_PARAM)
            ?: parameterName(parameter, Names.REQUEST_PART)
            ?: parameter.name?.asString()
            ?: "param"
        val type = parameter.type.resolve()
        val swagger = parameter.annotation(Names.SWAGGER_PARAMETER)
        val required = annotation?.bool("required")
            ?: (type.nullability != Nullability.NULLABLE && !parameter.hasDefault)
        return Parameter(
            name,
            "query",
            swagger?.string("description"),
            required,
            schemas.schema(type, "$location.$name", parameter),
            style(swagger),
            explode(swagger),
            swagger?.string("example") ?: annotation?.string("defaultValue"),
        )
    }

    private fun headerParameter(parameter: KSValueParameter, location: String): Parameter {
        val name = parameterName(parameter, Names.REQUEST_HEADER)
            ?: parameter.name?.asString()
            ?: "header"
        val type = parameter.type.resolve()
        val swagger = parameter.annotation(Names.SWAGGER_PARAMETER)
        val required = parameter.annotation(Names.REQUEST_HEADER)?.bool("required")
            ?: (type.nullability != Nullability.NULLABLE)
        return Parameter.header(name, schemas.schema(type, "$location.$name", parameter), required)
            .withDescription(swagger?.string("description") ?: "")
    }

    private fun flattenQuery(parameter: KSValueParameter, location: String): List<Parameter> {
        val type = parameter.type.resolve()
        val declaration = type.declaration as? KSClassDeclaration ?: run {
            diagnostics.error("RISS-PARAM", location, "@ParameterObject must be a class", parameter)
            return emptyList()
        }
        val constructor = declaration.primaryConstructor
        val source: List<Pair<KSAnnotated, KSType>> =
            if (constructor != null && constructor.parameters.isNotEmpty()) {
                constructor.parameters.mapNotNull { value ->
                    value.name ?: return@mapNotNull null
                    value to value.type.resolve()
                }
            } else {
                declaration.getDeclaredProperties().map { property ->
                    property to property.type.resolve()
                }.toList()
            }
        return source.mapNotNull { (annotated, propertyType) ->
            val name = when (annotated) {
                is KSValueParameter ->
                    annotated.annotation(Names.JSON_PROPERTY)?.string("value")
                        ?: annotated.name?.asString()
                is KSPropertyDeclaration ->
                    annotated.annotation(Names.JSON_PROPERTY)?.string("value")
                        ?: annotated.simpleName.asString()
                else -> null
            } ?: return@mapNotNull null
            if (name == "Companion") return@mapNotNull null
            val sources = listOfNotNull(
                annotated,
                (annotated as? KSValueParameter)?.let { value ->
                    declaration.getDeclaredProperties()
                        .firstOrNull { it.simpleName.asString() == value.name?.asString() }
                },
            )
            val swagger = sources.firstNotNullOfOrNull { it.annotation(Names.SWAGGER_SCHEMA) }
            val required = when (annotated) {
                is KSValueParameter ->
                    propertyType.nullability != Nullability.NULLABLE && !annotated.hasDefault
                else -> propertyType.nullability != Nullability.NULLABLE
            }
            Parameter(
                name,
                "query",
                swagger?.string("description"),
                required,
                schemas.schema(propertyType, "$location.$name", *sources.toTypedArray()),
                null,
                null,
                swagger?.string("example"),
            )
        }
    }

    private fun mapping(function: KSFunctionDeclaration): Mapping? {
        Names.mappings.forEach { (annotationName, methods) ->
            val annotation = function.annotation(annotationName) ?: return@forEach
            val resolvedMethods = methods.ifEmpty { requestMethods(annotation) }.ifEmpty { setOf("GET") }
            return Mapping(
                methods = resolvedMethods,
                paths = paths(annotation),
                consumes = annotation.strings("consumes"),
                produces = annotation.strings("produces"),
            )
        }
        return null
    }

    private fun classPaths(controller: KSClassDeclaration): List<String> {
        val mapping = controller.annotation(Names.REQUEST_MAPPING) ?: return listOf("")
        return paths(mapping).ifEmpty { listOf("") }
    }

    private fun paths(annotation: KSAnnotation): List<String> {
        val values = annotation.strings("value") + annotation.strings("path")
        return values.distinct().ifEmpty { listOf("") }
    }

    private fun requestMethods(annotation: KSAnnotation): Set<String> =
        annotation.arguments
            .firstOrNull { it.name?.asString() == "method" }
            ?.let { value ->
                when (val raw = value.value) {
                    is List<*> -> raw.mapNotNull(::enumName)
                    else -> listOfNotNull(enumName(raw))
                }
            }
            ?.toSet()
            .orEmpty()

    private fun tags(controller: KSClassDeclaration, function: KSFunctionDeclaration): List<String> {
        val fromFunction = function.annotationsNamed(Names.SWAGGER_TAG).mapNotNull { it.string("name") }
        val fromClass = controller.annotationsNamed(Names.SWAGGER_TAG).mapNotNull { it.string("name") }
        val names = (fromFunction + fromClass).distinct()
        return names.ifEmpty { listOf(humanize(controller.simpleName.asString().removeSuffix("Api"))) }
    }

    private fun hiddenParameter(parameter: KSValueParameter): Boolean =
        parameter.annotation(Names.SWAGGER_PARAMETER)?.bool("hidden") == true ||
            parameter.name?.asString() in HIDDEN_PARAMETER_NAMES

    private fun operationId(
        controller: KSClassDeclaration,
        function: KSFunctionDeclaration,
        explicit: String?,
        used: MutableSet<String>,
    ): String {
        val method = function.simpleName.asString()
        val simple = explicit ?: method
        val qualified = controller.simpleName.asString().replaceFirstChar(Char::lowercaseChar) + "_" + method
        val candidate = when {
            simple !in used -> simple
            qualified !in used -> qualified
            else -> {
                var index = 2
                var next = "${qualified}_$index"
                while (next in used) {
                    index++
                    next = "${qualified}_$index"
                }
                next
            }
        }
        used += candidate
        return candidate
    }

    private fun mergeDuplicates(operations: List<ScannedOperation>): List<ScannedOperation> {
        val merged = mutableListOf<ScannedOperation>()
        operations.groupBy { it.method to it.path }.forEach { (_, group) ->
            if (group.size == 1) {
                merged += group[0]
                return@forEach
            }
            var operation = group.first().operation
            group.drop(1).forEach { next ->
                operation = mergeOperations(operation, next.operation)
            }
            merged += group.first().copy(operation = operation)
        }
        return merged
    }

    private fun mergeOperations(left: Operation, right: Operation): Operation {
        val parameters = (left.parameters() + right.parameters()).distinctBy { it.name() + ":" + it.locatedIn() }
        val contents = LinkedHashMap(left.requestBody()?.content() ?: emptyMap())
        right.requestBody()?.content()?.forEach(contents::putIfAbsent)
        val requestBody = when {
            contents.isEmpty() -> left.requestBody() ?: right.requestBody()
            else -> RequestBody(
                listOfNotNull(left.requestBody()?.description(), right.requestBody()?.description())
                    .distinct()
                    .joinToString("\n")
                    .ifBlank { null },
                (left.requestBody()?.required() ?: false) || (right.requestBody()?.required() ?: false),
                contents,
            )
        }
        val responses = LinkedHashMap(left.responses())
        right.responses().forEach { (code, response) -> responses.putIfAbsent(code, response) }
        val summary = listOfNotNull(left.summary(), right.summary()).distinct().joinToString(" / ")
        val description = listOfNotNull(left.description(), right.description()).distinct().joinToString("\n\n")
        return Operation(
            left.operationId(),
            summary,
            description,
            (left.tags() + right.tags()).distinct(),
            parameters,
            requestBody,
            responses,
            left.security().ifEmpty { right.security() },
            left.deprecated() || right.deprecated(),
        )
    }

    private fun includedPackage(packageName: String): Boolean =
        scanPackages.isEmpty() || scanPackages.any { packageName == it || packageName.startsWith("$it.") }

    private fun deprecated(function: KSFunctionDeclaration, swagger: KSAnnotation?): Boolean {
        if (swagger?.bool("deprecated") == true) return true
        return function.annotations.any { annotation ->
            val name = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
            name == "kotlin.Deprecated" || name == "java.lang.Deprecated"
        }
    }

    private fun ignored(annotated: KSAnnotated): Boolean =
        annotated.annotation(Names.RISS_IGNORE) != null ||
            annotated.annotation(Names.SWAGGER_HIDDEN) != null ||
            annotated.annotation(Names.SWAGGER_OPERATION)?.bool("hidden") == true

    private fun ignoredParameter(parameter: KSValueParameter): Boolean {
        val qualified = parameter.type.resolve().declaration.qualifiedName?.asString() ?: return false
        return qualified in IGNORED_PARAMETERS
    }

    private fun parameterObject(parameter: KSValueParameter): Boolean =
        parameter.annotation(Names.RISS_PARAMETER_OBJECT) != null ||
            parameter.annotation(Names.SPRINGDOC_PARAMETER_OBJECT) != null ||
            parameter.annotation(Names.MODEL_ATTRIBUTE) != null

    private fun parameterName(parameter: KSValueParameter, annotationName: String): String? {
        val annotation = parameter.annotation(annotationName) ?: return null
        return annotation.string("name") ?: annotation.string("value")
    }

    private fun isFile(type: KSType): Boolean {
        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified == "org.springframework.web.multipart.MultipartFile") return true
        if (qualified in setOf("kotlin.collections.List", "java.util.List")) {
            val item = type.arguments.firstOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString()
            return item == "org.springframework.web.multipart.MultipartFile"
        }
        return false
    }

    private fun pathVariableNames(path: String): Set<String> =
        Regex("""\{([^}?*:]+)[^}]*}""")
            .findAll(path)
            .map { it.groupValues[1] }
            .toSet()

    private fun style(swagger: KSAnnotation?): String? =
        swagger?.enumName("style")?.lowercase()?.takeIf { it != "default" }

    private fun explode(swagger: KSAnnotation?): Boolean? =
        when (swagger?.enumName("explode")) {
            "TRUE" -> true
            "FALSE" -> false
            else -> null
        }

    private fun humanize(name: String): String =
        name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar(Char::uppercaseChar)

    private fun defaultDescription(code: String): String = when (code) {
        "200" -> "OK"
        "201" -> "Created"
        "204" -> "No content"
        "400" -> "Bad request"
        "401" -> "Unauthorized"
        "403" -> "Forbidden"
        "404" -> "Not found"
        "409" -> "Conflict"
        "500" -> "Internal server error"
        else -> code
    }

    private fun normalizeRef(ref: String): String =
        if (ref.startsWith("#/")) ref else "#/components/schemas/$ref"

    private data class Mapping(
        val methods: Set<String>,
        val paths: List<String>,
        val consumes: List<String>,
        val produces: List<String>,
    )

    private companion object {
        val VOID_TYPES = setOf(
            "kotlin.Unit",
            "java.lang.Void",
            "void",
        )
        val IGNORE_IMPLEMENTATIONS = setOf(
            "java.lang.Void",
            "void",
            "kotlin.Unit",
            "kotlin.Nothing",
        )
        val IGNORED_PARAMETERS = setOf(
            "jakarta.servlet.http.HttpServletRequest",
            "jakarta.servlet.http.HttpServletResponse",
            "jakarta.servlet.ServletRequest",
            "jakarta.servlet.ServletResponse",
            "org.springframework.web.context.request.WebRequest",
            "org.springframework.web.context.request.NativeWebRequest",
            "org.springframework.validation.BindingResult",
            "org.springframework.validation.Errors",
            "org.springframework.ui.Model",
            "org.springframework.ui.ModelMap",
            "java.security.Principal",
            "org.springframework.security.core.Authentication",
            "java.util.Locale",
            "java.util.TimeZone",
            "java.io.InputStream",
            "java.io.OutputStream",
            "kotlin.coroutines.Continuation",
            "jakarta.servlet.http.HttpSession",
            "org.springframework.web.bind.support.SessionStatus",
        )
        val HIDDEN_PARAMETER_NAMES = setOf("authentication", "session", "principal")
    }
}
