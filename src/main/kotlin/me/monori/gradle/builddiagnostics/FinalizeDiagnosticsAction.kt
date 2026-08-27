package me.monori.gradle.builddiagnostics

import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input

@Suppress("UnstableApiUsage")
internal abstract class FinalizeDiagnosticsAction : FlowAction<FinalizeDiagnosticsAction.Parameters> {
    interface Parameters : FlowParameters {
        @get:ServiceReference
        val service: Property<BuildDiagnosticsService>

        @get:Input
        val success: Property<Boolean>
    }

    override fun execute(parameters: Parameters) {
        parameters.service.get().finish(parameters.success.get())
    }
}
