package me.monori.gradle.builddiagnostics.core

/** How urgently a captured diagnostic needs attention. */
internal enum class Severity {
    /** The build or task reported an error. */
    ERROR,
    /** The build reported a warning but may still succeed. */
    WARNING,
}

/** Broad area of the build associated with a diagnostic. */
internal enum class Category {
    /** Kotlin, Java, or another compiler reported a problem. */ COMPILATION,
    /** A test or test summary reported a problem. */ TEST,
    /** A static-analysis or lint tool reported a problem. */ LINT,
    /** Dependency resolution or metadata reported a problem. */ DEPENDENCY,
    /** Build configuration reported a problem. */ CONFIGURATION,
    /** A Gradle task failed without a more specific category. */ TASK_EXECUTION,
    /** A deprecated API or feature was used. */ DEPRECATION,
    /** The collector could not determine a more specific category. */ UNKNOWN,
}

/** Where the collector obtained a diagnostic. */
internal enum class Origin {
    /** A problem attached to a Gradle task failure. */ PROBLEMS_API,
    /** A structured Gradle failure and its causes. */ FAILURE_TREE,
    /** Compatibility parsing of standard-error output. */ STDERR_PARSER,
    /** Compatibility parsing of standard-output output. */ STDOUT_PARSER,
}

/** How confidently the collector can associate a diagnostic with a task. */
internal enum class Attribution {
    /** Gradle supplied the task association directly. */ EXACT,
    /** Concurrent output prevents reliable task association. */ AMBIGUOUS,
    /** No task association is available. */ UNKNOWN,
}

internal data class SourceLocation(
    val path: String?, val line: Int? = null, val column: Int? = null
)

internal data class DiagnosticDraft(
    val severity: Severity,
    val category: Category = Category.UNKNOWN,
    val origin: Origin,
    val message: String,
    val context: List<String> = emptyList(),
    val taskPath: String? = null,
    val attribution: Attribution = Attribution.UNKNOWN,
    val location: SourceLocation? = null,
)

internal data class NormalizedDiagnostic(
    val draft: DiagnosticDraft,
    val fingerprint: String,
    val truncated: Boolean,
    val redacted: Boolean,
)
