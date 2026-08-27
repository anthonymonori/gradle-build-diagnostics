package me.monori.gradle.builddiagnostics.core

/** Simple full-path glob filter; exclusions take precedence over inclusions. */
internal class TaskPathFilter(includes: List<String>, excludes: List<String>) {
    private val includePatterns = includes.filter(String::isNotBlank).map(::glob)
    private val excludePatterns = excludes.filter(String::isNotBlank).map(::glob)

    fun allows(taskPath: String): Boolean =
        excludePatterns.none { it.matches(taskPath) } && (includePatterns.isEmpty() || includePatterns.any {
            it.matches(taskPath)
        })

    private fun glob(pattern: String): Regex =
        Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$")
}
