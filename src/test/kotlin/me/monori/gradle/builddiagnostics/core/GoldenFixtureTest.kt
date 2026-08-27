package me.monori.gradle.builddiagnostics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenFixtureTest {
    @Test
    fun `golden success fixture has a terminal record`() {
        val lines = fixture("success").lines().filter(String::isNotBlank)
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("\"eventType\":\"build_started\""))
        assertTrue(lines.last().contains("\"eventType\":\"build_finished\""))
    }

    @Test
    fun `golden interrupted fixture has no terminal record`() {
        val lines = fixture("interrupted").lines().filter(String::isNotBlank)
        assertEquals(1, lines.size)
        assertFalse(lines.single().contains("build_finished"))
    }

    @Test
    fun `golden failure and warning fixtures preserve attribution`() {
        assertTrue(fixture("failure").contains("\"attribution\":\"exact\""))
        assertTrue(fixture("warning").contains("\"attribution\":\"ambiguous\""))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name.jsonl")) { "Missing golden fixture $name" }.readText()
}
