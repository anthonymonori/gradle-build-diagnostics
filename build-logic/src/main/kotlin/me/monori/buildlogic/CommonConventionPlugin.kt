package me.monori.buildlogic

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

class CommonConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-gradle-plugin")

        extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        tasks.withType(Test::class.java).configureEach(Action { it.useJUnitPlatform() })
    }
}
