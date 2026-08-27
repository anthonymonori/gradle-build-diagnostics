package me.monori.gradle.builddiagnostics.core

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunRetentionPlannerTest {
    @Test
    fun `only plans completed runs beyond the retention limit`() {
        val root = createTempDirectory("retention").resolve("runs")
        Files.createDirectories(root)
        run(root, "00000000-0000-0000-0000-000000000001", true, 1)
        run(root, "00000000-0000-0000-0000-000000000002", true, 2)
        run(root, "00000000-0000-0000-0000-000000000003", false, 3)
        val planned = RunRetentionPlanner().eligibleForRemoval(root, retainCompletedRuns = 1)
        assertEquals(
            listOf("00000000-0000-0000-0000-000000000001"),
            planned.map { it.fileName.toString() })
        assertTrue(
            Files.isDirectory(root.resolve("00000000-0000-0000-0000-000000000001")),
            "Planner must not delete"
        )
        assertTrue(
            Files.isDirectory(root.resolve("00000000-0000-0000-0000-000000000003")),
            "Incomplete evidence is never eligible"
        )
    }

    @Test
    fun `zero disables planning and malformed terminal evidence is retained`() {
        val root = createTempDirectory("retention").resolve("runs")
        Files.createDirectories(root)
        run(root, "00000000-0000-0000-0000-000000000001", true, 1)
        val malformed =
            Files.createDirectories(root.resolve("00000000-0000-0000-0000-000000000002"))
        malformed.resolve("diagnostics.jsonl")
            .writeText("{\"eventType\":\"build_finished\",\"runId\":\"wrong\",\"outcome\":\"success\"}\n")

        assertTrue(
            RunRetentionPlanner().eligibleForRemoval(root, retainCompletedRuns = 0).isEmpty()
        )
        assertTrue(
            RunRetentionPlanner().eligibleForRemoval(root, retainCompletedRuns = 1).isEmpty()
        )
    }

    private fun run(root: java.nio.file.Path, name: String, complete: Boolean, age: Long) {
        val directory = Files.createDirectories(root.resolve(name))
        directory.resolve("diagnostics.jsonl")
            .writeText(if (complete) "{\"timestamp\":\"2026-08-27T00:00:0${age}Z\",\"eventType\":\"build_finished\",\"runId\":\"$name\",\"outcome\":\"success\"}\n" else "{\"eventType\":\"build_started\"}\n")
        Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(age))
    }
}
