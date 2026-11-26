package no.beint.riss.compiler

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestBodyValidationCompilerTest {
    @Test
    fun rejectsUnannotatedStructuredRequestBodyWhenEnabledAndAllowsWhenDisabled() {
        val dtoType = ksType("no.example.PlainDto")
        val param = ksParam(
            name = "body",
            type = dtoType,
            annotations = listOf(ksAnnotation(Names.REQUEST_BODY)),
        )
        val resolver = resolverWithEndpoint("create", listOf(param))

        AnnotationIndex.reset()
        try {
            val disabledDiagnostics = Diagnostics()
            ValidationCompiler(disabledDiagnostics, requireValidRequestBody = false).compile(resolver)
            assertTrue(disabledDiagnostics.problems.isEmpty())

            AnnotationIndex.reset()
            val enabledDiagnostics = Diagnostics()
            ValidationCompiler(enabledDiagnostics, requireValidRequestBody = true).compile(resolver)
            assertEquals(1, enabledDiagnostics.problems.size)
            assertEquals(
                "RISS-VALIDATION no.example.OrderController.create(body): @RequestBody parameter 'body' of structured type 'PlainDto' must be annotated with @Valid",
                enabledDiagnostics.problems.single().message,
            )
        } finally {
            AnnotationIndex.reset()
        }
    }

    @Test
    fun acceptsValidAnnotatedStructuredRequestBodyAndListOfValidDtos() {
        val dtoType = ksType("no.example.PlainDto")
        val validBody = ksParam(
            name = "body",
            type = dtoType,
            annotations = listOf(ksAnnotation(Names.REQUEST_BODY), ksAnnotation(Names.RISS_VALID)),
        )
        val validListArg = ksTypeArg(dtoType, annotations = listOf(ksAnnotation(Names.RISS_VALID)))
        val validListBody = ksParam(
            name = "items",
            type = ksType("kotlin.collections.List", arguments = listOf(validListArg)),
            annotations = listOf(ksAnnotation(Names.REQUEST_BODY)),
        )
        val resolver = resolverWithEndpoints(
            listOf(
                "createOne" to listOf(validBody),
                "createMany" to listOf(validListBody),
            ),
        )

        AnnotationIndex.reset()
        try {
            val diagnostics = Diagnostics()
            ValidationCompiler(diagnostics, requireValidRequestBody = true).compile(resolver)
            assertTrue(diagnostics.problems.isEmpty(), "Expected no errors but got: ${diagnostics.problems}")
        } finally {
            AnnotationIndex.reset()
        }
    }

    @Test
    fun rejectsListOfStructuredDtosWithoutElementValid() {
        val dtoType = ksType("no.example.PlainDto")
        val listBody = ksParam(
            name = "items",
            type = ksType("kotlin.collections.List", arguments = listOf(ksTypeArg(dtoType))),
            annotations = listOf(ksAnnotation(Names.REQUEST_BODY)),
        )
        val resolver = resolverWithEndpoint("createMany", listOf(listBody))

        AnnotationIndex.reset()
        try {
            val diagnostics = Diagnostics()
            ValidationCompiler(diagnostics, requireValidRequestBody = true).compile(resolver)
            assertEquals(1, diagnostics.problems.size)
            assertEquals(
                "RISS-VALIDATION no.example.OrderController.createMany(items): @RequestBody parameter 'items' of structured type 'List<PlainDto>' must be annotated with @Valid (use List<@Valid PlainDto>)",
                diagnostics.problems.single().message,
            )
        } finally {
            AnnotationIndex.reset()
        }
    }

    @Test
    fun excludesUnstructuredTypesFromRequireValidRequestBody() {
        val excludedTypes = listOf(
            "str" to ksType("kotlin.String"),
            "num" to ksType("kotlin.Int"),
            "bool" to ksType("kotlin.Boolean"),
            "bytes" to ksType("kotlin.ByteArray"),
            "anyVal" to ksType("kotlin.Any"),
            "obj" to ksType("java.lang.Object"),
            "map" to ksType(
                "kotlin.collections.Map",
                arguments = listOf(ksTypeArg(ksType("kotlin.String")), ksTypeArg(ksType("kotlin.Any"))),
            ),
            "jsonNode" to ksType("com.fasterxml.jackson.databind.JsonNode"),
            "toolsJsonNode" to ksType("tools.jackson.databind.JsonNode"),
            "stream" to ksType("java.io.InputStream"),
            "resource" to ksType("org.springframework.core.io.Resource"),
            "enumVal" to ksType("no.example.Status", classKind = ClassKind.ENUM_CLASS),
            "valueClass" to ksType("no.example.TenantId", modifiers = setOf(Modifier.VALUE)),
            "stringList" to ksType("kotlin.collections.List", arguments = listOf(ksTypeArg(ksType("kotlin.String")))),
        )
        val resolver = resolverWithEndpoints(
            excludedTypes.mapIndexed { idx, (name, type) ->
                "handle$idx" to listOf(ksParam(name, type, listOf(ksAnnotation(Names.REQUEST_BODY))))
            },
        )

        AnnotationIndex.reset()
        try {
            val diagnostics = Diagnostics()
            ValidationCompiler(diagnostics, requireValidRequestBody = true).compile(resolver)
            assertTrue(diagnostics.problems.isEmpty(), "Expected no errors but got: ${diagnostics.problems}")
        } finally {
            AnnotationIndex.reset()
        }
    }

    private fun resolverWithEndpoint(methodName: String, params: List<KSValueParameter>): Resolver =
        resolverWithEndpoints(listOf(methodName to params))

    private fun resolverWithEndpoints(endpoints: List<Pair<String, List<KSValueParameter>>>): Resolver {
        val controller = ksClass("no.example.OrderController")
        val functions = endpoints.map { (name, params) ->
            ksFunction(name, controller, params, listOf(ksAnnotation(Names.POST_MAPPING)))
        }
        return symbol { method, args ->
            when (method) {
                "getAllFiles" -> emptySequence<KSFile>()
                "getSymbolsWithAnnotation" -> {
                    val annotationName = args?.firstOrNull() as? String
                    if (annotationName == Names.POST_MAPPING) functions.asSequence() else emptySequence()
                }
                else -> error(method)
            }
        }
    }

    private fun ksClass(
        qualifiedName: String,
        classKind: ClassKind = ClassKind.CLASS,
        modifiers: Set<Modifier> = emptySet(),
    ): KSClassDeclaration = symbol { method, _ ->
        when (method) {
            "getQualifiedName" -> ksName(qualifiedName)
            "getSimpleName" -> ksName(qualifiedName.substringAfterLast('.'))
            "getClassKind" -> classKind
            "getModifiers" -> modifiers
            "getParentDeclaration" -> null
            "getAnnotations" -> emptySequence<KSAnnotation>()
            "getContainingFile" -> null
            "getPrimaryConstructor" -> null
            "getDeclarations" -> emptySequence<KSDeclaration>()
            "getSuperTypes" -> emptySequence<KSTypeReference>()
            else -> error(method)
        }
    }

    private fun ksFunction(
        name: String,
        parent: KSClassDeclaration,
        params: List<KSValueParameter>,
        annotations: List<KSAnnotation>,
    ): KSFunctionDeclaration = symbol { method, _ ->
        when (method) {
            "getSimpleName" -> ksName(name)
            "getParentDeclaration" -> parent
            "getParameters" -> params
            "getAnnotations" -> annotations.asSequence()
            "getModifiers" -> emptySet<Modifier>()
            "getContainingFile" -> null
            else -> error(method)
        }
    }

    private fun ksParam(
        name: String,
        type: KSType,
        annotations: List<KSAnnotation>,
    ): KSValueParameter {
        val typeRef = ksTypeRef(type)
        return symbol { method, _ ->
            when (method) {
                "getName" -> ksName(name)
                "getType" -> typeRef
                "getAnnotations" -> annotations.asSequence()
                else -> error(method)
            }
        }
    }

    private fun ksType(
        qualifiedName: String,
        classKind: ClassKind = ClassKind.CLASS,
        modifiers: Set<Modifier> = emptySet(),
        arguments: List<KSTypeArgument> = emptyList(),
        annotations: List<KSAnnotation> = emptyList(),
    ): KSType {
        val decl = ksClass(qualifiedName, classKind, modifiers)
        return symbol { method, _ ->
            when (method) {
                "isError" -> false
                "getDeclaration" -> decl
                "getArguments" -> arguments
                "getNullability" -> Nullability.NOT_NULL
                "getAnnotations" -> annotations.asSequence()
                else -> error(method)
            }
        }
    }

    private fun ksTypeArg(
        type: KSType,
        annotations: List<KSAnnotation> = emptyList(),
    ): KSTypeArgument {
        val ref = ksTypeRef(type, annotations)
        return symbol { method, _ ->
            when (method) {
                "getType" -> ref
                "getAnnotations" -> annotations.asSequence()
                else -> error(method)
            }
        }
    }

    private fun ksTypeRef(
        type: KSType,
        annotations: List<KSAnnotation> = emptyList(),
    ): KSTypeReference = symbol { method, _ ->
        when (method) {
            "resolve" -> type
            "getAnnotations" -> annotations.asSequence()
            else -> error(method)
        }
    }

    private fun ksAnnotation(qualifiedName: String): KSAnnotation {
        val decl = ksClass(qualifiedName, ClassKind.ANNOTATION_CLASS)
        val type = symbol<KSType> { method, _ ->
            when (method) {
                "isError" -> false
                "getDeclaration" -> decl
                else -> error(method)
            }
        }
        val ref = ksTypeRef(type)
        return symbol { method, _ ->
            when (method) {
                "getShortName" -> ksName(qualifiedName.substringAfterLast('.'))
                "getAnnotationType" -> ref
                "getArguments" -> emptyList<KSValueArgument>()
                else -> error(method)
            }
        }
    }

    private fun ksName(value: String): KSName = object : KSName {
        override fun asString(): String = value
        override fun getQualifier(): String = value.substringBeforeLast('.', "")
        override fun getShortName(): String = value.substringAfterLast('.')
    }

    private inline fun <reified T> symbol(crossinline read: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> T::class.java.simpleName
                else -> read(method.name, args)
            }
        } as T
}
