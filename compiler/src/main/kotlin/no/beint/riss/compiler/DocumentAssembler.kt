package no.beint.riss.compiler

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import no.beint.riss.model.Contact
import no.beint.riss.model.Info
import no.beint.riss.model.MediaType
import no.beint.riss.model.OpenApi
import no.beint.riss.model.Parameter
import no.beint.riss.model.PathItem
import no.beint.riss.model.Response
import no.beint.riss.model.Schema
import no.beint.riss.model.SecurityRequirement
import no.beint.riss.model.SecurityScheme
import no.beint.riss.model.Server
import no.beint.riss.model.Tag

internal data class AssembledDocument(
    val document: OpenApi,
    val files: List<KSFile>,
    val specName: String,
)

internal class DocumentAssembler(
    private val resolver: Resolver,
    private val enumReader: EnumReader,
    private val schemas: SchemaFactory,
    private val diagnostics: Diagnostics,
    private val options: RissOptions,
) {
    fun assemble(operations: List<ScannedOperation>): AssembledDocument {
        val documentType = resolver.getSymbolsWithAnnotation(Names.RISS_DOCUMENT)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (documentType.size > 1) {
            diagnostics.error(
                "RISS-DOCUMENT",
                "multiple @RissDocument types: ${documentType.joinToString { it.qualified() }}",
            )
        }
        val declared = documentType.singleOrNull()
        val documentAnnotation = declared?.annotation(Names.RISS_DOCUMENT)
        val specName = documentAnnotation?.string("name") ?: options.specName
        val info = info(declared, documentAnnotation)
        val paths = linkedMapOf<String, PathItem>()
        val tags = linkedMapOf<String, Tag>()
        operations.sortedWith(compareBy({ it.path }, { it.method })).forEach { scanned ->
            applyGlobals(scanned, declared)
            val existing = paths[scanned.path]
            paths[scanned.path] = existing?.with(scanned.method, scanned.operation)
                ?: PathItem(mapOf(scanned.method.lowercase() to scanned.operation))
            scanned.operation.tags().forEach { name ->
                tags.putIfAbsent(name, Tag(name, tagDescription(declared, scanned.controller, name)))
            }
        }
        addNamedSchemas(declared)
        validateRefs(paths)
        val builder = OpenApi.builder(info)
        paths.forEach(builder::path)
        tags.values.forEach(builder::tag)
        schemas.components().forEach(builder::schema)
        securitySchemes(declared).forEach { (name, scheme) -> builder.securityScheme(name, scheme) }
        securityRequirements(declared, documentAnnotation).forEach(builder::security)
        servers(declared).forEach(builder::server)
        val files = (operations.mapNotNull { it.file } + listOfNotNull(declared?.containingFile)).distinct()
        return AssembledDocument(builder.build(), files, specName)
    }

    private fun info(declared: KSClassDeclaration?, annotation: KSAnnotation?): Info {
        val swagger = declared?.annotation(Names.SWAGGER_OPENAPI)?.annotation("info")
        val title = annotation?.string("title")
            ?: swagger?.string("title")
            ?: options.title
            ?: "API"
        val version = annotation?.string("version")
            ?: swagger?.string("version")
            ?: options.version
        val description = annotation?.string("description") ?: swagger?.string("description")
        val contact = Contact.of(
            annotation?.string("contactName") ?: swagger?.annotation("contact")?.string("name"),
            annotation?.string("contactEmail") ?: swagger?.annotation("contact")?.string("email"),
            annotation?.string("contactUrl") ?: swagger?.annotation("contact")?.string("url"),
        )
        return Info(title, version, description, contact)
    }

    private fun applyGlobals(scanned: ScannedOperation, declared: KSClassDeclaration?) {
        var operation = scanned.operation
        headers(declared).forEach { header ->
            if (header.appliesTo(scanned.controller) && operation.parameters().none { it.name() == header.name && it.locatedIn() == "header" }) {
                operation = operation.withParameters(operation.parameters() + header.parameter())
            }
        }
        val responses = LinkedHashMap(operation.responses())
        defaultResponses(declared).groupBy { it.code }.forEach { (code, extras) ->
            if (!responses.containsKey(code)) {
                responses[code] = mergeResponses(extras)
            }
        }
        scanned.operation = operation.withResponses(responses)
    }

    private fun servers(declared: KSClassDeclaration?): List<Server> =
        declared?.annotationsNamed(Names.RISS_SERVER).orEmpty().mapNotNull { annotation ->
            annotation.string("url")?.let { url -> Server(url, annotation.string("description")) }
        }

    private fun addNamedSchemas(declared: KSClassDeclaration?) {
        declared ?: return
        declared.annotationsNamed(Names.RISS_STRING_SCHEMA).forEach { annotation ->
            val name = annotation.string("name") ?: return@forEach
            val builder = Schema.builder()
                .type("string")
                .description(annotation.string("description"))
                .pattern(annotation.string("pattern"))
                .example(annotation.string("example"))
            annotation.int("minLength")?.takeIf { it >= 0 }?.let(builder::minLength)
            annotation.int("maxLength")?.takeIf { it >= 0 }?.let(builder::maxLength)
            val explicit = annotation.strings("enumValues")
            val enumType = annotation.type("enumFrom")
            val values = if (explicit.isNotEmpty()) {
                explicit
            } else if (enumType != null && enumType.declaration.qualifiedName?.asString() != "java.lang.Void" &&
                enumType.declaration.qualifiedName?.asString() != "kotlin.Unit"
            ) {
                val enumClass = enumType.declaration as? KSClassDeclaration
                if (enumClass == null || enumClass.classKind != ClassKind.ENUM_CLASS) {
                    diagnostics.error("RISS-ENUM", "enumFrom for $name is not an enum")
                    emptyList()
                } else {
                    enumReader.values(
                        enumClass,
                        annotation.string("enumProperty"),
                        annotation.string("enumWhereProperty"),
                        annotation.string("enumWhereValue"),
                        name,
                    )
                }
            } else {
                emptyList()
            }
            if (values.isNotEmpty()) {
                builder.enumValues(values)
            }
            schemas.named(name, builder.build())
        }
        declared.annotationsNamed(Names.RISS_OBJECT_SCHEMA).forEach { annotation ->
            val name = annotation.string("name") ?: return@forEach
            val builder = Schema.builder()
                .type("object")
                .description(annotation.string("description"))
            annotation.annotations("properties").forEach { property ->
                val propertyName = property.string("name") ?: return@forEach
                val ref = property.string("ref")
                val type = property.string("type") ?: "string"
                val schema = if (ref != null) {
                    val target = Schema.ref(if (ref.startsWith("#/")) ref else "#/components/schemas/$ref")
                    if (type == "array") {
                        Schema.builder().type("array").items(target).build()
                    } else {
                        target
                    }
                } else {
                    val built = Schema.builder()
                        .type(type)
                        .format(property.string("format"))
                        .description(property.string("description"))
                        .example(property.string("example"))
                        .build()
                    if (property.bool("nullable") == true) built.nullable() else built
                }
                builder.property(propertyName, schema)
            }
            schemas.named(name, builder.build())
        }
    }

    private fun securitySchemes(declared: KSClassDeclaration?): Map<String, SecurityScheme> {
        val schemes = linkedMapOf<String, SecurityScheme>()
        declared?.annotationsNamed(Names.RISS_SECURITY_SCHEME)?.forEach { annotation ->
            val name = annotation.string("name") ?: return@forEach
            val header = annotation.string("headerName")
            schemes[name] = if (header != null) {
                SecurityScheme("apiKey", annotation.string("description"), null, null, header, "header")
            } else {
                SecurityScheme(
                    annotation.string("type") ?: "http",
                    annotation.string("description"),
                    annotation.string("scheme") ?: "bearer",
                    annotation.string("bearerFormat"),
                    null,
                    null,
                )
            }
        }
        declared?.annotationsNamed(Names.SWAGGER_SECURITY_SCHEME)?.forEach { annotation ->
            val name = annotation.string("name") ?: return@forEach
            schemes.putIfAbsent(
                name,
                SecurityScheme(
                    swaggerSchemeType(annotation),
                    annotation.string("description"),
                    annotation.string("scheme"),
                    annotation.string("bearerFormat"),
                    annotation.string("paramName"),
                    annotation.enumName("in")?.lowercase(),
                ),
            )
        }
        return schemes
    }

    private fun securityRequirements(
        declared: KSClassDeclaration?,
        annotation: KSAnnotation?,
    ): List<SecurityRequirement> {
        val names = annotation?.strings("security").orEmpty()
        if (names.isNotEmpty()) {
            return names.map(SecurityRequirement::of)
        }
        val swagger = declared?.annotation(Names.SWAGGER_OPENAPI)?.annotations("security").orEmpty()
        if (swagger.isNotEmpty()) {
            return swagger.mapNotNull { requirement ->
                requirement.string("name")?.let(SecurityRequirement::of)
            }
        }
        return emptyList()
    }

    private fun headers(declared: KSClassDeclaration?): List<GlobalHeader> =
        declared?.annotationsNamed(Names.RISS_GLOBAL_HEADER).orEmpty().map { annotation ->
            GlobalHeader(
                name = annotation.string("name") ?: "header",
                description = annotation.string("description"),
                type = annotation.string("type") ?: "string",
                format = annotation.string("format"),
                required = annotation.bool("required") == true,
                skipTypes = annotation.types("skipTypes").mapNotNull { it.declaration.qualifiedName?.asString() }.toSet(),
                onlyTypes = annotation.types("onlyTypes").mapNotNull { it.declaration.qualifiedName?.asString() }.toSet(),
                skipPackages = annotation.strings("skipPackages").toSet(),
            )
        }

    private fun defaultResponses(declared: KSClassDeclaration?): List<DefaultResponse> =
        declared?.annotationsNamed(Names.RISS_DEFAULT_RESPONSE).orEmpty().map { annotation ->
            DefaultResponse(
                code = annotation.string("code") ?: "500",
                description = annotation.string("description") ?: "Error",
                contentType = annotation.string("contentType"),
                schemaRef = annotation.string("schemaRef"),
            )
        }

    private fun tagDescription(
        declared: KSClassDeclaration?,
        controller: KSClassDeclaration,
        name: String,
    ): String? =
        declared?.annotationsNamed(Names.RISS_TAG)?.firstOrNull { it.string("name") == name }?.string("description")
            ?: controller.annotationsNamed(Names.SWAGGER_TAG)
                .firstOrNull { it.string("name") == name }
                ?.string("description")

    private fun swaggerSchemeType(annotation: KSAnnotation): String =
        when (annotation.enumName("type")) {
            "HTTP" -> "http"
            "APIKEY" -> "apiKey"
            "OAUTH2" -> "oauth2"
            "OPENIDCONNECT" -> "openIdConnect"
            else -> annotation.string("type") ?: "http"
        }

    private fun validateRefs(paths: Map<String, PathItem>) {
        val known = schemas.components().keys
        fun walk(schema: Schema?, location: String) {
            schema ?: return
            schema.ref()?.removePrefix("#/components/schemas/")?.let { name ->
                if (name !in known) {
                    diagnostics.error("RISS-REF", location, "unknown schema $name")
                }
            }
            schema.properties().forEach { (name, property) -> walk(property, "$location.$name") }
            schema.items()?.let { walk(it, "$location[]") }
            schema.additionalProperties()?.let { walk(it, "$location.*") }
            schema.oneOf().forEach { walk(it, location) }
            schema.anyOf().forEach { walk(it, location) }
        }
        schemas.components().forEach { (name, schema) -> walk(schema, name) }
        paths.forEach { (path, item) ->
            item.operations().forEach { (method, operation) ->
                val here = "${method.uppercase()} $path"
                operation.parameters().forEach { parameter ->
                    walk(parameter.schema(), "$here ${parameter.name()}")
                }
                operation.requestBody()?.content()?.forEach { (mediaType, media) ->
                    walk(media.schema(), "$here $mediaType")
                }
                operation.responses().forEach { (code, response) ->
                    response.content().forEach { (mediaType, media) ->
                        walk(media.schema(), "$here $code $mediaType")
                    }
                }
            }
        }
    }

    private data class GlobalHeader(
        val name: String,
        val description: String?,
        val type: String,
        val format: String?,
        val required: Boolean,
        val skipTypes: Set<String>,
        val onlyTypes: Set<String>,
        val skipPackages: Set<String>,
    ) {
        fun appliesTo(controller: KSClassDeclaration): Boolean {
            val qualified = controller.qualified()
            if (onlyTypes.isNotEmpty() && qualified !in onlyTypes) return false
            if (qualified in skipTypes) return false
            val pkg = controller.packageNameString()
            return skipPackages.none { pkg == it || pkg.startsWith("$it.") }
        }

        fun parameter(): Parameter {
            val schema = Schema.builder().type(type).format(format).build()
            return Parameter(name, "header", description, required, schema, null, null, null)
        }
    }

    private data class DefaultResponse(
        val code: String,
        val description: String,
        val contentType: String?,
        val schemaRef: String?,
    ) {
        fun media(): Pair<String, MediaType>? {
            if (schemaRef.isNullOrBlank()) return null
            val mediaType = contentType ?: "application/problem+json"
            val ref = if (schemaRef.startsWith("#/")) schemaRef else "#/components/schemas/$schemaRef"
            return mediaType to MediaType(Schema.ref(ref))
        }
    }

    private fun mergeResponses(extras: List<DefaultResponse>): Response {
        val description = extras.first().description
        val content = linkedMapOf<String, MediaType>()
        extras.forEach { extra -> extra.media()?.let { content.putIfAbsent(it.first, it.second) } }
        return Response(description, content)
    }
}
