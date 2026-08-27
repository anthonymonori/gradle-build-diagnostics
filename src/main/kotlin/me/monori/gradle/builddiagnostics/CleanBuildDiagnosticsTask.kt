package me.monori.gradle.builddiagnostics

import me.monori.gradle.builddiagnostics.core.RunRetentionPlanner
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.Comparator

internal abstract class CleanBuildDiagnosticsTask : DefaultTask() {
    @get:Internal
    abstract val outputBaseDirectory: DirectoryProperty

    @get:Input
    abstract val retainCompletedRuns: Property<Int>

    init {
        group = "build diagnostics"
        description =
            "Deletes completed diagnostics runs beyond buildDiagnostics.retainCompletedRuns."
    }

    @TaskAction
    fun prune() {
        val retain = retainCompletedRuns.get().coerceAtLeast(0)
        val candidates = RunRetentionPlanner().eligibleForRemoval(
            outputBaseDirectory.get().asFile.toPath().resolve("runs"), retain
        )
        candidates.forEach(::deleteRunPackage)
        logger.lifecycle("Pruned ${candidates.size} completed build diagnostics run package(s); retained $retain.")
    }

    private fun deleteRunPackage(runDirectory: java.nio.file.Path) {
        Files.walk(runDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
