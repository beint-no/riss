package no.beint.riss.compiler

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationMetaAnnotationTest {
    private val preAuthorize = "org.springframework.security.access.prepost.PreAuthorize"
    private val targets = setOf(preAuthorize)

    @Test
    fun matchesDirectAnnotation() {
        val host = annotated(annotation(preAuthorize))
        assertTrue(host.hasAnyMetaAnnotation(targets))
    }

    @Test
    fun matchesSingleAndMultiLevelMetaAnnotationsUpToDepth3() {
        val level1 = annotation(
            "no.example.RequireLedgerReadPermission",
            meta = listOf(annotation(preAuthorize)),
        )
        val level2 = annotation("no.example.RequireFullLedger", meta = listOf(level1))
        val level3 = annotation("no.example.RequireSuperLedger", meta = listOf(level2))
        val level4 = annotation("no.example.TooDeep", meta = listOf(level3))

        assertTrue(annotated(level1).hasAnyMetaAnnotation(targets))
        assertTrue(annotated(level2).hasAnyMetaAnnotation(targets))
        assertTrue(annotated(level3).hasAnyMetaAnnotation(targets))
        assertFalse(annotated(level4).hasAnyMetaAnnotation(targets))
    }

    @Test
    fun handlesCyclicMetaAnnotationsWithoutInfiniteRecursion() {
        val cycleAAnnotations = mutableListOf<KSAnnotation>()
        val cycleBAnnotations = mutableListOf<KSAnnotation>()
        val annA = annotation("no.example.CycleA", metaProvider = { cycleAAnnotations })
        val annB = annotation("no.example.CycleB", metaProvider = { cycleBAnnotations })
        cycleAAnnotations += annB
        cycleBAnnotations += annA

        assertFalse(annotated(annA).hasAnyMetaAnnotation(targets))
    }

    @Test
    fun returnsFalseWhenNoAuthorizationAnnotationPresent() {
        val other = annotation("org.springframework.web.bind.annotation.GetMapping")
        assertFalse(annotated(other).hasAnyMetaAnnotation(targets))
        assertFalse(annotated().hasAnyMetaAnnotation(targets))
    }

    @Test
    fun respectsCustomAuthorizationAnnotations() {
        val custom = annotation("no.example.CustomAuth")
        assertFalse(annotated(custom).hasAnyMetaAnnotation(targets))
        assertTrue(annotated(custom).hasAnyMetaAnnotation(setOf("no.example.CustomAuth")))
    }

    private fun annotated(vararg annotations: KSAnnotation): KSAnnotated = symbol { method ->
        when (method) {
            "getAnnotations" -> annotations.asSequence()
            else -> error(method)
        }
    }

    private fun annotation(
        qualifiedName: String,
        meta: List<KSAnnotation> = emptyList(),
        metaProvider: (() -> List<KSAnnotation>)? = null,
    ): KSAnnotation {
        val declaration = symbol<KSDeclaration> { method ->
            when (method) {
                "getQualifiedName" -> ksName(qualifiedName)
                "getSimpleName" -> ksName(qualifiedName.substringAfterLast('.'))
                "getAnnotations" -> (metaProvider?.invoke() ?: meta).asSequence()
                else -> error(method)
            }
        }
        val resolvedType = symbol<KSType> { method ->
            when (method) {
                "isError" -> false
                "getDeclaration" -> declaration
                else -> error(method)
            }
        }
        val typeRef = symbol<KSTypeReference> { method ->
            when (method) {
                "resolve" -> resolvedType
                else -> error(method)
            }
        }
        return symbol { method ->
            when (method) {
                "getShortName" -> ksName(qualifiedName.substringAfterLast('.'))
                "getAnnotationType" -> typeRef
                else -> error(method)
            }
        }
    }

    private fun ksName(value: String): KSName = object : KSName {
        override fun asString(): String = value
        override fun getQualifier(): String = value.substringBeforeLast('.', "")
        override fun getShortName(): String = value.substringAfterLast('.')
    }

    private inline fun <reified T> symbol(crossinline read: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            read(method.name)
        } as T
}
