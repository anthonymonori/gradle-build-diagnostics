package me.monori.buildlogic

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.reporting.DirectoryReport
import org.gradle.api.reporting.SingleFileReport
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import java.net.URI

class CommonConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("java-gradle-plugin")
            pluginManager.apply("jacoco")
            pluginManager.apply("org.jetbrains.dokka")

            extensions.getByType(JavaPluginExtension::class.java)
                .toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            tasks.withType(Test::class.java).configureEach(Action { it.useJUnitPlatform() })

            // Both reports share the same source set, so keep their setup in this convention.
            val jacocoAllTestReport = registerCoverageReport()
            configureApiDocumentation()
            registerGithubPagesSite(jacocoAllTestReport)
        }
    }

    private fun Project.registerCoverageReport(): TaskProvider<JacocoReport> =
        tasks.register("jacocoAllTestReport", JacocoReport::class.java) { report ->
            report.group = "verification"
            report.description = "Generates one JaCoCo report for every test suite."
            report.dependsOn(tasks.withType(Test::class.java))
            report.executionData.from(layout.buildDirectory.dir("jacoco").map { directory ->
                fileTree(directory) { pattern -> pattern.include("*.exec") }
            })

            val mainSourceSet = extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main")
            report.sourceDirectories.from(mainSourceSet.allSource.srcDirs)
            report.classDirectories.from(mainSourceSet.output)

            (report.reports.getByName("html") as DirectoryReport).apply {
                required.set(true)
                outputLocation.set(layout.buildDirectory.dir("reports/jacoco/all/html"))
            }
            (report.reports.getByName("xml") as SingleFileReport).apply {
                required.set(true)
                outputLocation.set(layout.buildDirectory.file("reports/jacoco/all/jacoco.xml"))
            }
            (report.reports.getByName("csv") as SingleFileReport).required.set(false)
        }

    private fun Project.configureApiDocumentation() {
        extensions.getByType(DokkaExtension::class.java).apply {
            dokkaPublications.named("html") { publication ->
                publication.moduleName.set("Gradle Build Diagnostics")
                publication.outputDirectory.set(layout.buildDirectory.dir("docs/kdoc"))
            }
            dokkaSourceSets.configureEach { sourceSet ->
                sourceSet.documentedVisibilities.set(setOf(VisibilityModifier.Public))
                sourceSet.sourceLink { sourceLink ->
                    sourceLink.localDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
                    sourceLink.remoteUrl.set(URI.create("https://github.com/anthonymonori/gradle-build-diagnostics/tree/main/src/main/kotlin"))
                    sourceLink.remoteLineSuffix.set("#L")
                }
            }
        }
    }

    // This task only assembles already-generated files; it does not change their contents.
    private fun Project.registerGithubPagesSite(jacocoAllTestReport: TaskProvider<JacocoReport>) {
        tasks.register("prepareGithubPages", Sync::class.java) { syncTask ->
            syncTask.group = "documentation"
            syncTask.description = "Assembles the static site published to GitHub Pages."
            syncTask.dependsOn(jacocoAllTestReport, tasks.named("dokkaGenerate"))
            syncTask.from("pages")
            syncTask.from(layout.buildDirectory.dir("reports/jacoco/all/html")) { copySpec -> copySpec.into("coverage") }
            syncTask.from(layout.buildDirectory.dir("docs/kdoc")) { copySpec -> copySpec.into("kdoc") }
            syncTask.into(layout.buildDirectory.dir("pages"))
        }
    }
}
