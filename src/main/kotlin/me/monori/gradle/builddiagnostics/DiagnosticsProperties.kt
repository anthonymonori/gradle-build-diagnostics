package me.monori.gradle.builddiagnostics

import org.gradle.api.file.Directory
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider
import me.monori.gradle.builddiagnostics.core.MiB

internal data class DiagnosticsProperties(
    val enabled: Provider<Boolean>,
    val outputBaseDirectory: Directory,
    val normalizePaths: Provider<Boolean>,
    val includeWarnings: Provider<Boolean>,
    val contextLinesBefore: Provider<Int>,
    val contextLinesAfter: Provider<Int>,
    val additionalErrorMatchers: Provider<List<String>>,
    val additionalWarningMatchers: Provider<List<String>>,
    val includeTaskPaths: Provider<List<String>>,
    val excludeTaskPaths: Provider<List<String>>,
    val maxEvents: Provider<Int>,
    val maxBytesPerBuild: Provider<Long>,
    val redactionMode: Provider<String>,
    val additionalRedactionPatterns: Provider<List<String>>,
    val consoleSummary: Provider<Boolean>,
    val retainCompletedRuns: Provider<Int>,
) {
    companion object {
        fun from(settings: Settings): DiagnosticsProperties {
            val properties = settings.providers
            fun boolean(name: String, default: Boolean) =
                properties.gradleProperty(name).map(String::toBoolean).orElse(default)

            fun list(name: String) = properties.gradleProperty(name)
                .map { it.split(',').map(String::trim).filter(String::isNotBlank) }
                .orElse(emptyList())

            @Suppress("UnstableApiUsage") return DiagnosticsProperties(
                enabled = boolean("buildDiagnostics.enabled", true),
                outputBaseDirectory = settings.layout.settingsDirectory.dir(
                    properties.gradleProperty("buildDiagnostics.outputBaseDirectory")
                        .getOrElse(".gradle/build-diagnostics"),
                ),
                normalizePaths = boolean("buildDiagnostics.normalizePaths", true),
                includeWarnings = boolean("buildDiagnostics.includeWarnings", false),
                contextLinesBefore = properties.gradleProperty("buildDiagnostics.contextLinesBefore")
                    .map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }.orElse(0),
                contextLinesAfter = properties.gradleProperty("buildDiagnostics.contextLinesAfter")
                    .map { it.toIntOrNull()?.coerceAtLeast(0) ?: 3 }.orElse(3),
                additionalErrorMatchers = list("buildDiagnostics.additionalErrorMatchers"),
                additionalWarningMatchers = list("buildDiagnostics.additionalWarningMatchers"),
                includeTaskPaths = list("buildDiagnostics.includeTaskPaths"),
                excludeTaskPaths = list("buildDiagnostics.excludeTaskPaths"),
                maxEvents = properties.gradleProperty("buildDiagnostics.maxEvents")
                    .map { it.toIntOrNull()?.takeIf { value -> value >= 0 } ?: 1_000 }
                    .orElse(1_000),
                maxBytesPerBuild = properties.gradleProperty("buildDiagnostics.maxBytesPerBuild")
                    .map { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: MiB.toLong() }
                    .orElse(MiB.toLong()),
                redactionMode = properties.gradleProperty("buildDiagnostics.redactionMode")
                    .orElse("conservative"),
                additionalRedactionPatterns = list("buildDiagnostics.additionalRedactionPatterns"),
                consoleSummary = boolean("buildDiagnostics.consoleSummary", false),
                retainCompletedRuns = properties.gradleProperty("buildDiagnostics.retainCompletedRuns")
                    .map { it.toIntOrNull() ?: 20 }.orElse(20),
            )
        }
    }
}
