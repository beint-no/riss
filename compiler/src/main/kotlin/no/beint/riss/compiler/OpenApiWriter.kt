package no.beint.riss.compiler

import no.beint.riss.model.MediaType
import no.beint.riss.model.OpenApi
import no.beint.riss.model.Operation
import no.beint.riss.model.Parameter
import no.beint.riss.model.RequestBody
import no.beint.riss.model.Response
import no.beint.riss.model.Schema
import no.beint.riss.model.SecurityRequirement
import no.beint.riss.model.SecurityScheme
import no.beint.riss.model.Server
import no.beint.riss.model.Tag
import java.math.BigDecimal
import java.math.BigInteger

/** Serializes the compiled document as compact JSON. YAML is not a supported encoding. */
internal object OpenApiWriter {
    fun write(document: OpenApi): ByteArray = writeString(document).toByteArray(Charsets.UTF_8)

    fun writeString(document: OpenApi): String = CompactJsonWriter.write(tree(document))

    private fun tree(document: OpenApi): Map<String, Any> = obj(
        "openapi" to OpenApi.VERSION,
        "jsonSchemaDialect" to OpenApi.JSON_SCHEMA_DIALECT,
        "info" to obj(
            "title" to document.info().title(),
            "version" to document.info().version(),
            "description" to document.info().description(),
            "contact" to document.info().contact()?.let { contact ->
                obj("name" to contact.name(), "email" to contact.email(), "url" to contact.url())
            },
        ),
        "servers" to document.servers().map(::server).ifEmpty { null },
        "security" to document.security().map(::security).ifEmpty { null },
        "tags" to document.tags().map(::tag).ifEmpty { null },
        "paths" to document.paths().mapValues { (_, item) ->
            item.operations().mapValues { (_, operation) -> operation(operation) }
        },
        "components" to components(document),
    )

    private fun components(document: OpenApi): Map<String, Any>? {
        val schemas = document.components().schemas()
        val schemes = document.components().securitySchemes()
        if (schemas.isEmpty() && schemes.isEmpty()) return null
        return obj(
            "schemas" to schemas.mapValues { (_, schema) -> schema(schema) }.ifEmpty { null },
            "securitySchemes" to schemes.mapValues { (_, scheme) -> securityScheme(scheme) }.ifEmpty { null },
        )
    }

    private fun server(server: Server) = obj("url" to server.url(), "description" to server.description())

    private fun tag(tag: Tag) = obj("name" to tag.name(), "description" to tag.description())

    private fun security(requirement: SecurityRequirement) = mapOf(requirement.name() to requirement.scopes())

    private fun securityScheme(scheme: SecurityScheme) = obj(
        "type" to scheme.type(),
        "description" to scheme.description(),
        "scheme" to scheme.scheme(),
        "bearerFormat" to scheme.bearerFormat(),
        "name" to scheme.name(),
        "in" to scheme.`in`(),
    )

    private fun operation(operation: Operation) = obj(
        "tags" to operation.tags().ifEmpty { null },
        "summary" to operation.summary(),
        "description" to operation.description(),
        "operationId" to operation.operationId(),
        "deprecated" to operation.deprecated().takeIf { it },
        "parameters" to operation.parameters().map(::parameter).ifEmpty { null },
        "requestBody" to operation.requestBody()?.let(::requestBody),
        "responses" to operation.responses().mapValues { (_, response) -> response(response) },
        "security" to operation.security().map(::security).ifEmpty { null },
    )

    private fun parameter(parameter: Parameter) = obj(
        "name" to parameter.name(),
        "in" to parameter.locatedIn(),
        "description" to parameter.description(),
        "required" to parameter.required(),
        "style" to parameter.style(),
        "explode" to parameter.explode(),
        "example" to literal(parameter.example(), parameter.schema().types()),
        "schema" to schema(parameter.schema()),
    )

    private fun requestBody(body: RequestBody) = obj(
        "description" to body.description(),
        "required" to body.required(),
        "content" to content(body.content()),
    )

    private fun response(response: Response) = obj(
        "description" to response.description(),
        "content" to response.content().takeIf { it.isNotEmpty() }?.let(::content),
    )

    private fun content(content: Map<String, MediaType>) =
        content.mapValues { (_, media) ->
            obj("schema" to schema(media.schema()), "example" to literal(media.example(), media.schema().types()))
        }

    private fun schema(schema: Schema): Map<String, Any> {
        if (schema.ref() != null && schema.anyOf().isEmpty() && schema.oneOf().isEmpty()) {
            return obj("\$ref" to schema.ref(), "description" to schema.description())
        }
        val types = schema.types()
        return obj(
            "description" to schema.description(),
            "type" to when {
                types.size == 1 -> types.first()
                types.size > 1 -> types
                else -> null
            },
            "format" to schema.format(),
            "pattern" to schema.pattern(),
            "minLength" to schema.minLength(),
            "maxLength" to schema.maxLength(),
            "minimum" to schema.minimum(),
            "maximum" to schema.maximum(),
            "multipleOf" to schema.multipleOf(),
            "minItems" to schema.minItems(),
            "maxItems" to schema.maxItems(),
            "enum" to schema.enumValues().ifEmpty { null },
            "default" to literal(schema.defaultValue(), types),
            "example" to literal(schema.example(), types),
            "items" to schema.items()?.let(::schema),
            "properties" to schema.properties().mapValues { (_, property) -> schema(property) }.ifEmpty { null },
            "required" to schema.required().ifEmpty { null },
            "additionalProperties" to when {
                schema.additionalProperties() != null -> schema(schema.additionalProperties())
                schema.additionalPropertiesAllowed() != null -> schema.additionalPropertiesAllowed()
                else -> null
            },
            "oneOf" to schema.oneOf().map(::schema).ifEmpty { null },
            "anyOf" to schema.anyOf().map(::schema).ifEmpty { null },
        )
    }

    private fun literal(value: String?, types: Collection<String> = emptyList()): Any? {
        if (value == null) return null
        if ("boolean" in types) {
            return when (value) {
                "true" -> true
                "false" -> false
                else -> value
            }
        }
        if (("integer" in types || "number" in types) && value.matches(NUMBER)) {
            return BigDecimal(value)
        }
        return value
    }

    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any> {
        val map = linkedMapOf<String, Any>()
        entries.forEach { (key, value) ->
            if (value != null) {
                map[key] = value
            }
        }
        return map
    }

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
}

internal object CompactJsonWriter {
    fun write(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Boolean -> append(value)
            is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> append(value)
            is Float -> appendFinite(value.toDouble(), value)
            is Double -> appendFinite(value, value)
            is Map<*, *> -> appendObject(value)
            is Iterable<*> -> appendArray(value)
            else -> error("unsupported JSON value ${value::class.qualifiedName}")
        }
    }

    private fun StringBuilder.appendFinite(number: Double, value: Number) {
        require(number.isFinite()) { "JSON numbers must be finite" }
        append(value)
    }

    private fun StringBuilder.appendObject(value: Map<*, *>) {
        append('{')
        var separator = false
        value.entries.sortedBy { (key) ->
            require(key is String) { "JSON object keys must be strings" }
            key
        }.forEach { (key, item) ->
            if (separator) append(',') else separator = true
            appendString(key as String)
            append(':')
            appendValue(item)
        }
        append('}')
    }

    private fun StringBuilder.appendArray(value: Iterable<*>) {
        append('[')
        var separator = false
        value.forEach { item ->
            if (separator) append(',') else separator = true
            appendValue(item)
        }
        append(']')
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    character.code < 0x20 -> appendUnicodeEscape(character)
                    character.isHighSurrogate() &&
                        value.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                        append(character)
                        append(value[++index])
                    }
                    character.isSurrogate() -> appendUnicodeEscape(character)
                    else -> append(character)
                }
            }
            index++
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(character: Char) {
        append("\\u")
        repeat(4) { shift ->
            append(HEX[(character.code shr (12 - shift * 4)) and 0x0f])
        }
    }

    private const val HEX = "0123456789abcdef"
}
