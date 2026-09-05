package no.beint.riss.compiler

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType

internal object Names {
    const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
    const val CONTROLLER = "org.springframework.web.bind.annotation.Controller"
    const val RESPONSE_BODY = "org.springframework.web.bind.annotation.ResponseBody"
    const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
    const val GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping"
    const val POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping"
    const val PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping"
    const val PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping"
    const val DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping"
    const val PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable"
    const val REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
    const val REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader"
    const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"
    const val REQUEST_PART = "org.springframework.web.bind.annotation.RequestPart"
    const val MODEL_ATTRIBUTE = "org.springframework.web.bind.annotation.ModelAttribute"
    const val SPRINGDOC_PARAMETER_OBJECT = "org.springdoc.core.annotations.ParameterObject"
    const val RISS_PARAMETER_OBJECT = "no.beint.riss.ParameterObject"
    const val RISS_IGNORE = "no.beint.riss.RissIgnore"
    const val RISS_DOCUMENT = "no.beint.riss.RissDocument"
    const val RISS_SECURITY_SCHEME = "no.beint.riss.RissSecurityScheme"
    const val RISS_SECURITY_SCHEMES = "no.beint.riss.RissSecuritySchemes"
    const val RISS_STRING_SCHEMA = "no.beint.riss.RissStringSchema"
    const val RISS_STRING_SCHEMAS = "no.beint.riss.RissStringSchemas"
    const val RISS_OBJECT_SCHEMA = "no.beint.riss.RissObjectSchema"
    const val RISS_OBJECT_SCHEMAS = "no.beint.riss.RissObjectSchemas"
    const val RISS_GLOBAL_HEADER = "no.beint.riss.RissGlobalHeader"
    const val RISS_GLOBAL_HEADERS = "no.beint.riss.RissGlobalHeaders"
    const val RISS_DEFAULT_RESPONSE = "no.beint.riss.RissDefaultResponse"
    const val RISS_DEFAULT_RESPONSES = "no.beint.riss.RissDefaultResponses"
    const val RISS_SERVER = "no.beint.riss.RissServer"
    const val RISS_SERVERS = "no.beint.riss.RissServers"
    const val RISS_TAG = "no.beint.riss.RissTag"
    const val RISS_TAGS = "no.beint.riss.RissTags"
    const val SWAGGER_TAG = "io.swagger.v3.oas.annotations.tags.Tag"
    const val SWAGGER_TAGS = "io.swagger.v3.oas.annotations.tags.Tags"
    const val SWAGGER_OPERATION = "io.swagger.v3.oas.annotations.Operation"
    const val SWAGGER_PARAMETER = "io.swagger.v3.oas.annotations.Parameter"
    const val SWAGGER_API_RESPONSE = "io.swagger.v3.oas.annotations.responses.ApiResponse"
    const val SWAGGER_API_RESPONSES = "io.swagger.v3.oas.annotations.responses.ApiResponses"
    const val SWAGGER_SCHEMA = "io.swagger.v3.oas.annotations.media.Schema"
    const val SWAGGER_ARRAY_SCHEMA = "io.swagger.v3.oas.annotations.media.ArraySchema"
    const val SWAGGER_CONTENT = "io.swagger.v3.oas.annotations.media.Content"
    const val SWAGGER_REQUEST_BODY = "io.swagger.v3.oas.annotations.parameters.RequestBody"
    const val SWAGGER_EXAMPLE = "io.swagger.v3.oas.annotations.media.ExampleObject"
    const val SWAGGER_HIDDEN = "io.swagger.v3.oas.annotations.Hidden"
    const val SWAGGER_OPENAPI = "io.swagger.v3.oas.annotations.OpenAPIDefinition"
    const val SWAGGER_SECURITY_SCHEME = "io.swagger.v3.oas.annotations.security.SecurityScheme"
    const val SWAGGER_SECURITY_SCHEMES = "io.swagger.v3.oas.annotations.security.SecuritySchemes"
    const val JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore"
    const val JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"
    const val JSON_VALUE = "com.fasterxml.jackson.annotation.JsonValue"
    const val JSON_ANY_SETTER = "com.fasterxml.jackson.annotation.JsonAnySetter"
    const val TOOLS_JSON_IGNORE = "tools.jackson.annotation.JsonIgnore"
    const val TOOLS_JSON_PROPERTY = "tools.jackson.annotation.JsonProperty"
    const val TOOLS_JSON_VALUE = "tools.jackson.annotation.JsonValue"
    const val NOT_NULL = "jakarta.validation.constraints.NotNull"
    const val NOT_BLANK = "jakarta.validation.constraints.NotBlank"
    const val NOT_EMPTY = "jakarta.validation.constraints.NotEmpty"
    const val SIZE = "jakarta.validation.constraints.Size"
    const val MIN = "jakarta.validation.constraints.Min"
    const val MAX = "jakarta.validation.constraints.Max"
    const val DECIMAL_MIN = "jakarta.validation.constraints.DecimalMin"
    const val DECIMAL_MAX = "jakarta.validation.constraints.DecimalMax"
    const val PATTERN = "jakarta.validation.constraints.Pattern"
    const val EMAIL = "jakarta.validation.constraints.Email"
    const val POSITIVE = "jakarta.validation.constraints.Positive"
    const val POSITIVE_OR_ZERO = "jakarta.validation.constraints.PositiveOrZero"
    const val DIGITS = "jakarta.validation.constraints.Digits"

    val mappings = mapOf(
        GET_MAPPING to setOf("GET"),
        POST_MAPPING to setOf("POST"),
        PUT_MAPPING to setOf("PUT"),
        PATCH_MAPPING to setOf("PATCH"),
        DELETE_MAPPING to setOf("DELETE"),
        REQUEST_MAPPING to emptySet(),
    )
}

internal object AnnotationIndex {
    private val cache = ThreadLocal.withInitial {
        java.util.IdentityHashMap<KSAnnotated, Map<String, List<KSAnnotation>>>()
    }

    fun reset() {
        cache.remove()
    }

    fun find(host: KSAnnotated, qualifiedName: String): KSAnnotation? = matching(host, qualifiedName).firstOrNull()

    fun findAll(host: KSAnnotated, qualifiedName: String): List<KSAnnotation> {
        val nested = matching(host, qualifiedName + "s").firstOrNull()?.annotations("value").orEmpty()
        return matching(host, qualifiedName) + nested
    }

    private fun matching(host: KSAnnotated, qualifiedName: String): List<KSAnnotation> {
        val shortName = qualifiedName.substringAfterLast('.')
        val candidates = index(host)[shortName].orEmpty()
        if (candidates.size <= 1) {
            return candidates
        }
        return candidates.filter { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun index(host: KSAnnotated): Map<String, List<KSAnnotation>> =
        cache.get().getOrPut(host) { host.annotations.groupBy { it.shortName.asString() } }
}

internal fun KSAnnotation.matches(qualifiedName: String): Boolean =
    shortName.asString() == qualifiedName.substringAfterLast('.') &&
        annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName

internal fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? = AnnotationIndex.find(this, qualifiedName)

internal fun KSAnnotated.annotationsNamed(qualifiedName: String): List<KSAnnotation> =
    AnnotationIndex.findAll(this, qualifiedName)

internal fun KSAnnotation.string(name: String): String? =
    (argument(name) as? String)?.takeIf { it.isNotBlank() && it != "<none>" && it != "\u0000" }

internal fun KSAnnotation.strings(name: String): List<String> =
    when (val value = argument(name)) {
        is List<*> -> value.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        is String -> listOf(value).filter(String::isNotBlank)
        else -> emptyList()
    }

internal fun KSAnnotation.bool(name: String): Boolean? = argument(name) as? Boolean

internal fun KSAnnotation.int(name: String): Int? = (argument(name) as? Number)?.toInt()

internal fun KSAnnotation.double(name: String): Double? = (argument(name) as? Number)?.toDouble()

internal fun KSAnnotation.type(name: String): KSType? = argument(name) as? KSType

internal fun KSAnnotation.types(name: String): List<KSType> =
    when (val value = argument(name)) {
        is List<*> -> value.filterIsInstance<KSType>()
        is KSType -> listOf(value)
        else -> emptyList()
    }

internal fun KSAnnotation.annotation(name: String): KSAnnotation? = argument(name) as? KSAnnotation

internal fun KSAnnotation.annotations(name: String): List<KSAnnotation> =
    when (val value = argument(name)) {
        is List<*> -> value.filterIsInstance<KSAnnotation>()
        is KSAnnotation -> listOf(value)
        else -> emptyList()
    }

internal fun KSAnnotation.enumName(name: String): String? = enumName(argument(name))

internal fun enumName(value: Any?): String? = when (value) {
    is KSType -> value.declaration.simpleName.asString()
    is KSDeclaration -> value.simpleName.asString()
    null -> null
    else -> value.toString().substringAfterLast('.').takeIf { it.isNotBlank() && it != "DEFAULT" }
}

private fun KSAnnotation.argument(name: String): Any? =
    arguments.firstOrNull { it.name?.asString() == name }?.value

internal fun KSDeclaration.qualified(): String =
    qualifiedName?.asString() ?: simpleName.asString()

internal fun KSClassDeclaration.packageNameString(): String = packageName.asString()
