package no.beint.riss.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathPatternsTest {
    @Test
    fun combineJoinsClassAndMethodPaths() {
        assertEquals("/api/features", PathPatterns.combine("/api", "/features"))
        assertEquals("/api/features/{id}", PathPatterns.combine("/api/features/", "{id}"))
        assertEquals("/", PathPatterns.combine("", ""))
    }

    @Test
    fun antMatchers() {
        assertTrue(PathPatterns.matches("/api/**", "/api/features"))
        assertTrue(PathPatterns.matches("/api/**", "/api/features/1/icon"))
        assertTrue(PathPatterns.included("/api/features", listOf("/api/**"), listOf("/api/admin/**")))
        assertFalse(PathPatterns.included("/api/admin/users", listOf("/api/**"), listOf("/api/admin/**")))
    }
}
