package no.beint.riss.compiler

internal class Diagnostics {
    private val errors = mutableListOf<String>()

    val problems: List<String> get() = errors

    fun error(code: String, location: String?, message: String) {
        errors += if (location.isNullOrBlank()) {
            "$code $message"
        } else {
            "$code $location: $message"
        }
    }

    fun error(code: String, message: String) = error(code, null, message)

    fun isEmpty(): Boolean = errors.isEmpty()
}
