package no.beint.riss.example

import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import no.beint.riss.ParameterObject
import no.beint.riss.RissDefaultResponse
import no.beint.riss.RissDocument
import no.beint.riss.RissGlobalHeader
import no.beint.riss.RissObjectSchema
import no.beint.riss.RissProperty
import no.beint.riss.RissSecurityScheme
import no.beint.riss.RissStringSchema
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI

@SpringBootApplication
class App

fun main(args: Array<String>) {
    runApplication<App>(*args)
}

@RissDocument(
    name = "example",
    title = "Riss example API",
    version = "v1",
    description = "Compile-time OpenAPI 3.1 document for the Riss example.",
    scanPackages = ["no.beint.riss.example"],
    paths = ["/api/**"],
    security = ["bearer-token"],
)
@RissSecurityScheme(name = "bearer-token", bearerFormat = "JWT")
@RissStringSchema(
    name = "CurrencyCode",
    description = "ISO 4217 currency code",
    pattern = "^[A-Z]{3}$",
    minLength = 3,
    maxLength = 3,
    example = "NOK",
)
@RissStringSchema(
    name = "FeatureKindCode",
    description = "Kind of feature",
    enumFrom = FeatureKind::class,
    enumProperty = "wire",
    example = "safe",
)
@RissObjectSchema(
    name = "FieldError",
    properties = [
        RissProperty(name = "field", example = "status"),
        RissProperty(name = "message", example = "must not be blank"),
    ],
)
@RissObjectSchema(
    name = "ProblemDetail",
    description = "RFC 9457 problem",
    properties = [
        RissProperty(name = "title", nullable = true, example = "Invalid request"),
        RissProperty(name = "status", type = "integer", format = "int32", example = "400"),
        RissProperty(name = "detail", nullable = true),
        RissProperty(name = "fieldErrors", type = "array", ref = "FieldError"),
    ],
)
@RissGlobalHeader(
    name = "X-Tenant-Id",
    description = "Optional tenant id",
    type = "integer",
    format = "int32",
    skipTypes = [HealthApi::class],
)
@RissDefaultResponse(code = "400", description = "Bad request", contentType = "application/problem+json", schemaRef = "ProblemDetail")
@RissDefaultResponse(code = "401", description = "Unauthorized")
class ExampleDocs

enum class FeatureKind(@get:JsonValue val wire: String) {
    SAFE("safe"),
    SMALL("small"),
    FAST("fast"),
}

data class FeatureFilter(
    @field:Schema(description = "Substring match on the feature name")
    val query: String? = null,
    val kind: FeatureKind? = null,
)

data class FeatureReq(
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:Schema(ref = "#/components/schemas/CurrencyCode")
    val currencyCode: String,
    val kind: FeatureKind,
)

data class FeatureRes(
    val id: Int,
    val name: String,
    val description: String?,
    @field:Schema(ref = "#/components/schemas/CurrencyCode")
    val currencyCode: String,
    val kind: FeatureKind,
)

data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

data class ProbeReq(
    @field:NotBlank
    val name: String,
    val metadata: Map<String, Any?>,
    val evidences: List<Map<String, Any?>>,
    val extra: Any? = null,
)

sealed interface ProbeResult {
    data class Ok(val name: String) : ProbeResult
    data class Err(val message: String) : ProbeResult
}

@RestController
@RequestMapping("/api/features")
@Tag(name = "Features", description = "Example features")
class FeatureApi {
    @GetMapping
    fun list(@Valid @ParameterObject filter: FeatureFilter?): List<FeatureRes> = emptyList()

    @GetMapping("/{id}")
    fun get(@PathVariable @Positive id: Int): ResponseEntity<FeatureRes> = ResponseEntity.notFound().build()

    @PostMapping
    @Operation(summary = "Create feature", description = "Creates a feature.")
    @ApiResponse(responseCode = "201", description = "Feature created")
    fun create(@Valid @RequestBody request: FeatureReq): ResponseEntity<FeatureRes> =
        ResponseEntity.created(URI.create("/api/features/1")).body(
            FeatureRes(1, request.name, request.description, request.currencyCode, request.kind),
        )

    @PostMapping("/{id}/icon", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun icon(@PathVariable id: Int, @RequestParam("file") file: MultipartFile): ResponseEntity<Void> =
        ResponseEntity.accepted().build()

    @GetMapping("/pages")
    fun pages(): Page<FeatureRes> = Page(emptyList(), null)

    @PostMapping("/probes")
    fun probes(@RequestBody request: ProbeReq): ProbeReq = request

    @GetMapping("/probes/{name}")
    fun probe(@PathVariable name: String): ProbeResult = ProbeResult.Ok(name)
}

@RestController
@RequestMapping("/api/health")
@Tag(name = "Health")
class HealthApi {
    @GetMapping
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
