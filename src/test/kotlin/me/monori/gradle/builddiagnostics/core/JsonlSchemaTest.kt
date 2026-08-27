package me.monori.gradle.builddiagnostics.core

import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonlSchemaTest {
    @Test
    fun `canonical records have required fields and terminal ordering`() {
        val run = createTempDirectory("schema-run")
        val writer =
            RunArtifactWriter(run, "fixture-run", now = { Instant.parse("2026-08-27T00:00:00Z") })
        writer.start()
        writer.diagnostic(
            DiagnosticDraft(
                Severity.ERROR,
                origin = Origin.FAILURE_TREE,
                message = "fixture"
            )
        )
        writer.finish(false)
        val lines = run.resolve("diagnostics.jsonl").readLines()
        assertEquals(3, lines.size)
        lines.forEachIndexed { index, line ->
            assertTrue(line.contains("\"schemaVersion\":1"))
            assertTrue(line.contains("\"runId\":\"fixture-run\""))
            assertTrue(line.contains("\"sequence\":${index + 1}"))
        }
        assertTrue(lines.first().contains("\"eventType\":\"build_started\""))
        assertTrue(lines.last().contains("\"eventType\":\"build_finished\""))
    }
}
