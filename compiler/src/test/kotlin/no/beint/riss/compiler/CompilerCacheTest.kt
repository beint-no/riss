package no.beint.riss.compiler

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSValueParameter
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerCacheTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun annotationCacheIsReleasedAndIsolatedBetweenCompilationThreads() {
        val reads = AtomicInteger()
        val host = symbol<KSAnnotated> { method ->
            when (method) {
                "getAnnotations" -> {
                    reads.incrementAndGet()
                    emptySequence<Nothing>()
                }
                else -> error(method)
            }
        }
        AnnotationIndex.reset()
        try {
            repeat(2) { host.annotation(Names.RISS_IGNORE) }
            assertEquals(1, reads.get())
            Executors.newSingleThreadExecutor().use { executor ->
                executor.submit {
                    try {
                        repeat(2) { host.annotation(Names.RISS_IGNORE) }
                    } finally {
                        AnnotationIndex.reset()
                    }
                }.get()
            }
            host.annotation(Names.RISS_IGNORE)
            assertEquals(2, reads.get())
            AnnotationIndex.reset()
            host.annotation(Names.RISS_IGNORE)
            assertEquals(3, reads.get())
        } finally {
            AnnotationIndex.reset()
        }
    }

    @Test
    fun enumValuesAreReadOncePerCompilationAndFilteredIndependently() {
        val path = directory.resolve("Feature.kt")
        val source = "enum class Feature(val wire: String, val active: Boolean) { ALPHA(\"alpha\", true), BETA(\"beta\", false) }"
        Files.writeString(path, source)
        val file = symbol<KSFile> { method ->
            when (method) {
                "getFilePath" -> path.toString()
                else -> error(method)
            }
        }
        val parameters = listOf("wire", "active").map { parameter ->
            symbol<KSValueParameter> { method ->
                when (method) {
                    "getName" -> name(parameter)
                    else -> error(method)
                }
            }
        }
        val constructor = symbol<KSFunctionDeclaration> { method ->
            when (method) {
                "getParameters" -> parameters
                else -> error(method)
            }
        }
        val entries = listOf("ALPHA", "BETA").map { entry ->
            symbol<KSClassDeclaration> { method ->
                when (method) {
                    "getSimpleName" -> name(entry)
                    "getClassKind" -> ClassKind.ENUM_ENTRY
                    "getAnnotations" -> emptySequence<Nothing>()
                    else -> error(method)
                }
            }
        }
        val declaration = symbol<KSClassDeclaration> { method ->
            when (method) {
                "getSimpleName", "getQualifiedName" -> name("Feature")
                "getContainingFile" -> file
                "getPrimaryConstructor" -> constructor
                "getDeclarations" -> entries.asSequence()
                else -> error(method)
            }
        }
        val diagnostics = Diagnostics()
        try {
            EnumReader(emptyList(), diagnostics).use { reader ->
                assertEquals(listOf("alpha", "beta"), reader.values(declaration, "wire", null, null, "first"))
                Files.writeString(path, source.replace("alpha", "changed"))
                assertEquals(listOf("alpha"), reader.values(declaration, "wire", "active", "true", "filtered"))
                assertEquals(listOf("BETA"), reader.values(declaration, null, "active", "false", "names"))
            }
            EnumReader(emptyList(), diagnostics).use { reader ->
                assertEquals(listOf("changed", "beta"), reader.values(declaration, "wire", null, null, "next"))
            }
            assertTrue(diagnostics.problems.isEmpty())
        } finally {
            AnnotationIndex.reset()
        }
    }

    private fun name(value: String): KSName = object : KSName {
        override fun asString(): String = value
        override fun getQualifier(): String = value.substringBeforeLast('.', "")
        override fun getShortName(): String = value.substringAfterLast('.')
    }

    private inline fun <reified T> symbol(crossinline read: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            read(method.name)
        } as T
}
