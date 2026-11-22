package no.beint.riss.example

import kotlin.Deprecated as Legacy

import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import no.beint.riss.validation.DecimalMax
import no.beint.riss.validation.DecimalMin
import no.beint.riss.validation.Max
import no.beint.riss.validation.Min
import no.beint.riss.validation.NotBlank
import no.beint.riss.validation.NotEmpty
import no.beint.riss.validation.Positive
import no.beint.riss.validation.Size
import no.beint.riss.validation.Valid
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
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
        RissProperty(name = "fieldErrors", type = "array", ref = "FieldError", description = "Validation errors", example = "[]"),
        RissProperty(name = "primaryError", ref = "FieldError", nullable = true, description = "First validation error", example = "{\"field\":\"status\"}"),
        RissProperty(name = "rootError", ref = "FieldError", description = "Root error", example = "{\"field\":\"name\"}"),
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
    @field:Size(max = 100)
    val tags: List<@Size(max = 255) String> = emptyList(),
    @field:Size(max = 20)
    val labels: List<String> = emptyList(),
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
    @Legacy("Use list instead")
    @GetMapping("/legacy")
    fun legacy(): List<FeatureRes> = emptyList()

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

    @PostMapping("/{id}/asset", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun asset(
        @PathVariable id: Int,
        @Valid @RequestPart("request") request: FeatureReq,
        @RequestPart("file") file: MultipartFile,
        @RequestPart(name = "caption", required = false) caption: String?,
        @RequestParam(required = false) dryRun: Boolean?,
    ): FeatureReq = request

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

@JvmInline
value class TenantCode(val raw: String)

@Schema(description = "Class enum description")
enum class Priority {
    LOW,
    HIGH,
}

@Schema(description = "Address DTO description")
data class AddressDto(
    val street: String,
)

data class ContractReq(
    @field:Schema(description = "Primary address", defaultValue = "{}", example = "{\"street\":\"Main\"}")
    val primaryAddress: AddressDto,
    @field:Schema(description = "Optional billing address", example = "{\"street\":\"Billing\"}")
    val billingAddress: AddressDto? = null,
    @field:Schema(description = "Required probe result")
    val probeResult: ProbeResult,
    @field:Schema(description = "Optional probe result")
    val optionalProbeResult: ProbeResult? = null,
    @field:Schema(description = "Feature tags", example = "[\"alpha\"]")
    val describedTags: List<String>,
    @field:Schema(ref = "CurrencyCode", description = "Supported currencies")
    val currencies: List<String>,
    @field:ArraySchema(
        schema = Schema(description = "One note"),
        arraySchema = Schema(description = "All notes"),
    )
    val notes: List<String>,
    @field:Positive
    val amount: BigDecimal,
    @field:Positive
    val count: Int,
    @field:DecimalMin(value = "0.5", inclusive = false)
    @field:DecimalMax(value = "99.5", inclusive = false)
    val exclusiveRate: BigDecimal,
    @field:DecimalMin("1.0")
    @field:DecimalMax("10.0")
    val inclusiveRate: BigDecimal,
    @field:Min(3_000_000_000L)
    @field:Max(9_000_000_000L)
    val bigId: Long,
    @field:NotBlank
    val blankChecked: String,
    @field:NotBlank
    @field:Size(min = 3, max = 50)
    val sizedBlankChecked: String,
    @field:NotEmpty
    val nonEmptyList: List<String>,
    @field:NotEmpty
    val nonEmptyText: String,
    @field:NotEmpty
    val nonEmptyMap: Map<String, String>,
    @field:Size(min = 1, max = 10)
    val scores: Map<String, @Positive Int>,
    val tenantCode: TenantCode,
    val optionalTenantCode: TenantCode? = null,
    val defaultPriority: Priority,
    @field:Schema(description = "Field enum description")
    val overridePriority: Priority,
    val componentId: String,
)

@RestController
@RequestMapping("/api/contracts")
@Tag(name = "Contracts")
@ApiResponses(
    ApiResponse(responseCode = "403", description = "Controller forbidden"),
    ApiResponse(responseCode = "404", description = "Controller not found"),
)
class ContractApi {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: ContractReq): ContractReq = request

    @DeleteMapping("/{id}")
    @ResponseStatus(code = HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "404", description = "Contract not found")
    fun delete(@PathVariable id: Long) {}

    @PostMapping("/optional-upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun optionalUpload(
        @Parameter(description = "Optional upload note")
        @RequestPart(name = "note", required = false)
        note: String?,
    ): Map<String, String> = mapOf("note" to (note ?: ""))
}

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Jobs")
@ResponseStatus(HttpStatus.ACCEPTED)
class JobApi {
    @PostMapping
    fun enqueue(): Map<String, String> = mapOf("status" to "queued")
}
