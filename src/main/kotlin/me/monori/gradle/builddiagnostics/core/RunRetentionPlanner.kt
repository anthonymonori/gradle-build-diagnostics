package me.monori.gradle.builddiagnostics.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

internal class RunRetentionPlanner {
    fun eligibleForRemoval(runsDirectory: Path, retainCompletedRuns: Int): List<Path> {
        if (retainCompletedRuns <= 0) return emptyList()
        if (!Files.isDirectory(runsDirectory)) return emptyList()
        val completed = Files.list(runsDirectory).use { stream ->
            stream.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .filter { runCatching { UUID.fromString(it.fileName.toString()) }.isSuccess }
                .map(::completedRun).filter { it != null }.map { it!! }
                .sorted(compareByDescending(CompletedRun::finishedAt)).toList()
        }
        return completed.drop(retainCompletedRuns).map(CompletedRun::directory)
    }

    private fun completedRun(runDirectory: Path): CompletedRun? {
        val jsonl = runDirectory.resolve("diagnostics.jsonl")
        if (!Files.isRegularFile(jsonl, NOFOLLOW_LINKS)) return null
        val terminal = lastNonBlankLine(jsonl) ?: return null
        val runId = runDirectory.fileName.toString()
        if (!terminal.contains("\"eventType\":\"build_finished\"") || !terminal.contains("\"runId\":\"$runId\"") || !(terminal.contains(
                "\"outcome\":\"success\""
            ) || terminal.contains("\"outcome\":\"failure\""))
        ) return null
        val finishedAt = timestamp.find(terminal)?.groupValues?.get(1)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return CompletedRun(runDirectory, finishedAt)
    }

    private fun lastNonBlankLine(path: Path): String? = runCatching {
        val size = Files.size(path)
        if (size == 0L) return@runCatching null
        val length = minOf(size, MAX_TERMINAL_SCAN_BYTES.toLong()).toInt()
        val bytes = ByteArray(length)
        java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(size - length)
            channel.read(java.nio.ByteBuffer.wrap(bytes))
        }
        bytes.toString(Charsets.UTF_8).lineSequence().lastOrNull { it.isNotBlank() }
    }.getOrNull()

    private data class CompletedRun(val directory: Path, val finishedAt: Instant)

    private companion object {
        const val MAX_TERMINAL_SCAN_BYTES = 64 * 1024
        val timestamp = Regex("\\\"timestamp\\\":\\\"([^\\\"]+)\\\"")
    }
}
