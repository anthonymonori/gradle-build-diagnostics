package me.monori.gradle.builddiagnostics.core

internal class FailureProblemsAdapter {
    fun extract(failure: Any): List<AssociatedProblem> = runCatching {
        getter(failure, "getProblems").asIterable().mapNotNull(::problem).toList()
    }.getOrDefault(emptyList())

    private fun problem(value: Any): AssociatedProblem? {
        val message = listOf("getContextualLabel", "getDetails", "getLabel").firstNotNullOfOrNull {
                getter(
                    value,
                    it
                )?.toString()?.takeIf(String::isNotBlank)
            } ?: return null
        val severity = when (getter(value, "getSeverity")?.toString()?.uppercase()) {
            "WARNING" -> Severity.WARNING
            else -> Severity.ERROR
        }
        val locationValue = getter(value, "getLocation")
        val path =
            locationValue?.let { getter(it, "getPath") ?: getter(it, "getFilePath") }?.toString()
        val line = locationValue?.let { getter(it, "getLine") }?.asInt()
        val column = locationValue?.let { getter(it, "getColumn") }?.asInt()
        return AssociatedProblem(
            severity,
            message,
            category = category(message),
            location = if (path == null && line == null && column == null) null else SourceLocation(
                path, line, column
            )
        )
    }

    private fun getter(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(target)
    }.getOrNull()

    private fun Any?.asIterable(): Iterable<Any> = when (this) {
        is Iterable<*> -> filterNotNull()
        is Array<*> -> filterNotNull()
        else -> emptyList()
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        else -> toString().toIntOrNull()
    }

    private fun category(message: String): Category =
        categoryRules.firstOrNull { it.matches(message) }?.category ?: Category.UNKNOWN

    private data class CategoryRule(val category: Category, val keywords: Set<String>) {
        fun matches(message: String) = keywords.any { message.contains(it, ignoreCase = true) }
    }

    private companion object {
        val categoryRules = listOf(
            CategoryRule(Category.DEPRECATION, setOf("deprecat")),
            CategoryRule(Category.LINT, setOf("lint")),
            CategoryRule(Category.DEPENDENCY, setOf("dependenc", "resolution")),
            CategoryRule(Category.TEST, setOf("test")),
            CategoryRule(Category.COMPILATION, setOf("compil", "kotlin", "java")),
            CategoryRule(Category.CONFIGURATION, setOf("configuration")),
        )
    }
}

internal data class AssociatedProblem(
    val severity: Severity,
    val message: String,
    val category: Category = Category.UNKNOWN,
    val location: SourceLocation? = null,
)
