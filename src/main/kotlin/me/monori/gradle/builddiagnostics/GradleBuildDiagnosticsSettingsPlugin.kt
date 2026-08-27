package me.monori.gradle.builddiagnostics

import org.gradle.api.Plugin
import org.gradle.api.Action
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.LoggingOutput
import org.gradle.api.services.BuildServiceRegistry
import org.gradle.build.event.BuildEventsListenerRegistry
import javax.inject.Inject

@Suppress("unused")
abstract class GradleBuildDiagnosticsSettingsPlugin @Inject constructor(
    private val buildEvents: BuildEventsListenerRegistry,
    @Suppress("UnstableApiUsage") private val flowScope: FlowScope,
    @Suppress("UnstableApiUsage") private val flowProviders: FlowProviders,
    private val loggingOutput: LoggingOutput,
) : Plugin<Settings> {

    @Suppress("UnstableApiUsage")
    override fun apply(settings: Settings) {
        val diagnostics = DiagnosticsProperties.from(settings)
        if (!diagnostics.enabled.get()) return
        settings.gradle.rootProject(Action { project ->
            project.tasks.register(
                "cleanBuildDiagnostics", CleanBuildDiagnosticsTask::class.java
            ) { task ->
                task.outputBaseDirectory.set(diagnostics.outputBaseDirectory)
                task.retainCompletedRuns.set(diagnostics.retainCompletedRuns)
            }
        })
        val service = settings.gradle.sharedServices.registerCapabilityProbe(
            diagnostics, settings.settingsDir
        )
        val collector = service.get()
        buildEvents.onTaskCompletion(service)
        settings.gradle.settingsEvaluated { collector.start() }
        loggingOutput.addStandardErrorListener(collector::captureStandardError)
        loggingOutput.addStandardOutputListener(collector::captureStandardOutput)
        flowScope.always(FinalizeDiagnosticsAction::class.java, Action { action ->
            action.parameters.service.set(service)
            action.parameters.success.set(flowProviders.buildWorkResult.map { !it.failure.isPresent })
        })
    }

    private fun BuildServiceRegistry.registerCapabilityProbe(
        diagnostics: DiagnosticsProperties, rootDirectory: java.io.File
    ) = registerIfAbsent(
        BuildDiagnosticsService.NAME, BuildDiagnosticsService::class.java, Action { registration ->
            registration.parameters.baseDirectory.set(diagnostics.outputBaseDirectory)
            registration.parameters.rootDirectory.set(rootDirectory)
            registration.parameters.normalizePaths.set(diagnostics.normalizePaths)
            registration.parameters.includeWarnings.set(diagnostics.includeWarnings)
            registration.parameters.contextLinesBefore.set(diagnostics.contextLinesBefore)
            registration.parameters.contextLinesAfter.set(diagnostics.contextLinesAfter)
            registration.parameters.additionalErrorMatchers.set(diagnostics.additionalErrorMatchers)
            registration.parameters.additionalWarningMatchers.set(diagnostics.additionalWarningMatchers)
            registration.parameters.includeTaskPaths.set(diagnostics.includeTaskPaths)
            registration.parameters.excludeTaskPaths.set(diagnostics.excludeTaskPaths)
            registration.parameters.maxEvents.set(diagnostics.maxEvents)
            registration.parameters.maxBytesPerBuild.set(diagnostics.maxBytesPerBuild)
            registration.parameters.redactionMode.set(diagnostics.redactionMode)
            registration.parameters.additionalRedactionPatterns.set(diagnostics.additionalRedactionPatterns)
            registration.parameters.consoleSummary.set(diagnostics.consoleSummary)
        })
}
