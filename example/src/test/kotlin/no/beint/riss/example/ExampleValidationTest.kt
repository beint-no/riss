package no.beint.riss.example

import no.beint.riss.spring.RissSpringValidator
import no.beint.riss.validation.RissValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.validation.BeanPropertyBindingResult
import java.math.BigDecimal
import java.util.Locale

@SpringBootTest(classes = [App::class])
@AutoConfigureMockMvc
class ExampleValidationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var rissValidator: RissValidator

    @Test
    fun hibernateValidatorAndJakartaValidationAreNotOnClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("org.hibernate.validator.HibernateValidator")
        }
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("jakarta.validation.Validator")
        }
    }

    @Test
    fun pathVariablePositiveConstraintIsEnforcedWithoutProxies() {
        mockMvc.perform(get("/api/features/0"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Validation failed"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
            .andExpect(jsonPath("$.fieldErrors[0].message").value("must be greater than 0"))

        mockMvc.perform(get("/api/features/1"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun requestBodyValidAnnotationTriggersCompileTimeValidation() {
        val invalidJson = """
            {
              "tags": ["${"x".repeat(256)}"],
              "labels": [],
              "name": "   ",
              "currencyCode": "NOK",
              "kind": "safe"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/features")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson),
        )
            .andExpect(status().isBadRequest)

        val validJson = """
            {
              "tags": ["fast"],
              "labels": ["v1"],
              "name": "Compile-time validation",
              "currencyCode": "NOK",
              "kind": "safe"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/features")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Compile-time validation"))
    }

    @Test
    fun nestedContainerAndNumericConstraintsReportExactPaths() {
        val req = ContractReq(
            primaryAddress = AddressDto("Main"),
            billingAddress = null,
            probeResult = ProbeResult.Ok("ok"),
            optionalProbeResult = null,
            describedTags = listOf("a"),
            currencies = listOf("NOK"),
            notes = listOf("n"),
            amount = BigDecimal.ZERO,
            count = 0,
            exclusiveRate = BigDecimal("0.5"),
            inclusiveRate = BigDecimal("10.1"),
            bigId = 100L,
            blankChecked = " ",
            sizedBlankChecked = "ab",
            nonEmptyList = emptyList(),
            nonEmptyText = "",
            nonEmptyMap = emptyMap(),
            scores = mapOf("badKey" to 0),
            tenantCode = TenantCode("T1"),
            optionalTenantCode = null,
            defaultPriority = Priority.LOW,
            overridePriority = Priority.HIGH,
            componentId = "comp-1",
        )
        val violations = rissValidator.validate(req)
        val fields = violations.map { it.field }
        assertTrue("amount" in fields)
        assertTrue("count" in fields)
        assertTrue("exclusiveRate" in fields)
        assertTrue("inclusiveRate" in fields)
        assertTrue("bigId" in fields)
        assertTrue("blankChecked" in fields)
        assertTrue("sizedBlankChecked" in fields)
        assertTrue("nonEmptyList" in fields)
        assertTrue("nonEmptyText" in fields)
        assertTrue("nonEmptyMap" in fields)
        assertTrue("scores[badKey]" in fields)

        val bindingResult = BeanPropertyBindingResult(req, "contractReq")
        val springValidator = RissSpringValidator(rissValidator, null)
        springValidator.validate(req, bindingResult)
        assertTrue(bindingResult.hasFieldErrors("scores[badKey]"))
        assertFalse(bindingResult.fieldErrors.isEmpty())
    }

    @Test
    fun messageSourceResolvesCurlyBraceI18nReferences() {
        val messages = StaticMessageSource()
        messages.addMessage("err.custom.name", Locale.getDefault(), "Name is required (translated)")
        val customValidator = RissValidator(
            listOf(
                no.beint.riss.validation.ValidatorSet {
                    mapOf(
                        FeatureReq::class.java to no.beint.riss.validation.TypeValidator { _, _, sink, _ ->
                            sink.reject("name", "{err.custom.name}")
                        },
                    )
                },
            ),
        )
        val springValidator = RissSpringValidator(customValidator, messages)
        val target = FeatureReq(name = "", currencyCode = "NOK", kind = FeatureKind.SAFE)
        val errors = BeanPropertyBindingResult(target, "featureReq")
        springValidator.validate(target, errors)
        assertEquals("Name is required (translated)", errors.getFieldError("name")?.defaultMessage)
    }
}
