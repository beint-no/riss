package no.beint.riss.compiler

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import java.math.BigDecimal
import java.util.regex.PatternSyntaxException

internal data class ValidationModel(
    val types: List<ValidatedType>,
    val methods: List<ValidatedMethod>,
    val requiredExternalTypes: List<String>,
    val files: List<KSFile>,
)

internal data class ValidatedType(
    val qualifiedName: String,
    val typeParamCount: Int,
    val properties: List<ValidatedProperty>,
    val assertions: List<ValidatedAssertion>,
)

internal data class ValidatedProperty(
    val name: String,
    val type: KSType,
    val constraints: List<Constraint>,
    val cascade: Boolean,
    val elementTarget: ContainerValidation?,
)

internal data class ValidatedAssertion(
    val methodName: String,
    val fieldName: String,
    val expectTrue: Boolean,
    val message: String,
)

internal data class ValidatedMethod(
    val controllerQualifiedName: String,
    val methodName: String,
    val jvmParameterTypes: List<String>,
    val parameters: List<ValidatedMethodParameter>,
)

internal data class ValidatedMethodParameter(
    val index: Int,
    val fieldName: String,
    val type: KSType,
    val constraints: List<Constraint>,
    val cascade: Boolean,
    val elementTarget: ContainerValidation?,
)

internal enum class ContainerKind { ITERABLE, MAP, ARRAY }

internal data class ContainerValidation(
    val kind: ContainerKind,
    val valueType: KSType,
    val valueConstraints: List<Constraint>,
    val cascadeValue: Boolean,
)

internal sealed interface Constraint {
    val message: String

    data class NotNull(override val message: String) : Constraint
    data class NotBlank(override val message: String) : Constraint
    data class NotEmpty(override val message: String) : Constraint
    data class Size(val min: Int, val max: Int, override val message: String) : Constraint
    data class Min(val value: Long, override val message: String) : Constraint
    data class Max(val value: Long, override val message: String) : Constraint
    data class DecimalMin(val value: String, val inclusive: Boolean, override val message: String) : Constraint
    data class DecimalMax(val value: String, val inclusive: Boolean, override val message: String) : Constraint
    data class Positive(override val message: String) : Constraint
    data class PositiveOrZero(override val message: String) : Constraint
    data class Negative(override val message: String) : Constraint
    data class NegativeOrZero(override val message: String) : Constraint
    data class Digits(val integer: Int, val fraction: Int, override val message: String) : Constraint
    data class Pattern(val regexp: String, override val message: String) : Constraint
    data class Email(override val message: String) : Constraint
    data class Past(override val message: String) : Constraint
    data class PastOrPresent(override val message: String) : Constraint
    data class Future(override val message: String) : Constraint
    data class FutureOrPresent(override val message: String) : Constraint
    data class AssertTrue(override val message: String) : Constraint
    data class AssertFalse(override val message: String) : Constraint
}

internal class ValidationCompiler(
    private val diagnostics: Diagnostics,
    private val requireValidRequestBody: Boolean = false,
) {
    private val constraintCache = mutableMapOf<String, Boolean>()

    fun compile(resolver: Resolver): ValidationModel {
        val classes = linkedMapOf<String, KSClassDeclaration>()
        val files = linkedSetOf<KSFile>()
        val requiredExternalTypes = linkedSetOf<String>()
        val validatedInputClasses = linkedSetOf<KSClassDeclaration>()

        resolver.getAllFiles().forEach { file ->
            collectClasses(file.declarations.filterIsInstance<KSClassDeclaration>(), classes)
        }

        val validatedTypes = mutableListOf<ValidatedType>()
        val validatedClassDecls = linkedSetOf<KSClassDeclaration>()
        classes.values.forEach { declaration ->
            val model = inspectClass(declaration, requiredExternalTypes)
            if (model != null) {
                validatedTypes += model
                validatedClassDecls += declaration
                declaration.containingFile?.let(files::add)
            }
        }

        val controllerFunctions = Names.mappings.keys
            .asSequence()
            .flatMap { resolver.getSymbolsWithAnnotation(it) }
            .filterIsInstance<KSFunctionDeclaration>()
            .distinct()
            .toList()

        val checkedControllers = mutableSetOf<String>()
        controllerFunctions.forEach { function ->
            val controller = function.parentDeclaration as? KSClassDeclaration ?: return@forEach
            val controllerName = controller.qualifiedName?.asString() ?: return@forEach
            if (checkedControllers.add(controllerName)) {
                checkObsoleteValidated(controller, controllerName)
            }
            checkObsoleteValidated(function, "$controllerName.${function.simpleName.asString()}")
        }

        val validatedMethods = mutableListOf<ValidatedMethod>()
        controllerFunctions.forEach { function ->
            val externalBefore = requiredExternalTypes.size
            val model = inspectControllerMethod(function, requiredExternalTypes, validatedInputClasses)
            if (model != null) {
                validatedMethods += model
                function.containingFile?.let(files::add)
            } else if (requiredExternalTypes.size != externalBefore) {
                function.containingFile?.let(files::add)
            }
        }

        val nestedChecked = mutableSetOf<String>()
        val queue = ArrayDeque<KSClassDeclaration>()
        validatedClassDecls.forEach(queue::add)
        validatedInputClasses.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val declaration = queue.removeFirst()
            val qn = declaration.qualifiedName?.asString() ?: continue
            if (!nestedChecked.add(qn)) continue
            if (declaration.containingFile == null) continue
            checkMissingNestedValid(declaration, queue, requiredExternalTypes)
        }

        return ValidationModel(
            types = validatedTypes.sortedBy { it.qualifiedName },
            methods = validatedMethods.sortedWith(
                compareBy<ValidatedMethod> { it.controllerQualifiedName }
                    .thenBy { it.methodName }
                    .thenBy { it.jvmParameterTypes.joinToString(",") },
            ),
            requiredExternalTypes = requiredExternalTypes.sorted(),
            files = files.toList(),
        )
    }

    private fun collectClasses(
        declarations: Sequence<KSClassDeclaration>,
        into: MutableMap<String, KSClassDeclaration>,
    ) {
        declarations.forEach { declaration ->
            if (!isAccessible(declaration)) return@forEach
            val qn = declaration.qualifiedName?.asString() ?: return@forEach
            if (declaration.classKind == ClassKind.CLASS || declaration.classKind == ClassKind.INTERFACE) {
                into.putIfAbsent(qn, declaration)
            }
            collectClasses(declaration.declarations.filterIsInstance<KSClassDeclaration>(), into)
        }
    }

    private fun isAccessible(declaration: KSDeclaration): Boolean {
        var current: KSDeclaration? = declaration
        while (current != null) {
            if (Modifier.PRIVATE in current.modifiers || Modifier.PROTECTED in current.modifiers) {
                return false
            }
            current = current.parentDeclaration
        }
        return true
    }

    private fun inspectClass(
        declaration: KSClassDeclaration,
        requiredExternalTypes: MutableSet<String>,
    ): ValidatedType? {
        if (Modifier.VALUE in declaration.modifiers || Modifier.INLINE in declaration.modifiers) {
            return null
        }
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        val ctorParams = declaration.primaryConstructor?.parameters
            ?.associateBy { it.name?.asString().orEmpty() }
            .orEmpty()

        val properties = mutableListOf<ValidatedProperty>()
        declaration.getDeclaredProperties().forEach { property ->
            if (Modifier.PRIVATE in property.modifiers || Modifier.PROTECTED in property.modifiers) {
                return@forEach
            }
            val propName = property.simpleName.asString()
            val sources = buildList<KSAnnotated> {
                add(property)
                property.backingField?.let(::add)
                property.getter?.let(::add)
                ctorParams[propName]?.let(::add)
                add(property.type)
            }
            val type = property.type.resolve()
            if (type.isError) return@forEach

            val location = "$qualifiedName.$propName"
            val parsed = parseTarget(sources, type, location, property)
            if (parsed.cascade) {
                recordExternalIfConstrained(type, requiredExternalTypes)
            }
            if (parsed.elementTarget?.cascadeValue == true) {
                recordExternalIfConstrained(parsed.elementTarget.valueType, requiredExternalTypes)
            }
            if (parsed.constraints.isNotEmpty() || parsed.cascade || parsed.elementTarget != null) {
                properties += ValidatedProperty(
                    name = propName,
                    type = type,
                    constraints = parsed.constraints,
                    cascade = parsed.cascade,
                    elementTarget = parsed.elementTarget,
                )
            }
        }

        val assertions = mutableListOf<ValidatedAssertion>()
        declaration.getDeclaredFunctions().forEach { fn ->
            if (Modifier.PRIVATE in fn.modifiers || Modifier.PROTECTED in fn.modifiers) {
                return@forEach
            }
            val assertTrue = fn.annotationAny(Names.ASSERT_TRUE_ANNOTATIONS)
            val assertFalse = fn.annotationAny(Names.ASSERT_FALSE_ANNOTATIONS)
            if (assertTrue == null && assertFalse == null) return@forEach

            val fnName = fn.simpleName.asString()
            val location = "$qualifiedName.$fnName"
            val returnType = fn.returnType?.resolve()
            val returnName = returnType?.declaration?.qualifiedName?.asString()
            if (fn.parameters.isNotEmpty() || returnName != "kotlin.Boolean") {
                diagnostics.error(
                    "RISS-VALIDATION",
                    location,
                    "@AssertTrue/@AssertFalse method must take no parameters and return Boolean",
                    fn,
                )
                return@forEach
            }
            val fieldName = assertionFieldName(fnName)
            assertTrue?.let { ann ->
                assertions += ValidatedAssertion(
                    methodName = fnName,
                    fieldName = fieldName,
                    expectTrue = true,
                    message = constraintMessage(ann, "must be true"),
                )
            }
            assertFalse?.let { ann ->
                assertions += ValidatedAssertion(
                    methodName = fnName,
                    fieldName = fieldName,
                    expectTrue = false,
                    message = constraintMessage(ann, "must be false"),
                )
            }
        }

        if (properties.isEmpty() && assertions.isEmpty()) {
            return null
        }

        return ValidatedType(
            qualifiedName = qualifiedName,
            typeParamCount = declaration.typeParameters.size,
            properties = properties,
            assertions = assertions,
        )
    }

    private fun inspectControllerMethod(
        function: KSFunctionDeclaration,
        requiredExternalTypes: MutableSet<String>,
        validatedInputClasses: MutableSet<KSClassDeclaration>,
    ): ValidatedMethod? {
        val controller = function.parentDeclaration as? KSClassDeclaration ?: return null
        if (!isAccessible(controller) || !isAccessible(function)) return null
        val controllerName = controller.qualifiedName?.asString() ?: return null
        val methodName = function.simpleName.asString()

        val validatedParams = mutableListOf<ValidatedMethodParameter>()
        val jvmParamTypes = mutableListOf<String>()

        function.parameters.forEachIndexed { index, param ->
            val type = param.type.resolve()
            if (type.isError) return null
            jvmParamTypes += jvmClassLiteral(type)

            val isScalarBinding = param.annotation(Names.PATH_VARIABLE) != null ||
                param.annotation(Names.REQUEST_PARAM) != null ||
                param.annotation(Names.REQUEST_HEADER) != null
            val isRequestBody = param.annotation(Names.REQUEST_BODY) != null
            val isStructuredInput = isRequestBody ||
                param.annotation(Names.RISS_PARAMETER_OBJECT) != null ||
                param.annotation(Names.SPRINGDOC_PARAMETER_OBJECT) != null ||
                param.annotation(Names.REQUEST_PART) != null
            val hasDirectValid = param.hasAny(Names.VALID_ANNOTATIONS) || param.annotation(Names.VALIDATED) != null
            val container = containerKind(type)

            // Spring MVC already validates @RequestBody, @ModelAttribute, @ParameterObject, @RequestPart,
            // and implicit command objects annotated with @Valid through RissSpringValidator (including BindingResult).
            if (!isScalarBinding && hasDirectValid && container == null) {
                recordExternalIfConstrained(type, requiredExternalTypes)
                (unwrapAlias(type).declaration as? KSClassDeclaration)?.takeIf { it.containingFile != null }?.let(validatedInputClasses::add)
                return@forEachIndexed
            }

            val sources = listOf<KSAnnotated>(param, param.type)
            val fieldName = resolveParameterFieldName(param)
            val location = "$controllerName.$methodName($fieldName)"
            val parsed = parseTarget(sources, type, location, param)

            if (parsed.cascade) {
                recordExternalIfConstrained(type, requiredExternalTypes)
                (unwrapAlias(type).declaration as? KSClassDeclaration)?.takeIf { it.containingFile != null }?.let(validatedInputClasses::add)
            }
            if (parsed.elementTarget?.cascadeValue == true) {
                recordExternalIfConstrained(parsed.elementTarget.valueType, requiredExternalTypes)
                (unwrapAlias(parsed.elementTarget.valueType).declaration as? KSClassDeclaration)?.takeIf { it.containingFile != null }?.let(validatedInputClasses::add)
            }

            if (isStructuredInput && !hasDirectValid) {
                if (container != null) {
                    val valueType = containerValueType(type, container)
                    val valueDecl = valueType?.let { unwrapAlias(it).declaration as? KSClassDeclaration }
                    if (parsed.elementTarget?.cascadeValue != true) {
                        if (isRequestBody && requireValidRequestBody && isCollectionLike(type, container) &&
                            valueType != null && isStructuredRequestBodyType(valueType) && valueDecl != null
                        ) {
                            val containerName = unwrapAlias(type).declaration.simpleName.asString()
                            val typeName = "$containerName<${valueDecl.simpleName.asString()}>"
                            diagnostics.error(
                                "RISS-VALIDATION",
                                location,
                                "@RequestBody parameter '$fieldName' of structured type '$typeName' must be annotated with @Valid (use $containerName<@Valid ${valueDecl.simpleName.asString()}>)",
                                param,
                            )
                        } else if (valueDecl != null && hasConstraints(valueDecl)) {
                            diagnostics.error(
                                "RISS-VALIDATION",
                                location,
                                "Element type '${valueDecl.simpleName.asString()}' has validation constraints; add @Valid on the type argument (e.g. List<@Valid ${valueDecl.simpleName.asString()}>)",
                                param,
                            )
                        }
                    }
                } else {
                    val decl = unwrapAlias(type).declaration as? KSClassDeclaration
                    if (isRequestBody && requireValidRequestBody && isStructuredRequestBodyType(type) && decl != null) {
                        diagnostics.error(
                            "RISS-VALIDATION",
                            location,
                            "@RequestBody parameter '$fieldName' of structured type '${decl.simpleName.asString()}' must be annotated with @Valid",
                            param,
                        )
                    } else if (decl != null && hasConstraints(decl)) {
                        diagnostics.error(
                            "RISS-VALIDATION",
                            location,
                            "Parameter type '${decl.simpleName.asString()}' has validation constraints; add @Valid so Spring validates it",
                            param,
                        )
                    }
                }
            }

            // For @RequestBody/@ModelAttribute/@RequestPart without @Valid, only validate direct constraints
            // or element-level @Valid/constraints (e.g. @RequestBody stages: List<@Valid StageDto>).
            if (parsed.constraints.isNotEmpty() || parsed.cascade || parsed.elementTarget != null) {
                validatedParams += ValidatedMethodParameter(
                    index = index,
                    fieldName = fieldName,
                    type = type,
                    constraints = parsed.constraints,
                    cascade = parsed.cascade,
                    elementTarget = parsed.elementTarget,
                )
            }
        }

        if (validatedParams.isEmpty()) {
            return null
        }

        if (Modifier.SUSPEND in function.modifiers) {
            jvmParamTypes += "kotlin.coroutines.Continuation::class.java"
        }

        return ValidatedMethod(
            controllerQualifiedName = controllerName,
            methodName = methodName,
            jvmParameterTypes = jvmParamTypes,
            parameters = validatedParams,
        )
    }

    private fun checkObsoleteValidated(symbol: KSAnnotated, location: String) {
        val ann = symbol.annotation(Names.VALIDATED) ?: return
        if (ann.types("value").isEmpty()) {
            diagnostics.error(
                "RISS-VALIDATION",
                location,
                "Remove @Validated: Riss validates controller parameters at compile time without Spring AOP proxies",
                symbol,
            )
        }
    }

    private fun checkMissingNestedValid(
        declaration: KSClassDeclaration,
        queue: ArrayDeque<KSClassDeclaration>,
        requiredExternalTypes: MutableSet<String>,
    ) {
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.INTERFACE) return
        if (Modifier.VALUE in declaration.modifiers || Modifier.INLINE in declaration.modifiers) return
        val qualifiedName = declaration.qualifiedName?.asString() ?: return
        val ctorParams = declaration.primaryConstructor?.parameters
            ?.associateBy { it.name?.asString().orEmpty() }
            .orEmpty()

        declaration.getDeclaredProperties().forEach { property ->
            if (Modifier.PRIVATE in property.modifiers || Modifier.PROTECTED in property.modifiers) return@forEach
            val propName = property.simpleName.asString()
            val sources = buildList<KSAnnotated> {
                add(property)
                property.backingField?.let(::add)
                property.getter?.let(::add)
                ctorParams[propName]?.let(::add)
                add(property.type)
            }
            if (sources.any { it.hasAny(Names.JSON_IGNORE_ANNOTATIONS) }) return@forEach
            val type = property.type.resolve()
            if (type.isError) return@forEach

            val location = "$qualifiedName.$propName"
            val container = containerKind(type)
            if (container != null) {
                val valueArg = when (container) {
                    ContainerKind.ITERABLE, ContainerKind.ARRAY -> type.arguments.firstOrNull()
                    ContainerKind.MAP -> type.arguments.getOrNull(1)
                }
                val valueType = valueArg?.type?.resolve() ?: return@forEach
                if (valueType.isError) return@forEach
                val valueDecl = unwrapAlias(valueType).declaration as? KSClassDeclaration ?: return@forEach
                val hasArgValid = listOfNotNull(valueArg, valueArg.type).any { it.hasAny(Names.VALID_ANNOTATIONS) } ||
                    valueType.annotations.any { ann -> Names.VALID_ANNOTATIONS.any { ann.matches(it) } }
                if (hasArgValid) {
                    recordExternalIfConstrained(valueType, requiredExternalTypes)
                    if (valueDecl.containingFile != null) queue.add(valueDecl)
                } else if (hasConstraints(valueDecl)) {
                    diagnostics.error(
                        "RISS-VALIDATION",
                        location,
                        "Element type '${valueDecl.simpleName.asString()}' has validation constraints; add @Valid on the type argument (e.g. List<@Valid ${valueDecl.simpleName.asString()}>)",
                        property,
                    )
                }
            } else {
                val propDecl = unwrapAlias(type).declaration as? KSClassDeclaration ?: return@forEach
                val hasDirectValid = sources.any { it.hasAny(Names.VALID_ANNOTATIONS) }
                if (hasDirectValid) {
                    recordExternalIfConstrained(type, requiredExternalTypes)
                    if (propDecl.containingFile != null) queue.add(propDecl)
                } else if (hasConstraints(propDecl)) {
                    diagnostics.error(
                        "RISS-VALIDATION",
                        location,
                        "Property type '${propDecl.simpleName.asString()}' has validation constraints; add @field:Valid so nested constraints are enforced",
                        property,
                    )
                }
            }
        }
    }

    private fun containerValueType(type: KSType, container: ContainerKind): KSType? {
        val unwrapped = unwrapAlias(type)
        val arg = when (container) {
            ContainerKind.ITERABLE, ContainerKind.ARRAY -> unwrapped.arguments.firstOrNull()
            ContainerKind.MAP -> unwrapped.arguments.getOrNull(1)
        }
        return arg?.type?.resolve()?.takeUnless { it.isError }
    }

    private fun recordExternalIfConstrained(type: KSType, requiredExternalTypes: MutableSet<String>) {
        val decl = unwrapAlias(type).declaration as? KSClassDeclaration ?: return
        if (decl.containingFile != null) return
        if (!isAccessible(decl)) return
        val qn = decl.qualifiedName?.asString() ?: return
        if (hasConstraints(decl)) {
            requiredExternalTypes += qn
        }
    }

    private fun hasConstraints(declaration: KSClassDeclaration, visiting: MutableSet<String> = mutableSetOf()): Boolean {
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.INTERFACE) return false
        if (Modifier.VALUE in declaration.modifiers || Modifier.INLINE in declaration.modifiers) return false
        val qn = declaration.qualifiedName?.asString() ?: return false
        if (qn.startsWith("kotlin.") || qn.startsWith("java.") || qn.startsWith("javax.") ||
            qn.startsWith("jakarta.") || qn.startsWith("org.springframework.")
        ) {
            return false
        }
        constraintCache[qn]?.let { return it }
        if (!visiting.add(qn)) return false

        val ctorParams = declaration.primaryConstructor?.parameters
            ?.associateBy { it.name?.asString().orEmpty() }
            .orEmpty()

        val found = declaration.getDeclaredProperties().any { property ->
            if (Modifier.PRIVATE in property.modifiers || Modifier.PROTECTED in property.modifiers) return@any false
            val propName = property.simpleName.asString()
            val sources = buildList<KSAnnotated> {
                add(property)
                property.backingField?.let(::add)
                property.getter?.let(::add)
                ctorParams[propName]?.let(::add)
                add(property.type)
            }
            if (sources.any { it.hasAny(Names.JSON_IGNORE_ANNOTATIONS) }) return@any false
            if (sources.any { it.hasAny(Names.ALL_VALIDATION_ANNOTATIONS) }) return@any true
            val resolved = property.type.resolve()
            if (resolved.isError) return@any false
            val container = containerKind(resolved) ?: return@any false
            val valueArg = when (container) {
                ContainerKind.ITERABLE, ContainerKind.ARRAY -> resolved.arguments.firstOrNull()
                ContainerKind.MAP -> resolved.arguments.getOrNull(1)
            }
            val valueType = valueArg?.type?.resolve()
            listOfNotNull(valueArg, valueArg?.type).any { it.hasAny(Names.ALL_VALIDATION_ANNOTATIONS) } ||
                (valueType != null && !valueType.isError && valueType.annotations.any { ann ->
                    Names.ALL_VALIDATION_ANNOTATIONS.any { ann.matches(it) }
                })
        } || declaration.getDeclaredFunctions().any { fn ->
            if (Modifier.PRIVATE in fn.modifiers || Modifier.PROTECTED in fn.modifiers) return@any false
            fn.parameters.isEmpty() && (
                fn.annotationAny(Names.ASSERT_TRUE_ANNOTATIONS) != null ||
                    fn.annotationAny(Names.ASSERT_FALSE_ANNOTATIONS) != null
                )
        } || declaration.superTypes.any { superRef ->
            val superDecl = superRef.resolve().declaration as? KSClassDeclaration ?: return@any false
            hasConstraints(superDecl, visiting)
        }

        visiting.remove(qn)
        constraintCache[qn] = found
        return found
    }

    private data class ParsedTarget(
        val constraints: List<Constraint>,
        val cascade: Boolean,
        val elementTarget: ContainerValidation?,
    )

    private fun parseTarget(
        sources: List<KSAnnotated>,
        type: KSType,
        location: String,
        symbol: KSAnnotated,
    ): ParsedTarget {
        val constraints = extractConstraints(sources, type, location, symbol)
        val containerKind = containerKind(type)
        val hasDirectValid = sources.any { it.hasAny(Names.VALID_ANNOTATIONS) }

        var cascade = false
        var elementTarget: ContainerValidation? = null

        if (containerKind != null) {
            val valueArg: KSTypeArgument? = when (containerKind) {
                ContainerKind.ITERABLE, ContainerKind.ARRAY -> type.arguments.firstOrNull()
                ContainerKind.MAP -> type.arguments.getOrNull(1)
            }
            val valueType = valueArg?.type?.resolve()
            val argSources = buildList<KSAnnotated> {
                valueArg?.let(::add)
                valueArg?.type?.let(::add)
            }
            val argAnnotations = buildList {
                argSources.forEach { addAll(it.annotations) }
                valueType?.annotations?.let(::addAll)
            }
            val argCascade = argAnnotations.any { ann ->
                Names.VALID_ANNOTATIONS.any { ann.matches(it) }
            }
            val argConstraints = if (valueType != null && !valueType.isError) {
                extractConstraintsFromAnnotations(argAnnotations, valueType, "$location[]", symbol)
            } else {
                emptyList()
            }

            if (hasDirectValid) {
                if (argCascade) {
                    diagnostics.error(
                        "RISS-VALIDATION",
                        location,
                        "Remove redundant @Valid on container; keep @Valid only on its type argument",
                        symbol,
                    )
                } else {
                    diagnostics.error(
                        "RISS-VALIDATION",
                        location,
                        "Place @Valid on the container type argument (e.g. List<@Valid T>) instead of the container itself",
                        symbol,
                    )
                }
            }

            if ((argCascade || argConstraints.isNotEmpty()) && valueType != null && !valueType.isError) {
                elementTarget = ContainerValidation(
                    kind = containerKind,
                    valueType = valueType,
                    valueConstraints = argConstraints,
                    cascadeValue = argCascade,
                )
            }
        } else if (hasDirectValid) {
            cascade = true
        }

        return ParsedTarget(constraints, cascade, elementTarget)
    }

    private fun extractConstraints(
        sources: List<KSAnnotated>,
        type: KSType,
        location: String,
        symbol: KSAnnotated,
    ): List<Constraint> {
        val allAnnotations = sources.flatMap { it.annotations.toList() }
        return extractConstraintsFromAnnotations(allAnnotations, type, location, symbol)
    }

    private fun extractConstraintsFromAnnotations(
        annotations: List<KSAnnotation>,
        type: KSType,
        location: String,
        symbol: KSAnnotated,
    ): List<Constraint> {
        val result = mutableListOf<Constraint>()
        val seenKinds = mutableSetOf<String>()

        fun first(names: List<String>): KSAnnotation? =
            annotations.firstOrNull { ann -> names.any { ann.matches(it) } }

        first(Names.NOT_NULL_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("NotNull")) {
                result += Constraint.NotNull(constraintMessage(ann, "must not be null"))
            }
        }

        first(Names.NOT_BLANK_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("NotBlank")) {
                requireType(isCharSequence(type), "@NotBlank requires a String or CharSequence", location, symbol)
                result += Constraint.NotBlank(constraintMessage(ann, "must not be blank"))
            }
        }

        first(Names.NOT_EMPTY_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("NotEmpty")) {
                requireType(
                    isCharSequence(type) || containerKind(type) != null,
                    "@NotEmpty requires a String, CharSequence, Collection, Map, or Array",
                    location,
                    symbol,
                )
                result += Constraint.NotEmpty(constraintMessage(ann, "must not be empty"))
            }
        }

        first(Names.SIZE_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Size")) {
                requireType(
                    isCharSequence(type) || containerKind(type) != null,
                    "@Size requires a String, CharSequence, Collection, Map, or Array",
                    location,
                    symbol,
                )
                val min = ann.int("min") ?: 0
                val max = ann.int("max") ?: Int.MAX_VALUE
                if (min < 0 || max < 0 || min > max) {
                    diagnostics.error("RISS-VALIDATION", location, "@Size bounds are invalid: min=$min, max=$max", symbol)
                } else {
                    result += Constraint.Size(min, max, constraintMessage(ann, "size must be between $min and $max"))
                }
            }
        }

        first(Names.MIN_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Min")) {
                requireType(isNumeric(type), "@Min requires a numeric type", location, symbol)
                val value = ann.long("value") ?: 0L
                result += Constraint.Min(value, constraintMessage(ann, "must be greater than or equal to $value"))
            }
        }

        first(Names.MAX_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Max")) {
                requireType(isNumeric(type), "@Max requires a numeric type", location, symbol)
                val value = ann.long("value") ?: Long.MAX_VALUE
                result += Constraint.Max(value, constraintMessage(ann, "must be less than or equal to $value"))
            }
        }

        first(Names.DECIMAL_MIN_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("DecimalMin")) {
                requireType(isNumeric(type) || isCharSequence(type), "@DecimalMin requires a numeric or CharSequence type", location, symbol)
                val raw = ann.string("value")
                val inclusive = ann.bool("inclusive") ?: true
                if (raw == null || !isValidBigDecimal(raw)) {
                    diagnostics.error("RISS-VALIDATION", location, "@DecimalMin value '$raw' is not a valid decimal number", symbol)
                } else {
                    val defaultMsg = if (inclusive) "must be greater than or equal to $raw" else "must be greater than $raw"
                    result += Constraint.DecimalMin(raw, inclusive, constraintMessage(ann, defaultMsg))
                }
            }
        }

        first(Names.DECIMAL_MAX_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("DecimalMax")) {
                requireType(isNumeric(type) || isCharSequence(type), "@DecimalMax requires a numeric or CharSequence type", location, symbol)
                val raw = ann.string("value")
                val inclusive = ann.bool("inclusive") ?: true
                if (raw == null || !isValidBigDecimal(raw)) {
                    diagnostics.error("RISS-VALIDATION", location, "@DecimalMax value '$raw' is not a valid decimal number", symbol)
                } else {
                    val defaultMsg = if (inclusive) "must be less than or equal to $raw" else "must be less than $raw"
                    result += Constraint.DecimalMax(raw, inclusive, constraintMessage(ann, defaultMsg))
                }
            }
        }

        first(Names.POSITIVE_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Positive")) {
                requireType(isNumeric(type), "@Positive requires a numeric type", location, symbol)
                result += Constraint.Positive(constraintMessage(ann, "must be greater than 0"))
            }
        }

        first(Names.POSITIVE_OR_ZERO_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("PositiveOrZero")) {
                requireType(isNumeric(type), "@PositiveOrZero requires a numeric type", location, symbol)
                result += Constraint.PositiveOrZero(constraintMessage(ann, "must be greater than or equal to 0"))
            }
        }

        first(Names.NEGATIVE_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Negative")) {
                requireType(isNumeric(type), "@Negative requires a numeric type", location, symbol)
                result += Constraint.Negative(constraintMessage(ann, "must be less than 0"))
            }
        }

        first(Names.NEGATIVE_OR_ZERO_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("NegativeOrZero")) {
                requireType(isNumeric(type), "@NegativeOrZero requires a numeric type", location, symbol)
                result += Constraint.NegativeOrZero(constraintMessage(ann, "must be less than or equal to 0"))
            }
        }

        first(Names.DIGITS_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Digits")) {
                requireType(isNumeric(type) || isCharSequence(type), "@Digits requires a numeric or CharSequence type", location, symbol)
                val integer = ann.int("integer") ?: -1
                val fraction = ann.int("fraction") ?: -1
                if (integer < 0 || fraction < 0) {
                    diagnostics.error("RISS-VALIDATION", location, "@Digits bounds must be non-negative: integer=$integer, fraction=$fraction", symbol)
                } else {
                    result += Constraint.Digits(
                        integer,
                        fraction,
                        constraintMessage(ann, "numeric value out of bounds (<$integer digits>.<$fraction digits> expected)"),
                    )
                }
            }
        }

        first(Names.PATTERN_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Pattern")) {
                requireType(isCharSequence(type), "@Pattern requires a String or CharSequence", location, symbol)
                val regexp = ann.string("regexp")
                if (regexp == null) {
                    diagnostics.error("RISS-VALIDATION", location, "@Pattern requires a non-blank regexp", symbol)
                } else {
                    try {
                        java.util.regex.Pattern.compile(regexp)
                        result += Constraint.Pattern(regexp, constraintMessage(ann, "must match \"$regexp\""))
                    } catch (ex: PatternSyntaxException) {
                        diagnostics.error("RISS-VALIDATION", location, "@Pattern has invalid regex '$regexp': ${ex.description}", symbol)
                    }
                }
            }
        }

        first(Names.EMAIL_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Email")) {
                requireType(isCharSequence(type), "@Email requires a String or CharSequence", location, symbol)
                result += Constraint.Email(constraintMessage(ann, "must be a well-formed email address"))
            }
        }

        first(Names.PAST_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Past")) {
                requireType(isTemporal(type), "@Past requires a date/time type", location, symbol)
                result += Constraint.Past(constraintMessage(ann, "must be a past date"))
            }
        }

        first(Names.PAST_OR_PRESENT_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("PastOrPresent")) {
                requireType(isTemporal(type), "@PastOrPresent requires a date/time type", location, symbol)
                result += Constraint.PastOrPresent(constraintMessage(ann, "must be a date in the past or in the present"))
            }
        }

        first(Names.FUTURE_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("Future")) {
                requireType(isTemporal(type), "@Future requires a date/time type", location, symbol)
                result += Constraint.Future(constraintMessage(ann, "must be a future date"))
            }
        }

        first(Names.FUTURE_OR_PRESENT_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("FutureOrPresent")) {
                requireType(isTemporal(type), "@FutureOrPresent requires a date/time type", location, symbol)
                result += Constraint.FutureOrPresent(constraintMessage(ann, "must be a date in the present or in the future"))
            }
        }

        first(Names.ASSERT_TRUE_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("AssertTrue")) {
                requireType(isBoolean(type), "@AssertTrue requires a Boolean type", location, symbol)
                result += Constraint.AssertTrue(constraintMessage(ann, "must be true"))
            }
        }

        first(Names.ASSERT_FALSE_ANNOTATIONS)?.let { ann ->
            if (seenKinds.add("AssertFalse")) {
                requireType(isBoolean(type), "@AssertFalse requires a Boolean type", location, symbol)
                result += Constraint.AssertFalse(constraintMessage(ann, "must be false"))
            }
        }

        return result
    }

    private fun requireType(ok: Boolean, message: String, location: String, symbol: KSAnnotated) {
        if (!ok) {
            diagnostics.error("RISS-VALIDATION", location, message, symbol)
        }
    }

    private fun constraintMessage(annotation: KSAnnotation, defaultMessage: String): String {
        val raw = annotation.string("message") ?: return defaultMessage
        if (raw.startsWith("{jakarta.validation.constraints.")) return defaultMessage
        return raw
    }

    private fun resolveParameterFieldName(param: KSValueParameter): String {
        for (binding in listOf(Names.PATH_VARIABLE, Names.REQUEST_PARAM, Names.REQUEST_HEADER, Names.REQUEST_PART)) {
            val ann = param.annotation(binding) ?: continue
            val explicit = ann.string("name") ?: ann.string("value")
            if (explicit != null) return explicit
        }
        return param.name?.asString() ?: "arg"
    }

    private fun assertionFieldName(methodName: String): String = when {
        methodName.startsWith("is") && methodName.length > 2 && methodName[2].isUpperCase() ->
            methodName[2].lowercaseChar() + methodName.substring(3)
        methodName.startsWith("get") && methodName.length > 3 && methodName[3].isUpperCase() ->
            methodName[3].lowercaseChar() + methodName.substring(4)
        else -> methodName
    }

    private fun isValidBigDecimal(raw: String): Boolean = try {
        BigDecimal(raw)
        true
    } catch (_: NumberFormatException) {
        false
    }

    private fun unwrapAlias(type: KSType): KSType {
        var current = type
        val seen = mutableSetOf<String>()
        while (current.declaration is KSTypeAlias) {
            val alias = current.declaration as KSTypeAlias
            val name = alias.qualifiedName?.asString() ?: break
            if (!seen.add(name)) break
            val resolved = alias.type.resolve()
            if (resolved.isError) break
            current = if (current.nullability == Nullability.NULLABLE) resolved.makeNullable() else resolved
        }
        return current
    }

    private fun containerKind(rawType: KSType): ContainerKind? {
        val type = unwrapAlias(rawType)
        val qn = type.declaration.qualifiedName?.asString() ?: return null
        return when (qn) {
            in ITERABLE_TYPES -> ContainerKind.ITERABLE
            in MAP_TYPES -> ContainerKind.MAP
            "kotlin.Array", "kotlin.ByteArray", "kotlin.ShortArray", "kotlin.IntArray",
            "kotlin.LongArray", "kotlin.FloatArray", "kotlin.DoubleArray",
            "kotlin.BooleanArray", "kotlin.CharArray" -> ContainerKind.ARRAY
            else -> null
        }
    }

    private fun isCollectionLike(rawType: KSType, container: ContainerKind): Boolean = when (container) {
        ContainerKind.ITERABLE -> true
        ContainerKind.ARRAY -> unwrapAlias(rawType).declaration.qualifiedName?.asString() == "kotlin.Array"
        ContainerKind.MAP -> false
    }

    private fun isStructuredRequestBodyType(rawType: KSType): Boolean {
        val type = unwrapAlias(rawType)
        val decl = type.declaration as? KSClassDeclaration ?: return false
        if (decl.classKind != ClassKind.CLASS && decl.classKind != ClassKind.INTERFACE) return false
        if (Modifier.VALUE in decl.modifiers || Modifier.INLINE in decl.modifiers) return false
        val qn = decl.qualifiedName?.asString() ?: return false
        return UNSTRUCTURED_REQUEST_BODY_PREFIXES.none { qn.startsWith(it) }
    }

    private fun isCharSequence(type: KSType): Boolean =
        unwrapAlias(type).declaration.qualifiedName?.asString() in CHAR_SEQUENCE_TYPES

    private fun isNumeric(type: KSType): Boolean =
        unwrapAlias(type).declaration.qualifiedName?.asString() in NUMERIC_TYPES

    private fun isTemporal(type: KSType): Boolean =
        unwrapAlias(type).declaration.qualifiedName?.asString() in TEMPORAL_TYPES

    private fun isBoolean(type: KSType): Boolean {
        val qn = unwrapAlias(type).declaration.qualifiedName?.asString()
        return qn == "kotlin.Boolean" || qn == "java.lang.Boolean"
    }

    private fun jvmClassLiteral(rawType: KSType): String {
        val type = unwrapAlias(rawType)
        val decl = type.declaration
        if (decl is KSTypeParameter) {
            val bound = decl.bounds.firstOrNull()?.resolve()
            return if (bound != null && !bound.isError) jvmClassLiteral(bound) else "Any::class.java"
        }
        val qn = decl.qualifiedName?.asString() ?: return "Any::class.java"
        val nullable = type.nullability == Nullability.NULLABLE
        return when (qn) {
            "kotlin.Int" -> if (nullable) "Int::class.javaObjectType" else "Int::class.javaPrimitiveType!!"
            "kotlin.Long" -> if (nullable) "Long::class.javaObjectType" else "Long::class.javaPrimitiveType!!"
            "kotlin.Short" -> if (nullable) "Short::class.javaObjectType" else "Short::class.javaPrimitiveType!!"
            "kotlin.Byte" -> if (nullable) "Byte::class.javaObjectType" else "Byte::class.javaPrimitiveType!!"
            "kotlin.Double" -> if (nullable) "Double::class.javaObjectType" else "Double::class.javaPrimitiveType!!"
            "kotlin.Float" -> if (nullable) "Float::class.javaObjectType" else "Float::class.javaPrimitiveType!!"
            "kotlin.Boolean" -> if (nullable) "Boolean::class.javaObjectType" else "Boolean::class.javaPrimitiveType!!"
            "kotlin.Char" -> if (nullable) "Char::class.javaObjectType" else "Char::class.javaPrimitiveType!!"
            "kotlin.String" -> "String::class.java"
            "kotlin.Any" -> "Any::class.java"
            "kotlin.collections.List", "kotlin.collections.MutableList" -> "List::class.java"
            "kotlin.collections.Set", "kotlin.collections.MutableSet" -> "Set::class.java"
            "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "Collection::class.java"
            "kotlin.collections.Iterable", "kotlin.collections.MutableIterable" -> "Iterable::class.java"
            "kotlin.collections.Map", "kotlin.collections.MutableMap" -> "Map::class.java"
            "kotlin.Array" -> {
                val arg = type.arguments.firstOrNull()?.type?.resolve()
                val elemQn = arg?.declaration?.qualifiedName?.asString() ?: "Any"
                val kotlinElem = when (elemQn) {
                    "kotlin.String" -> "String"
                    "kotlin.Int" -> "Int"
                    "kotlin.Long" -> "Long"
                    "kotlin.Any" -> "Any"
                    else -> elemQn
                }
                "Array<$kotlinElem>::class.java"
            }
            "kotlin.ByteArray" -> "ByteArray::class.java"
            "kotlin.ShortArray" -> "ShortArray::class.java"
            "kotlin.IntArray" -> "IntArray::class.java"
            "kotlin.LongArray" -> "LongArray::class.java"
            "kotlin.FloatArray" -> "FloatArray::class.java"
            "kotlin.DoubleArray" -> "DoubleArray::class.java"
            "kotlin.BooleanArray" -> "BooleanArray::class.java"
            "kotlin.CharArray" -> "CharArray::class.java"
            else -> "$qn::class.java"
        }
    }

    private companion object {
        val CHAR_SEQUENCE_TYPES = setOf(
            "kotlin.String",
            "kotlin.CharSequence",
            "java.lang.String",
            "java.lang.CharSequence",
        )
        val NUMERIC_TYPES = setOf(
            "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
            "kotlin.Float", "kotlin.Double", "kotlin.Number",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double", "java.lang.Number",
            "java.math.BigDecimal", "java.math.BigInteger",
        )
        val TEMPORAL_TYPES = setOf(
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Instant",
            "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.YearMonth",
            "java.time.Year", "java.time.LocalTime", "java.time.OffsetTime",
            "java.util.Date",
        )
        val ITERABLE_TYPES = setOf(
            "kotlin.collections.List", "kotlin.collections.MutableList",
            "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
            "kotlin.collections.Iterable", "kotlin.collections.MutableIterable",
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable",
            "java.util.ArrayList", "java.util.LinkedHashSet", "java.util.HashSet",
        )
        val MAP_TYPES = setOf(
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "java.util.Map", "java.util.LinkedHashMap", "java.util.HashMap",
        )
        val UNSTRUCTURED_REQUEST_BODY_PREFIXES = listOf(
            "kotlin.",
            "java.",
            "javax.",
            "jakarta.",
            "org.springframework.",
            "com.fasterxml.jackson.",
            "tools.jackson.",
            "kotlinx.serialization.json.",
        )
    }
}
