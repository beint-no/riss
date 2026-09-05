package no.beint.riss.compiler

internal object PathPatterns {
    private val REPEATED_SLASHES = Regex("/+")

    fun combine(prefix: String, path: String): String {
        val joined = "/${prefix.trim('/')}/${path.trim('/')}".replace(REPEATED_SLASHES, "/")
        return if (joined.length > 1) joined.trimEnd('/') else joined
    }

    fun matches(pattern: String, path: String): Boolean {
        val patternParts = parts(pattern)
        val pathParts = parts(path)
        return matches(patternParts, pathParts, 0, 0)
    }

    fun included(path: String, includes: List<String>, excludes: List<String>): Boolean {
        if (includes.isNotEmpty() && includes.none { matches(it, path) }) {
            return false
        }
        return excludes.none { matches(it, path) }
    }

    private fun matches(pattern: List<String>, path: List<String>, patternIndex: Int, pathIndex: Int): Boolean {
        if (patternIndex == pattern.size) {
            return pathIndex == path.size
        }
        val segment = pattern[patternIndex]
        if (segment == "**") {
            if (patternIndex == pattern.lastIndex) {
                return true
            }
            var index = pathIndex
            while (index <= path.size) {
                if (matches(pattern, path, patternIndex + 1, index)) {
                    return true
                }
                index++
            }
            return false
        }
        if (pathIndex == path.size) {
            return false
        }
        val value = path[pathIndex]
        val ok = segment == "*" || segment == value || (segment.startsWith('{') && segment.endsWith('}'))
        return ok && matches(pattern, path, patternIndex + 1, pathIndex + 1)
    }

    private fun parts(path: String): List<String> =
        path.trim('/').split('/').filter(String::isNotEmpty)
}
