package me.monori.gradle.builddiagnostics.parser

import me.monori.gradle.builddiagnostics.core.Attribution
import me.monori.gradle.builddiagnostics.core.Category
import me.monori.gradle.builddiagnostics.core.DiagnosticDraft
import me.monori.gradle.builddiagnostics.core.Origin
import me.monori.gradle.builddiagnostics.core.Severity

internal class StatefulOutputParser(
    private val origin: Origin,
    private val contextLinesBefore: Int = 0,
    private val contextLinesAfter: Int = 3,
    private val customErrorMatchers: List<Regex> = emptyList(),
    private val customWarningMatchers: List<Regex> = emptyList(),
) {
    private val remainder = StringBuilder()
    private val preceding = ArrayDeque<String>()
    private var pending: Pending? = null

    fun feed(chunk: String): List<DiagnosticDraft> {
        remainder.append(chunk)
        val complete = remainder.toString().splitToSequence('\n').toList()
        if (!remainder.endsWith("\n")) {
            remainder.clear()
            remainder.append(complete.lastOrNull().orEmpty())
            return complete.dropLast(1).flatMap(::accept)
        }
        remainder.clear()
        return complete.dropLast(1).flatMap(::accept)
    }

    fun finish(): List<DiagnosticDraft> = buildList {
        if (remainder.isNotEmpty()) addAll(accept(remainder.toString()))
        remainder.clear()
        pending?.let { add(it.toDraft()) }
        pending = null
    }

    private fun accept(rawLine: String): List<DiagnosticDraft> {
        val line = rawLine.removeSuffix("\r")
        val classified = classify(line)
        val current = pending
        if (current != null && classified == null && isContext(line) && current.context.size < contextLinesBefore + contextLinesAfter) {
            current.context += line
            return emptyList()
        }
        val emitted = current?.let { listOf(it.toDraft()) }.orEmpty()
        pending = classified?.let {
            Pending(
                it.first, it.second, origin, line, preceding.toMutableList()
            )
        }
        if (classified == null) remember(line)
        return emitted
    }

    private fun classify(line: String): Pair<Severity, Category>? {
        val trimmed = line.trimStart()
        return compilerError(trimmed) ?: compilerWarning(trimmed) ?: additionalMatcher(line)
        ?: testSummary(trimmed) ?: exceptionHeader(trimmed)
    }

    private fun compilerError(line: String): Pair<Severity, Category>? =
        (line.startsWith("e:") || line.contains("error:", ignoreCase = true)).takeIf { it }
            ?.let { Severity.ERROR to Category.COMPILATION }

    private fun compilerWarning(line: String): Pair<Severity, Category>? =
        (line.startsWith("w:") || line.contains("warning:", ignoreCase = true)).takeIf { it }
            ?.let { Severity.WARNING to Category.COMPILATION }

    private fun additionalMatcher(line: String): Pair<Severity, Category>? {
        if (customErrorMatchers.any { it.matches(line) }) return Severity.ERROR to Category.UNKNOWN
        if (customWarningMatchers.any { it.matches(line) }) return Severity.WARNING to Category.UNKNOWN
        return null
    }

    private fun testSummary(line: String): Pair<Severity, Category>? =
        line.matches(TEST_SUMMARY).takeIf { it }?.let { Severity.ERROR to Category.TEST }

    private fun exceptionHeader(line: String): Pair<Severity, Category>? =
        line.matches(EXCEPTION_HEADER).takeIf { it }
            ?.let { Severity.ERROR to Category.TASK_EXECUTION }

    private fun isContext(line: String) =
        line.isBlank() || line.startsWith(" ") || line.startsWith("\t") || line.trimStart()
            .startsWith("^") || line.trimStart().startsWith("at ")

    private fun remember(line: String) {
        if (contextLinesBefore <= 0) return
        preceding += line
        while (preceding.size > contextLinesBefore) preceding.removeFirst()
    }

    private data class Pending(
        val severity: Severity,
        val category: Category,
        val origin: Origin,
        val message: String,
        val context: MutableList<String> = mutableListOf()
    ) {
        fun toDraft() = DiagnosticDraft(
            severity = severity,
            category = category,
            origin = origin,
            message = message,
            context = context,
            attribution = Attribution.AMBIGUOUS
        )
    }

    private companion object {
        val TEST_SUMMARY =
            Regex(".*\\b\\d+ tests? completed.*\\b\\d+ failed.*", RegexOption.IGNORE_CASE)
        val EXCEPTION_HEADER = Regex("(?:Caused by: )?.*(?:Exception|Error)(?::.*)?")
    }
}
