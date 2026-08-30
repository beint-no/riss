package no.beint.riss.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

private enum class BinaryFeature(val wire: String, val priority: Int, val active: Boolean) {
    ALPHA("alpha", -1, true),
    BETA("beta", 12, false),
}

class EnumReaderTest {
    @Test
    fun readsConstructorConstantsWithTheJdkClassFileApi() {
        val type = BinaryFeature::class.java
        val resource = "/${type.name.replace('.', '/')}.class"
        val bytes = requireNotNull(type.getResourceAsStream(resource)).use { it.readAllBytes() }

        assertEquals(
            listOf(
                EnumEntryValues("ALPHA", mapOf("wire" to "alpha", "priority" to "-1", "active" to "1")),
                EnumEntryValues("BETA", mapOf("wire" to "beta", "priority" to "12", "active" to "0")),
            ),
            readEnumEntries(type.name.replace('.', '/'), listOf("wire", "priority", "active"), bytes),
        )
    }
}
