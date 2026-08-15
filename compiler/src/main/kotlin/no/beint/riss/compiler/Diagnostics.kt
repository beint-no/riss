package no.beint.riss.compiler

import com.google.devtools.ksp.symbol.KSNode

internal class Diagnostics {
    private val errors = mutableListOf<Problem>()

    val problems: List<Problem> get() = errors

    fun error(code: String, message: String, symbol: KSNode? = null) {
        error(code, null, message, symbol)
    }

    fun error(code: String, location: String?, message: String, symbol: KSNode? = null) {
        val text = if (location.isNullOrBlank()) {
            "$code $message"
        } else {
            "$code $location: $message"
        }
        errors += Problem(text, symbol)
    }

    fun isEmpty(): Boolean = errors.isEmpty()

    data class Problem(
        val message: String,
        val symbol: KSNode?,
    )
}
