package me.monori.gradle.builddiagnostics.core

import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class RunArtifactWriterTest {
    @Test
    fun `normalizes redacts and fingerprints post-redaction content`() {
        val normalizer = DiagnosticNormalizer(NormalizationLimits(128, 128))
        val one = normalizer.normalize(
            DiagnosticDraft(
                Severity.ERROR,
                origin = Origin.STDERR_PARSER,
                message = "\u001B[31mtoken=first-secret\u001B[0m\u0000"
            )
        )
        val two = normalizer.normalize(
            DiagnosticDraft(
                Severity.ERROR,
                origin = Origin.STDERR_PARSER,
                message = "token=second-secret"
            )
        )
        assertContains(one.draft.message, "[REDACTED]")
        assertFalse(one.draft.message.contains("\u001B"))
        assertEquals(one.fingerprint, two.fingerprint)
    }

    @Test
    fun `honors custom redactions and UTF8 message limits`() {
        val normalizer = DiagnosticNormalizer(
            NormalizationLimits(maxMessageBytes = 20, maxContextBytes = 20),
            additionalRedactions = listOf(Regex("session=[^\\s]+")),
        )
        val diagnostic = normalizer.normalize(
            DiagnosticDraft(
                Severity.WARNING,
                origin = Origin.STDOUT_PARSER,
                message = "session=private-value " + "é".repeat(30)
            ),
        )
        assertTrue(diagnostic.redacted)
        assertTrue(diagnostic.truncated)
        assertFalse(diagnostic.draft.message.contains("private-value"))
        assertTrue(diagnostic.draft.message.toByteArray().size <= 20)
    }

    @Test
    fun `disabled redaction preserves trusted local output`() {
        val diagnostic = DiagnosticNormalizer(redactionMode = RedactionMode.DISABLED).normalize(
            DiagnosticDraft(
                Severity.ERROR,
                origin = Origin.STDERR_PARSER,
                message = "token=trusted-local-value"
            ),
        )
        assertFalse(diagnostic.redacted)
        assertContains(diagnostic.draft.message, "trusted-local-value")
    }

    @Test
    fun `conservative redaction covers URL credentials and multiline keys without broad false positives`() {
        val diagnostic = DiagnosticNormalizer().normalize(
            DiagnosticDraft(
                Severity.ERROR,
                origin = Origin.STDERR_PARSER,
                message = "download https://user:pass@example.test/repo\n-----BEGIN PRIVATE KEY-----\nprivate\n-----END PRIVATE KEY-----\ntokenized output remains useful"
            ),
        )

        assertTrue(diagnostic.redacted)
        assertFalse(diagnostic.draft.message.contains("user:pass"))
        assertFalse(diagnostic.draft.message.contains("\nprivate\n"))
        assertContains(diagnostic.draft.message, "tokenized output remains useful")
    }

    @Test
    fun `writes flushed jsonl and an atomic text snapshot without duplicates`() {
        val run = createTempDirectory("diagnostics-run")
        val writer = RunArtifactWriter(run, "run-1")
        writer.start()
        val draft = DiagnosticDraft(
            Severity.ERROR,
            Category.COMPILATION,
            Origin.FAILURE_TREE,
            "Type mismatch",
            listOf("expected String"),
            ":compileKotlin",
            Attribution.EXACT
        )
        writer.diagnostic(draft)
        writer.diagnostic(draft)
        writer.finish(false)
        val lines = run.resolve("diagnostics.jsonl").readLines()
        assertEquals(3, lines.size)
        assertContains(lines[1], "\"eventType\":\"diagnostic\"")
        assertContains(run.resolve("build-context.txt").toFile().readText(), "State: failure")
    }

    @Test
    fun `escapes every JSON control character in event fields`() {
        val run = createTempDirectory("diagnostics-json-escaping")
        val writer = RunArtifactWriter(run, "run-json")
        writer.start()
        writer.taskFinished(":tab\treturn\rbackspace\bform-feed\u000Ccontrol\u0001", "success")

        val line = run.resolve("diagnostics.jsonl").readLines()[1]
        assertContains(line, "tab\\treturn\\rbackspace\\bform-feed\\fcontrol\\u0001")
        assertFalse(line.any { it.code < 0x20 })
    }

    @Test
    fun `marks an unfinished run as in progress and reports an event limit`() {
        val run = createTempDirectory("diagnostics-interrupted-run")
        val writer = RunArtifactWriter(run, "run-2", maxEvents = 1)
        val first = DiagnosticDraft(Severity.ERROR, origin = Origin.FAILURE_TREE, message = "first")
        writer.start()
        assertTrue(writer.diagnostic(first))
        assertFalse(writer.diagnostic(first.copy(message = "second")))
        val lines = run.resolve("diagnostics.jsonl").readLines()
        assertEquals(3, lines.size)
        assertContains(lines.last(), "\"eventType\":\"collector_warning\"")
        assertContains(run.resolve("build-context.txt").toFile().readText(), "State: in_progress")
    }

    @Test
    fun `always writes a terminal record after diagnostic byte exhaustion`() {
        val run = createTempDirectory("diagnostics-terminal-reserve")
        val writer = RunArtifactWriter(run, "run-3", maxBytes = 350)
        writer.start()
        assertFalse(
            writer.diagnostic(
                DiagnosticDraft(
                    Severity.ERROR,
                    origin = Origin.STDERR_PARSER,
                    message = "x".repeat(200)
                )
            )
        )
        writer.finish(false)

        val lines = run.resolve("diagnostics.jsonl").readLines()
        assertContains(lines.first(), "\"eventType\":\"build_started\"")
        assertContains(lines.last(), "\"eventType\":\"build_finished\"")
        assertContains(run.resolve("build-context.txt").toFile().readText(), "State: failure")
    }

    @Test
    fun `serializes concurrent writer callbacks into monotonic durable records`() {
        val run = createTempDirectory("diagnostics-concurrent-run")
        val writer = RunArtifactWriter(run, "run-4", maxEvents = 100)
        writer.start()
        val executor = Executors.newFixedThreadPool(8)
        repeat(40) { index ->
            executor.submit {
                writer.taskFinished(":task$index", "success")
                writer.diagnostic(
                    DiagnosticDraft(
                        Severity.ERROR,
                        origin = Origin.FAILURE_TREE,
                        message = "failure-$index"
                    )
                )
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        writer.finish(false)

        val lines = run.resolve("diagnostics.jsonl").readLines()
        val sequences =
            lines.map { Regex("\"sequence\":(\\d+)").find(it)!!.groupValues[1].toLong() }
        assertEquals((1L..sequences.size.toLong()).toList(), sequences)
        assertEquals(40, lines.count { it.contains("\"eventType\":\"diagnostic\"") })
        assertContains(lines.last(), "\"eventType\":\"build_finished\"")
        assertContains(run.resolve("build-context.txt").toFile().readText(), "State: failure")
    }

    @Test
    fun `bounded high volume callbacks retain a coherent terminal artifact`() {
        val run = createTempDirectory("diagnostics-high-volume-run")
        val writer = RunArtifactWriter(run, "run-8", maxEvents = 32, maxBytes = 256_000) // 250 KiB
        writer.start()
        val executor = Executors.newFixedThreadPool(12)
        repeat(200) { index ->
            executor.submit {
                writer.taskFinished(":stress$index", "failure")
                writer.diagnostic(
                    DiagnosticDraft(
                        Severity.ERROR,
                        origin = Origin.FAILURE_TREE,
                        message = "stress-$index"
                    )
                )
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        writer.finish(false)

        val lines = run.resolve("diagnostics.jsonl").readLines()
        val sequences =
            lines.map { Regex("\"sequence\":(\\d+)").find(it)!!.groupValues[1].toLong() }
        assertEquals((1L..sequences.size.toLong()).toList(), sequences)
        assertEquals(32, lines.count { it.contains("\"eventType\":\"diagnostic\"") })
        assertEquals(1, lines.count { it.contains("\"reason\":\"event_limit_reached\"") })
        assertContains(lines.last(), "\"eventType\":\"build_finished\"")
    }

    @Test
    fun `ignores late output after the terminal record`() {
        val run = createTempDirectory("diagnostics-sealed-run")
        val writer = RunArtifactWriter(run, "run-5")
        writer.start()
        writer.finish(false)

        assertFalse(
            writer.diagnostic(
                DiagnosticDraft(
                    Severity.ERROR,
                    origin = Origin.STDOUT_PARSER,
                    message = "late output"
                )
            )
        )
        assertFalse(writer.taskFinished(":late", "success"))
        assertContains(
            run.resolve("diagnostics.jsonl").readLines().last(),
            "\"eventType\":\"build_finished\""
        )
    }

    @Test
    fun `text-render failure does not prevent durable JSONL or terminal state`() {
        val run = createTempDirectory("diagnostics-render-failure")
        val writer = RunArtifactWriter(run, "run-6", files = MoveFailingFiles)
        writer.start()
        writer.diagnostic(
            DiagnosticDraft(
                Severity.ERROR,
                origin = Origin.FAILURE_TREE,
                message = "captured before render failure"
            )
        )
        writer.finish(false)

        val lines = run.resolve("diagnostics.jsonl").readLines()
        assertContains(lines[1], "captured before render failure")
        assertContains(lines.last(), "\"eventType\":\"build_finished\"")
    }

    @Test
    fun `append failure is contained and reports no diagnostic`() {
        val writer = RunArtifactWriter(
            createTempDirectory("diagnostics-append-failure"),
            "run-7",
            files = WriteFailingFiles
        )
        writer.start()
        assertFalse(
            writer.diagnostic(
                DiagnosticDraft(
                    Severity.ERROR,
                    origin = Origin.FAILURE_TREE,
                    message = "cannot persist"
                )
            )
        )
        writer.finish(false)
    }

    private object MoveFailingFiles : ArtifactFileOperations {
        override fun createDirectories(path: Path) =
            NioArtifactFileOperations.createDirectories(path)

        override fun writeString(
            path: Path,
            value: String,
            options: Array<out StandardOpenOption>
        ) = NioArtifactFileOperations.writeString(path, value, options)

        override fun move(
            source: Path,
            target: Path,
            options: Array<out StandardCopyOption>
        ): Nothing = throw java.io.IOException("simulated move failure")
    }

    private object WriteFailingFiles : ArtifactFileOperations {
        override fun createDirectories(path: Path) =
            NioArtifactFileOperations.createDirectories(path)

        override fun writeString(
            path: Path,
            value: String,
            options: Array<out StandardOpenOption>
        ): Nothing = throw java.io.IOException("simulated write failure")

        override fun move(source: Path, target: Path, options: Array<out StandardCopyOption>) =
            NioArtifactFileOperations.move(source, target, options)
    }
}
