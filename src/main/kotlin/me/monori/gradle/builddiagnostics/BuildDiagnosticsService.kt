package me.monori.gradle.builddiagnostics

import me.monori.gradle.builddiagnostics.core.Attribution
import me.monori.gradle.builddiagnostics.core.Category
import me.monori.gradle.builddiagnostics.core.DiagnosticDraft
import me.monori.gradle.builddiagnostics.core.DiagnosticNormalizer
import me.monori.gradle.builddiagnostics.core.FailureProblemsAdapter
import me.monori.gradle.builddiagnostics.core.Origin
import me.monori.gradle.builddiagnostics.core.RunArtifactWriter
import me.monori.gradle.builddiagnostics.core.RunArtifactWriterRegistry
import me.monori.gradle.builddiagnostics.core.RedactionMode
import me.monori.gradle.builddiagnostics.core.Severity
import me.monori.gradle.builddiagnostics.core.SourcePathNormalizer
import me.monori.gradle.builddiagnostics.core.TaskPathFilter
import me.monori.gradle.builddiagnostics.parser.StatefulOutputParser
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal abstract class BuildDiagnosticsService : BuildService<BuildDiagnosticsService.Parameters>,
    OperationCompletionListener {

    /**
     * Configuration passed from the settings plugin to this build-wide collector.
     *
     * Values come from `buildDiagnostics.*` Gradle properties. Keeping them here lets Gradle
     * safely reuse the collector with the configuration cache.
    */
    interface Parameters : BuildServiceParameters {
        /** Directory that contains all diagnostics runs for this build. */
        val baseDirectory: DirectoryProperty

        /** Consumer build root, used when turning local source paths into relative paths. */
        val rootDirectory: DirectoryProperty

        /** Whether paths inside the consumer build root should be written as relative paths. */
        val normalizePaths: Property<Boolean>

        /** Whether parser-captured warnings should be kept as well as errors. */
        val includeWarnings: Property<Boolean>

        /** Number of non-diagnostic lines to keep before a parsed match. */
        val contextLinesBefore: Property<Int>

        /** Number of following context lines to keep after a parsed match. */
        val contextLinesAfter: Property<Int>

        /** Extra anchored patterns that identify error output. */
        val additionalErrorMatchers: ListProperty<String>

        /** Extra anchored patterns that identify warning output. */
        val additionalWarningMatchers: ListProperty<String>

        /** Task paths eligible for task-attributed collection; an empty list allows all paths. */
        val includeTaskPaths: ListProperty<String>

        /** Task paths never collected; these take precedence over included paths. */
        val excludeTaskPaths: ListProperty<String>

        /** Maximum number of diagnostic events to retain for one run. */
        val maxEvents: Property<Int>

        /** Maximum total size of the `diagnostics.jsonl` file for one run. */
        val maxBytesPerBuild: Property<Long>

        /** Built-in redaction policy used before writing captured text. */
        val redactionMode: Property<String>

        /** Extra regular expressions for values that must be removed from captured text. */
        val additionalRedactionPatterns: ListProperty<String>

        /** Whether a failed build should print the diagnostics directory to the console. */
        val consoleSummary: Property<Boolean>
    }

    private val writer: RunArtifactWriter by lazy {
        val base = parameters.baseDirectory.get().asFile.toPath()
        RunArtifactWriterRegistry.acquire(base) {
            val runId = UUID.randomUUID().toString()
            val compiled = parameters.additionalRedactionPatterns.get()
                .map { pattern -> pattern to runCatching { Regex(pattern) } }
            val patterns = compiled.mapNotNull { it.second.getOrNull() }
            val mode = RedactionMode.from(parameters.redactionMode.get())
            val pathNormalizer =
                SourcePathNormalizer(parameters.rootDirectory.get().asFile.toPath())
            RunArtifactWriter(
                runDirectory = base.resolve("runs").resolve(runId),
                runId = runId,
                normalizer = DiagnosticNormalizer(
                    redactionMode = mode ?: RedactionMode.CONSERVATIVE,
                    additionalRedactions = patterns,
                    pathNormalizer = if (parameters.normalizePaths.get()) pathNormalizer::normalize else { value -> value }),
                maxEvents = parameters.maxEvents.get(),
                maxBytes = parameters.maxBytesPerBuild.get()
            ).also {
                it.start()
                if (compiled.any { it.second.isFailure }) it.collectorWarning("invalid_redaction_pattern")
                if (mode == null) it.collectorWarning("invalid_redaction_mode")
                if (parserMatchers.invalid) it.collectorWarning("invalid_parser_matcher")
                updateLatest(base, it.runDirectory)
            }
        }
    }

    private val parserMatchers by lazy {
        ParserMatchers.from(
            parameters.additionalErrorMatchers.get(),
            parameters.additionalWarningMatchers.get()
        )
    }
    private val stderrParser by lazy { parser(Origin.STDERR_PARSER) }
    private val stdoutParser by lazy { parser(Origin.STDOUT_PARSER) }
    private val problemsAdapter = FailureProblemsAdapter()
    private val taskPathFilter by lazy {
        TaskPathFilter(
            parameters.includeTaskPaths.get(),
            parameters.excludeTaskPaths.get()
        )
    }

    internal fun start() {
        writer
    }

    override fun onFinish(event: FinishEvent) {
        val taskEvent = event as? TaskFinishEvent ?: return
        if (!taskPathFilter.allows(taskEvent.descriptor.taskPath)) return
        val result = taskEvent.result
        writer.taskFinished(
            taskEvent.descriptor.taskPath,
            if (result is TaskFailureResult) "failure" else "success"
        )
        if (result !is TaskFailureResult) return
        runCatching {
            result.failures.forEach { failure ->
                flatten(failure).forEach { node ->
                    val problems = problemsAdapter.extract(node)
                    if (problems.isEmpty()) writer.diagnostic(
                        DiagnosticDraft(
                            severity = Severity.ERROR,
                            category = Category.TASK_EXECUTION,
                            origin = Origin.FAILURE_TREE,
                            message = node.message ?: "Task failure without a message",
                            context = node.description?.lineSequence()?.take(24)?.toList()
                                .orEmpty(),
                            taskPath = taskEvent.descriptor.taskPath,
                            attribution = Attribution.EXACT
                        ),
                    ) else problems.forEach { problem ->
                        writer.diagnostic(
                            DiagnosticDraft(
                                severity = problem.severity,
                                category = problem.category,
                                origin = Origin.PROBLEMS_API,
                                message = problem.message,
                                taskPath = taskEvent.descriptor.taskPath,
                                attribution = Attribution.EXACT,
                                location = problem.location
                            ),
                        )
                    }
                }
            }
        }
    }

    fun finish(success: Boolean) {
        acceptParsed(stderrParser.finish())
        acceptParsed(stdoutParser.finish())
        writer.finish(success)
        if (parameters.consoleSummary.get() && !success) {
            println("Build diagnostics written to ${writer.runDirectory}")
        }
    }

    fun captureStandardError(output: CharSequence) {
        acceptParsed(stderrParser.feed(output.toString()))
    }

    fun captureStandardOutput(output: CharSequence) {
        acceptParsed(stdoutParser.feed(output.toString()))
    }

    private fun acceptParsed(diagnostics: List<DiagnosticDraft>) {
        diagnostics.filter { it.severity == Severity.ERROR || parameters.includeWarnings.get() }
            .forEach(writer::diagnostic)
    }

    private fun parser(origin: Origin) = StatefulOutputParser(
        origin = origin,
        contextLinesBefore = parameters.contextLinesBefore.get().coerceAtLeast(0),
        contextLinesAfter = parameters.contextLinesAfter.get().coerceAtLeast(0),
        customErrorMatchers = parserMatchers.errors,
        customWarningMatchers = parserMatchers.warnings,
    )


    private fun flatten(failure: Failure): Sequence<Failure> = sequence {
        yield(failure)
        failure.causes.forEach { yieldAll(flatten(it)) }
    }

    private fun updateLatest(base: java.nio.file.Path, run: java.nio.file.Path) {
        runCatching {
            Files.createDirectories(base)
            val target = base.resolve("latest.json")
            val temporary = base.resolve("latest.${run.fileName}.json.tmp")
            Files.writeString(
                temporary,
                "{\"runId\":\"${run.fileName}\",\"path\":\"runs/${run.fileName}\"}\n",
                StandardCharsets.UTF_8
            )
            runCatching {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
                .getOrElse { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    companion object {
        const val NAME = "me.monori.gradle-build-diagnostics.service"
    }
}

private data class ParserMatchers(
    val errors: List<Regex>,
    val warnings: List<Regex>,
    val invalid: Boolean
) {
    companion object {
        fun from(errors: List<String>, warnings: List<String>): ParserMatchers {
            var invalid = false
            fun compile(values: List<String>) = values.mapNotNull { value ->
                if (!value.startsWith("^") || !value.endsWith("$")) {
                    invalid = true; null
                } else runCatching { Regex(value) }.getOrElse { invalid = true; null }
            }
            return ParserMatchers(compile(errors), compile(warnings), invalid)
        }
    }
}
