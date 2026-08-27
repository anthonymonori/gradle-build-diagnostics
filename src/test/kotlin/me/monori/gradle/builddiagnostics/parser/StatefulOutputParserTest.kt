package me.monori.gradle.builddiagnostics.parser

import me.monori.gradle.builddiagnostics.core.Attribution
import me.monori.gradle.builddiagnostics.core.Origin
import me.monori.gradle.builddiagnostics.core.Severity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatefulOutputParserTest {
    @Test
    fun `retains Kotlin diagnostic context across chunks and CRLF`() {
        val parser = StatefulOutputParser(Origin.STDERR_PARSER)
        assertTrue(parser.feed("e: Fixture.kt:1:20 Type mismatch\r").isEmpty())
        assertTrue(
            parser.feed("\n    fun broken(): String = 42\r\n                   ^\r\n").isEmpty()
        )
        val diagnostic = parser.finish().single()
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(Attribution.AMBIGUOUS, diagnostic.attribution)
        assertTrue(diagnostic.context.size >= 2)
        assertContains(diagnostic.context.joinToString("\n"), "^")
    }

    @Test
    fun `keeps warnings distinct from errors`() {
        val parser = StatefulOutputParser(Origin.STDOUT_PARSER)
        parser.feed("w: deprecated API\n")
        val diagnostic = parser.finish().single()
        assertEquals(Severity.WARNING, diagnostic.severity)
    }

    @Test
    fun `captures bounded preceding context and anchored custom matches`() {
        val parser = StatefulOutputParser(
            Origin.STDOUT_PARSER,
            contextLinesBefore = 2,
            contextLinesAfter = 1,
            customErrorMatchers = listOf(Regex("^CUSTOM_FAIL: .+$")),
        )
        parser.feed("first context\nsecond context\nCUSTOM_FAIL: details\n  trailing detail\n")

        val diagnostic = parser.finish().single()
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(
            listOf("first context", "second context", "  trailing detail"),
            diagnostic.context
        )
    }

    @Test
    fun `custom matchers are anchored full line matches`() {
        val parser = StatefulOutputParser(
            Origin.STDOUT_PARSER,
            customErrorMatchers = listOf(Regex("^CUSTOM_FAIL$"))
        )
        parser.feed("prefix CUSTOM_FAIL suffix\n")
        assertTrue(parser.finish().isEmpty())
    }
}
