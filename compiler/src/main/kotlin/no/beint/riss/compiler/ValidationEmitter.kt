package no.beint.riss.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import java.nio.charset.StandardCharsets

internal class ValidationEmitter(
    private val codeGenerator: CodeGenerator,
    private val generatedPackage: String,
    private val registryName: String,
) {
    fun emit(model: ValidationModel) {
        if (model.types.isEmpty() && model.methods.isEmpty() && model.requiredExternalTypes.isEmpty()) {
            return
        }
        val dependencies = Dependencies(aggregating = true, *model.files.toTypedArray())

        val patterns = linkedMapOf<String, String>()
        val decimals = linkedMapOf<String, String>()

        fun patternConst(regexp: String): String =
            patterns.getOrPut(regexp) { "PATTERN_${patterns.size}" }

        fun decimalConst(value: String): String =
            decimals.getOrPut(value) { "DEC_${decimals.size}" }

        val typeBodies = model.types.mapIndexed { index, type ->
            val fnName = "validateType$index"
            val body = buildString {
                val starArgs = if (type.typeParamCount > 0) {
                    "<" + List(type.typeParamCount) { "*" }.joinToString(", ") + ">"
                } else {
                    ""
                }
                appendLine("    private fun $fnName(raw: Any, prefix: String, sink: ValidationSink, root: RissValidator) {")
                appendLine("        val target = raw as ${type.qualifiedName}$starArgs")
                type.properties.forEachIndexed { pIdx, prop ->
                    val varName = "v$pIdx"
                    val pathExpr = "ValidationSupport.path(prefix, ${ktString(prop.name)})"
                    appendLine("        val $varName: Any? = target.${ktIdent(prop.name)}")
                    emitValueChecks(
                        out = this,
                        indent = "        ",
                        varName = varName,
                        pathExpr = pathExpr,
                        constraints = prop.constraints,
                        cascade = prop.cascade,
                        elementTarget = prop.elementTarget,
                        patternConst = ::patternConst,
                        decimalConst = ::decimalConst,
                    )
                }
                type.assertions.forEach { assertion ->
                    val cond = if (assertion.expectTrue) "target.${ktIdent(assertion.methodName)}() == false" else "target.${ktIdent(assertion.methodName)}() == true"
                    appendLine("        if ($cond) {")
                    appendLine("            sink.reject(ValidationSupport.path(prefix, ${ktString(assertion.fieldName)}), ${ktString(assertion.message)})")
                    appendLine("        }")
                }
                appendLine("    }")
            }
            Triple(type, fnName, body)
        }

        val methodBodies = model.methods.mapIndexed { index, method ->
            val fnName = "validateMethod$index"
            val body = buildString {
                appendLine("    private fun $fnName(args: Array<Any?>, sink: ValidationSink, root: RissValidator) {")
                method.parameters.forEachIndexed { pIdx, param ->
                    val varName = "p$pIdx"
                    val pathExpr = ktString(param.fieldName)
                    appendLine("        val $varName: Any? = if (${param.index} < args.size) args[${param.index}] else null")
                    emitValueChecks(
                        out = this,
                        indent = "        ",
                        varName = varName,
                        pathExpr = pathExpr,
                        constraints = param.constraints,
                        cascade = param.cascade,
                        elementTarget = param.elementTarget,
                        patternConst = ::patternConst,
                        decimalConst = ::decimalConst,
                    )
                }
                appendLine("    }")
            }
            Triple(method, fnName, body)
        }

        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "kt",
        ).bufferedWriter(StandardCharsets.UTF_8).use { out ->
            out.appendLine("@file:Suppress(\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\", \"REDUNDANT_CAST\", \"USELESS_CAST\", \"DEPRECATION\")")
            out.appendLine("package $generatedPackage")
            out.appendLine()
            out.appendLine("import java.math.BigDecimal")
            out.appendLine("import no.beint.riss.validation.MethodParameterValidator")
            out.appendLine("import no.beint.riss.validation.MethodValidatorEntry")
            out.appendLine("import no.beint.riss.validation.RissValidator")
            out.appendLine("import no.beint.riss.validation.TypeValidator")
            out.appendLine("import no.beint.riss.validation.ValidationSink")
            out.appendLine("import no.beint.riss.validation.ValidationSupport")
            out.appendLine("import no.beint.riss.validation.ValidatorSet")
            out.appendLine()
            out.appendLine("public class $registryName : ValidatorSet {")
            out.appendLine("    override fun typeValidators(): Map<Class<*>, TypeValidator> {")
            if (typeBodies.isEmpty()) {
                out.appendLine("        return emptyMap()")
            } else {
                out.appendLine("        val map = HashMap<Class<*>, TypeValidator>(${typeBodies.size * 2})")
                typeBodies.forEach { (type, fnName, _) ->
                    out.appendLine("        map[${type.qualifiedName}::class.java] = TypeValidator(::${fnName})")
                }
                out.appendLine("        return map")
            }
            out.appendLine("    }")
            out.appendLine()
            out.appendLine("    override fun methodValidators(): List<MethodValidatorEntry> {")
            if (methodBodies.isEmpty()) {
                out.appendLine("        return emptyList()")
            } else {
                out.appendLine("        val list = ArrayList<MethodValidatorEntry>(${methodBodies.size})")
                methodBodies.forEach { (method, fnName, _) ->
                    val paramClasses = method.jvmParameterTypes.joinToString(", ")
                    out.appendLine(
                        "        list.add(MethodValidatorEntry(${method.controllerQualifiedName}::class.java, ${ktString(method.methodName)}, arrayOf<Class<*>>($paramClasses), MethodParameterValidator(::${fnName})))",
                    )
                }
                out.appendLine("        return list")
            }
            out.appendLine("    }")
            out.appendLine()
            if (model.requiredExternalTypes.isNotEmpty()) {
                out.appendLine("    override fun requiredTypes(): List<Class<*>> = listOf(")
                model.requiredExternalTypes.forEach { qn ->
                    out.appendLine("        $qn::class.java,")
                }
                out.appendLine("    )")
                out.appendLine()
            }
            typeBodies.forEach { (_, _, body) ->
                out.append(body)
                out.appendLine()
            }
            methodBodies.forEach { (_, _, body) ->
                out.append(body)
                out.appendLine()
            }
            if (patterns.isNotEmpty() || decimals.isNotEmpty()) {
                out.appendLine("    private companion object {")
                patterns.forEach { (regexp, constName) ->
                    out.appendLine("        val $constName: java.util.regex.Pattern = java.util.regex.Pattern.compile(${ktString(regexp)})")
                }
                decimals.forEach { (value, constName) ->
                    out.appendLine("        val $constName: BigDecimal = BigDecimal(${ktString(value)})")
                }
                out.appendLine("    }")
            }
            out.appendLine("}")
        }

        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = "META-INF/services/no.beint.riss.validation.ValidatorSet",
            extensionName = "",
        ).bufferedWriter(StandardCharsets.UTF_8).use { out ->
            out.appendLine("$generatedPackage.$registryName")
        }
    }

    private fun emitValueChecks(
        out: StringBuilder,
        indent: String,
        varName: String,
        pathExpr: String,
        constraints: List<Constraint>,
        cascade: Boolean,
        elementTarget: ContainerValidation?,
        patternConst: (String) -> String,
        decimalConst: (String) -> String,
    ) {
        val nullRejecting = constraints.filter {
            it is Constraint.NotNull || it is Constraint.NotBlank || it is Constraint.NotEmpty
        }
        val nonNullOnly = constraints.filter {
            it !is Constraint.NotNull && it !is Constraint.NotBlank && it !is Constraint.NotEmpty
        }

        nullRejecting.forEach { constraint ->
            when (constraint) {
                is Constraint.NotNull -> {
                    out.appendLine("${indent}if ($varName == null) {")
                    out.appendLine("$indent    sink.reject($pathExpr, ${ktString(constraint.message)})")
                    out.appendLine("$indent}")
                }
                is Constraint.NotBlank -> {
                    out.appendLine("${indent}if ($varName !is CharSequence || !ValidationSupport.isNotBlank($varName)) {")
                    out.appendLine("$indent    sink.reject($pathExpr, ${ktString(constraint.message)})")
                    out.appendLine("$indent}")
                }
                is Constraint.NotEmpty -> {
                    out.appendLine("${indent}if (!ValidationSupport.isNotEmpty($varName)) {")
                    out.appendLine("$indent    sink.reject($pathExpr, ${ktString(constraint.message)})")
                    out.appendLine("$indent}")
                }
                else -> {}
            }
        }

        if (nonNullOnly.isNotEmpty() || cascade || elementTarget != null) {
            out.appendLine("${indent}if ($varName != null) {")
            val inner = "$indent    "
            nonNullOnly.forEach { constraint ->
                emitNonNullConstraint(out, inner, varName, pathExpr, constraint, patternConst, decimalConst)
            }
            if (cascade) {
                out.appendLine("${inner}root.validate($varName, $pathExpr, sink)")
            }
            if (elementTarget != null) {
                emitContainerChecks(out, inner, varName, pathExpr, elementTarget, patternConst, decimalConst)
            }
            out.appendLine("$indent}")
        }
    }

    private fun emitContainerChecks(
        out: StringBuilder,
        indent: String,
        varName: String,
        pathExpr: String,
        target: ContainerValidation,
        patternConst: (String) -> String,
        decimalConst: (String) -> String,
    ) {
        val elemVar = "${varName}_e"
        val elemPathVar = "${varName}_p"
        val basePathVar = "${varName}_base"
        val eagerPath = target.cascadeValue
        if (eagerPath) {
            out.appendLine("${indent}val $basePathVar = $pathExpr")
        }
        val baseExpr = if (eagerPath) basePathVar else pathExpr
        when (target.kind) {
            ContainerKind.ITERABLE -> {
                val idxVar = "${varName}_i"
                out.appendLine("${indent}var $idxVar = 0")
                out.appendLine("${indent}for ($elemVar in ($varName as Iterable<*>)) {")
                val elemPathExpr = if (eagerPath) {
                    out.appendLine("$indent    val $elemPathVar = ValidationSupport.indexedPath($baseExpr, $idxVar)")
                    elemPathVar
                } else {
                    "ValidationSupport.indexedPath($baseExpr, $idxVar)"
                }
                emitValueChecks(
                    out = out,
                    indent = "$indent    ",
                    varName = elemVar,
                    pathExpr = elemPathExpr,
                    constraints = target.valueConstraints,
                    cascade = target.cascadeValue,
                    elementTarget = null,
                    patternConst = patternConst,
                    decimalConst = decimalConst,
                )
                out.appendLine("$indent    $idxVar++")
                out.appendLine("$indent}")
            }
            ContainerKind.ARRAY -> {
                val idxVar = "${varName}_i"
                out.appendLine("${indent}val ${varName}_arr = $varName as Array<*>")
                out.appendLine("${indent}for ($idxVar in ${varName}_arr.indices) {")
                out.appendLine("$indent    val $elemVar: Any? = ${varName}_arr[$idxVar]")
                val elemPathExpr = if (eagerPath) {
                    out.appendLine("$indent    val $elemPathVar = ValidationSupport.indexedPath($baseExpr, $idxVar)")
                    elemPathVar
                } else {
                    "ValidationSupport.indexedPath($baseExpr, $idxVar)"
                }
                emitValueChecks(
                    out = out,
                    indent = "$indent    ",
                    varName = elemVar,
                    pathExpr = elemPathExpr,
                    constraints = target.valueConstraints,
                    cascade = target.cascadeValue,
                    elementTarget = null,
                    patternConst = patternConst,
                    decimalConst = decimalConst,
                )
                out.appendLine("$indent}")
            }
            ContainerKind.MAP -> {
                val entryVar = "${varName}_entry"
                out.appendLine("${indent}for ($entryVar in ($varName as Map<*, *>).entries) {")
                out.appendLine("$indent    val $elemVar: Any? = $entryVar.value")
                val elemPathExpr = if (eagerPath) {
                    out.appendLine("$indent    val $elemPathVar = ValidationSupport.mapPath($baseExpr, $entryVar.key)")
                    elemPathVar
                } else {
                    "ValidationSupport.mapPath($baseExpr, $entryVar.key)"
                }
                emitValueChecks(
                    out = out,
                    indent = "$indent    ",
                    varName = elemVar,
                    pathExpr = elemPathExpr,
                    constraints = target.valueConstraints,
                    cascade = target.cascadeValue,
                    elementTarget = null,
                    patternConst = patternConst,
                    decimalConst = decimalConst,
                )
                out.appendLine("$indent}")
            }
        }
    }

    private fun emitNonNullConstraint(
        out: StringBuilder,
        indent: String,
        varName: String,
        pathExpr: String,
        constraint: Constraint,
        patternConst: (String) -> String,
        decimalConst: (String) -> String,
    ) {
        val cond = when (constraint) {
            is Constraint.Size -> {
                val lenVar = "${varName}_len"
                out.appendLine("${indent}val $lenVar = ValidationSupport.lengthOf($varName)")
                "$lenVar < ${constraint.min} || $lenVar > ${constraint.max}"
            }
            is Constraint.Min -> "!ValidationSupport.minLong($varName, ${constraint.value}L)"
            is Constraint.Max -> "!ValidationSupport.maxLong($varName, ${constraint.value}L)"
            is Constraint.DecimalMin -> "!ValidationSupport.decimalMin($varName, ${decimalConst(constraint.value)}, ${constraint.inclusive})"
            is Constraint.DecimalMax -> "!ValidationSupport.decimalMax($varName, ${decimalConst(constraint.value)}, ${constraint.inclusive})"
            is Constraint.Positive -> "!ValidationSupport.isPositive($varName)"
            is Constraint.PositiveOrZero -> "!ValidationSupport.isPositiveOrZero($varName)"
            is Constraint.Negative -> "!ValidationSupport.isNegative($varName)"
            is Constraint.NegativeOrZero -> "!ValidationSupport.isNegativeOrZero($varName)"
            is Constraint.Digits -> "!ValidationSupport.checkDigits($varName, ${constraint.integer}, ${constraint.fraction})"
            is Constraint.Pattern -> "!${patternConst(constraint.regexp)}.matcher($varName as CharSequence).matches()"
            is Constraint.Email -> "!ValidationSupport.isValidOptionalEmail($varName as CharSequence)"
            is Constraint.Past -> "!ValidationSupport.isPast($varName)"
            is Constraint.PastOrPresent -> "!ValidationSupport.isPastOrPresent($varName)"
            is Constraint.Future -> "!ValidationSupport.isFuture($varName)"
            is Constraint.FutureOrPresent -> "!ValidationSupport.isFutureOrPresent($varName)"
            is Constraint.AssertTrue -> "$varName == false"
            is Constraint.AssertFalse -> "$varName == true"
            is Constraint.NotNull, is Constraint.NotBlank, is Constraint.NotEmpty -> return
        }
        out.appendLine("${indent}if ($cond) {")
        out.appendLine("$indent    sink.reject($pathExpr, ${ktString(constraint.message)})")
        out.appendLine("$indent}")
    }

    private fun ktIdent(name: String): String =
        if (name in KOTLIN_KEYWORDS) "`$name`" else name

    private fun ktString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> append(c)
            }
        }
        append('"')
    }

    private companion object {
        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )
    }
}
