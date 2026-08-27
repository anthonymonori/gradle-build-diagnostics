package me.monori.gradle.builddiagnostics

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

class BuildDiagnosticsFunctionalTest {
    @Test
    fun `captures a failing Groovy DSL consumer build`() {
        val fixture = createTempDirectory("gradle-build-diagnostics-groovy").toFile()
        fixture.resolve("settings.gradle").writeText(
            """
            plugins {
                id 'me.monori.gradle-build-diagnostics'
            }
            """.trimIndent(),
        )
        fixture.resolve("build.gradle").writeText(
            """
            tasks.register('fails') {
                doLast {
                    println 'e: Groovy consumer compiler-style output'
                    throw new GradleException('Groovy consumer failure')
                }
            }
            """.trimIndent(),
        )

        runFailingBuild(fixture, "fails")

        val jsonl = reportFor(fixture).readText()
        assertContains(jsonl, "Groovy consumer failure")
        assertContains(jsonl, "Groovy consumer compiler-style output")
        assertContains(jsonl, "\"origin\":\"failure_tree\"")
    }

    @Test
    fun `supports the declared Gradle compatibility matrix`() {
        listOf(
            System.getProperty("buildDiagnostics.minimumSupportedGradleVersion"),
            System.getProperty("buildDiagnostics.currentTestedGradleVersion"),
        ).distinct().forEach { gradleVersion ->
            val fixture = createFailingTaskFixture().apply {
                resolve("build.gradle.kts").writeText(
                    """
                    tasks.register("fails") {
                        doLast {
                            println("e: compatibility parser output")
                            throw GradleException("compatibility failure message")
                        }
                    }
                    """.trimIndent(),
                )
            }

            runFailingBuild(fixture, "fails", gradleVersion = gradleVersion)

            val jsonl = reportFor(fixture).readText()
            assertContains(jsonl, "\"taskPath\":\":fails\"")
            assertContains(jsonl, "\"origin\":\"failure_tree\"")
            assertContains(jsonl, "compatibility parser output")
            assertContains(jsonl, "\"attribution\":\"ambiguous\"")
            assertContains(jsonl, "\"eventType\":\"build_finished\"")
            println("COMPATIBILITY Gradle $gradleVersion: configuration-cache failure and parser scenarios passed")
        }
    }

    @Test fun `can disable collection through gradle properties`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.enabled=false")
        runFailingBuild(fixture, "fails")
        assertTrue(reportsFor(fixture).isEmpty())
    }

    @Test fun `can relocate artifacts through gradle properties`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.outputBaseDirectory=custom-diagnostics")
        runFailingBuild(fixture, "fails")
        assertEquals(fixture.resolve("custom-diagnostics/runs").listFiles()?.isNotEmpty(), true)
    }

    @Test fun `does not expose a settings DSL configuration surface`() {
        val fixture = createFailingTaskFixture().apply {
            resolve("settings.gradle.kts").appendText("\n\nbuildDiagnostics { }\n")
        }

        val result = runFailingBuild(fixture, "fails")

        assertContains(result.output, "Unresolved reference")
        val report = reportFor(fixture).readText()
        assertContains(report, "\"eventType\":\"build_started\"")
        assertContains(report, "\"eventType\":\"build_finished\"")
        assertContains(report, "\"outcome\":\"failure\"")
    }

    @Test fun `starts a single package after settings evaluation`() {
        val fixture = createFailingTaskFixture()

        runFailingBuild(fixture, "fails")

        val reports = reportsFor(fixture)
        assertEquals(1, reports.size)
        assertContains(reports.single().readText(), "\"eventType\":\"build_started\"")
    }

    @Test fun `prints artifact location only when console summary is enabled`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.consoleSummary=true")
        val result = runFailingBuild(fixture, "fails")
        assertContains(result.output, "Build diagnostics written to")
    }

    @Test fun `invalid custom redaction is reported without changing a failed build outcome`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.additionalRedactionPatterns=[invalid")
        runFailingBuild(fixture, "fails")
        assertContains(reportFor(fixture).readText(), "\"reason\":\"invalid_redaction_pattern\"")
    }

    @Test fun `disabled redaction mode preserves explicitly trusted parser output`() {
        val fixture = createSuccessfulOutputFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.redactionMode=disabled")

        runSuccessfulBuild(fixture, "succeeds")

        assertContains(reportFor(fixture).readText(), "token=trusted-local-value")
    }

    @Test fun `invalid redaction mode falls back safely and records a warning`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.redactionMode=unknown")

        runFailingBuild(fixture, "fails")

        assertContains(reportFor(fixture).readText(), "\"reason\":\"invalid_redaction_mode\"")
    }

    @Test fun `task path exclusion removes only exact task attributed events`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.excludeTaskPaths=:fails")

        runFailingBuild(fixture, "fails")

        val jsonl = reportFor(fixture).readText()
        assertFalse(jsonl.contains("\"taskPath\":\":fails\""))
        assertContains(jsonl, "\"eventType\":\"build_finished\"")
    }

    @Test fun `malformed numeric properties do not alter a failing build outcome`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText(
            """
            buildDiagnostics.maxEvents=not-a-number
            buildDiagnostics.maxBytesPerBuild=-1
            buildDiagnostics.retainCompletedRuns=not-a-number
            """.trimIndent(),
        )

        runFailingBuild(fixture, "fails")

        assertContains(reportFor(fixture).readText(), "\"eventType\":\"build_finished\"")
    }

    @Test fun `parallel parser output stays ambiguous while failure paths stay exact`() {
        val fixture = createParallelFailureFixture()

        GradleRunner.create()
            .withProjectDir(fixture)
            .withArguments(":one:fails", ":two:fails", "--parallel", "--max-workers=2", "--configuration-cache")
            .withPluginClasspath()
            .buildAndFail()

        val jsonl = reportFor(fixture).readText()
        assertContains(jsonl, "\"taskPath\":\":one:fails\"")
        assertContains(jsonl, "\"taskPath\":\":two:fails\"")
        assertContains(jsonl, "parallel parser output")
        assertContains(jsonl, "\"attribution\":\"ambiguous\"")
    }

    @Test fun `explicit clean task removes only completed runs beyond retention`() {
        val fixture = createFailingTaskFixture()
        runFailingBuild(fixture, "fails")
        runFailingBuild(fixture, "fails")

        val result = GradleRunner.create()
            .withProjectDir(fixture)
            .withArguments("cleanBuildDiagnostics", "-PbuildDiagnostics.retainCompletedRuns=1", "--configuration-cache")
            .withPluginClasspath()
            .build()

        assertContains(result.output, "Pruned 1 completed build diagnostics run package(s); retained 1.")
        assertEquals(2, reportsFor(fixture).size, "One retained prior run plus the clean-task run should remain")
    }

    @Test fun `custom anchored parser matcher captures configured surrounding context`() {
        val fixture = createTempDirectory("gradle-build-diagnostics-custom-parser").toFile()
        fixture.resolve("settings.gradle.kts").writeText("plugins { id(\"me.monori.gradle-build-diagnostics\") }")
        fixture.resolve("gradle.properties").writeText(
            """
            buildDiagnostics.contextLinesBefore=2
            buildDiagnostics.contextLinesAfter=1
            buildDiagnostics.additionalErrorMatchers=^CUSTOM_FAIL: .+$
            """.trimIndent(),
        )
        fixture.resolve("build.gradle.kts").writeText(
            """
            tasks.register("succeeds") {
                doLast {
                    println("context one")
                    println("context two")
                    println("CUSTOM_FAIL: configured matcher")
                    println("  trailing context")
                }
            }
            """.trimIndent(),
        )

        runSuccessfulBuild(fixture, "succeeds")

        val jsonl = reportFor(fixture).readText()
        assertContains(jsonl, "CUSTOM_FAIL: configured matcher")
        assertContains(jsonl, "context one")
        assertContains(jsonl, "trailing context")
        assertContains(jsonl, "\"attribution\":\"ambiguous\"")
    }

    @Test fun `unanchored custom matcher is rejected without changing build outcome`() {
        val fixture = createFailingTaskFixture()
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.additionalErrorMatchers=unanchored")

        runFailingBuild(fixture, "fails")

        assertContains(reportFor(fixture).readText(), "\"reason\":\"invalid_parser_matcher\"")
    }

    @Test
    fun `writes a terminal success record for a successful build`() {
        val fixture = createTempDirectory("gradle-build-diagnostics-success").toFile()
        fixture.resolve("settings.gradle.kts").writeText("plugins { id(\"me.monori.gradle-build-diagnostics\") }")
        fixture.resolve("gradle.properties").writeText("buildDiagnostics.includeWarnings=true")
        fixture.resolve("build.gradle.kts").writeText("tasks.register(\"succeeds\") { doLast { println(\"w: fixture warning\") } }")

        GradleRunner.create()
            .withProjectDir(fixture)
            .withArguments("succeeds", "--configuration-cache")
            .withPluginClasspath()
            .build()

        val jsonl = reportFor(fixture).readText()
        assertContains(jsonl, "\"eventType\":\"build_started\"")
        assertContains(jsonl, "\"eventType\":\"task_finished\"")
        assertContains(jsonl, "\"taskPath\":\":succeeds\"")
        assertContains(jsonl, "\"eventType\":\"build_finished\"")
        assertContains(jsonl, "\"outcome\":\"success\"")
        assertContains(jsonl, "\"severity\":\"warning\"")
        assertContains(jsonl, "\"attribution\":\"ambiguous\"")
    }

    @Test
    fun `records the structured failure tree for a failing task`() {
        val fixture = createTempDirectory("gradle-build-diagnostics-fixture").toFile()
        fixture.resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("me.monori.gradle-build-diagnostics")
            }
            """.trimIndent(),
        )
        fixture.resolve("build.gradle.kts").writeText(
            """
            tasks.register("fails") {
                doLast {
                    throw GradleException("fixture failure message")
                }
            }
            """.trimIndent(),
        )

        runFailingBuild(fixture, "fails")

        val report = reportFor(fixture)
        assertTrue(report.isFile, "Expected capability probe output at $report")
        val jsonl = report.readText()
        assertContains(jsonl, "\"eventType\":\"diagnostic\"")
        assertContains(jsonl, "\"taskPath\":\":fails\"")
        assertContains(jsonl, "fixture failure message")
        assertContains(jsonl, "\"origin\":\"failure_tree\"")
        assertTrue(fixture.resolve(".gradle/build-diagnostics/latest.json").isFile)
        assertContains(report.parentFile.resolve("build-context.txt").readText(), "captured output is untrusted")
        println("ARTIFACT generic-task-failure: diagnostics.jsonl written")
    }

    @Test
    fun `records task failures on a configuration-cache reuse`() {
        val fixture = createFailingTaskFixture()

        runFailingBuild(fixture, "fails")
        runFailingBuild(fixture, "fails")

        val reports = reportsFor(fixture)
        assertEquals(2, reports.size, "Expected exactly one run package per invocation")
        println("CAPABILITY configuration-cache-reuse runs=${reports.size}: ${capabilitySummary(reports.last().readText())}")
    }

    @Test
    fun `writes a terminal failure record for a configuration failure after plugin registration`() {
        val fixture = createTempDirectory("gradle-build-diagnostics-config-failure").toFile()
        fixture.resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("me.monori.gradle-build-diagnostics")
            }
            """.trimIndent(),
        )
        fixture.resolve("build.gradle.kts").writeText("error(\"fixture configuration failure\")")

        runFailingBuild(fixture, "help")

        val report = reportFor(fixture)
        assertContains(report.readText(), "\"eventType\":\"build_finished\"")
        assertContains(report.readText(), "\"outcome\":\"failure\"")
    }

    @Test fun `early settings failure before plugin registration writes no diagnostic package`() {
        val fixture = createTempDirectory("gradle-build-diagnostics-early-settings-failure").toFile()
        fixture.resolve("settings.gradle.kts").writeText("error(\"fixture early settings failure\")")
        fixture.resolve("build.gradle.kts").writeText("")

        runFailingBuild(fixture, "help")

        assertTrue(reportsFor(fixture).isEmpty())
    }

    @Test
    fun `captures a Kotlin 2 3 21 compile failure as a structured task failure`() {
        assertKotlinCompilationFailure(
            kotlinVersion = "2.3.21",
            gradleVersion = System.getProperty("buildDiagnostics.minimumSupportedGradleVersion"),
        )
    }

    @Test
    fun `captures a Kotlin 2 4 0 compile failure as a structured task failure`() {
        assertKotlinCompilationFailure(
            kotlinVersion = "2.4.0",
            gradleVersion = System.getProperty("buildDiagnostics.currentTestedGradleVersion"),
        )
    }

    @Test
    fun `captures a warning from an Android library consumer`() {
        val fixture = createAndroidLibraryFixture()

        runSuccessfulBuild(fixture, "emitsAndroidWarning", configurationCache = false, gradleVersion = "9.5.0")

        val jsonl = reportFor(fixture).readText()
        assertContains(jsonl, "Android fixture warning")
        assertContains(jsonl, "\"severity\":\"warning\"")
        assertContains(jsonl, "\"attribution\":\"ambiguous\"")
    }

    @Test
    fun `captures a Kotlin Multiplatform compilation failure`() {
        val fixture = createKotlinMultiplatformFixture()

        runFailingBuild(fixture, "compileKotlinJvm", configurationCache = false, gradleVersion = "9.5.0")

        val jsonl = reportFor(fixture).readText()
        assertContains(jsonl, "\"taskPath\":\":compileKotlinJvm\"")
        assertContains(jsonl, "\"origin\":\"failure_tree\"")
    }

    private fun assertKotlinCompilationFailure(kotlinVersion: String, gradleVersion: String) {
        val fixture = createTempDirectory("gradle-build-diagnostics-kotlin-$kotlinVersion").toFile()
        fixture.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal() } }
            plugins { id("me.monori.gradle-build-diagnostics") }
            """.trimIndent(),
        )
        fixture.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
            }
            repositories { mavenCentral() }
            """.trimIndent(),
        )
        fixture.resolve("src/main/kotlin/Fixture.kt").apply {
            parentFile.mkdirs()
            writeText("fun broken(): String = 42")
        }

        val result = runFailingBuild(
            fixture = fixture,
            task = "compileKotlin",
            configurationCache = false,
            gradleVersion = gradleVersion,
        )

        val report = reportFor(fixture)
        assertTrue(
            report.isFile,
            "Expected a task-failure capability record. Gradle output:\n${result.output.takeLast(8_000)}",
        )
        val jsonl = report.readText()
        assertContains(jsonl, "\"taskPath\":\":compileKotlin\"")
        assertContains(jsonl, "\"origin\":\"failure_tree\"")
        println("COMPATIBILITY Gradle $gradleVersion / Kotlin $kotlinVersion: structured compilation failure captured")
    }

    private fun createFailingTaskFixture() = createTempDirectory("gradle-build-diagnostics-fixture").toFile().apply {
        resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("me.monori.gradle-build-diagnostics")
            }
            """.trimIndent(),
        )
        resolve("build.gradle.kts").writeText(
            """
            tasks.register("fails") {
                doLast {
                    throw GradleException("fixture failure message")
                }
            }
            """.trimIndent(),
        )
    }

    private fun createSuccessfulOutputFixture() = createTempDirectory("gradle-build-diagnostics-output").toFile().apply {
        resolve("settings.gradle.kts").writeText("plugins { id(\"me.monori.gradle-build-diagnostics\") }")
        resolve("build.gradle.kts").writeText("tasks.register(\"succeeds\") { doLast { println(\"e: token=trusted-local-value\") } }")
    }

    private fun createAndroidLibraryFixture() = createTempDirectory("gradle-build-diagnostics-android").toFile().apply {
        resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
            plugins { id("me.monori.gradle-build-diagnostics") }
            """.trimIndent(),
        )
        resolve("gradle.properties").writeText("buildDiagnostics.includeWarnings=true")
        resolve("local.properties").writeText("sdk.dir=${requireNotNull(System.getenv("ANDROID_HOME")).replace("\\", "\\\\")}")
        resolve("build.gradle.kts").writeText(
            """
            plugins { id("com.android.library") version "9.3.0" }

            android {
                namespace = "fixture.android"
                compileSdk = 37
            }

            tasks.register("emitsAndroidWarning") {
                doLast { println("w: Android fixture warning") }
            }
            """.trimIndent(),
        )
    }

    private fun createKotlinMultiplatformFixture() = createTempDirectory("gradle-build-diagnostics-kmp").toFile().apply {
        resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            plugins { id("me.monori.gradle-build-diagnostics") }
            """.trimIndent(),
        )
        resolve("build.gradle.kts").writeText(
            """
            plugins { kotlin("multiplatform") version "2.4.0" }

            kotlin { jvm() }
            """.trimIndent(),
        )
        resolve("src/commonMain/kotlin/Broken.kt").apply {
            parentFile.mkdirs()
            writeText("fun broken(): String = 42")
        }
    }

    private fun createParallelFailureFixture() = createTempDirectory("gradle-build-diagnostics-parallel").toFile().apply {
        resolve("settings.gradle.kts").writeText(
            """
            plugins { id("me.monori.gradle-build-diagnostics") }
            include(":one", ":two")
            """.trimIndent(),
        )
        listOf("one", "two").forEach { name ->
            resolve("$name/build.gradle.kts").apply {
                parentFile.mkdirs()
                writeText("tasks.register(\"fails\") { doLast { println(\"e: parallel parser output $name\"); throw GradleException(\"parallel failure $name\") } }")
            }
        }
    }

    private fun runSuccessfulBuild(
        fixture: java.io.File,
        task: String,
        configurationCache: Boolean = true,
        gradleVersion: String? = null,
    ): BuildResult =
        GradleRunner.create()
            .withProjectDir(fixture)
            .withArguments(buildList {
                add(task)
                if (configurationCache) add("--configuration-cache")
            })
            .withPluginClasspath()
            .let { runner -> gradleVersion?.let(runner::withGradleVersion) ?: runner }
            .build()

    private fun runFailingBuild(
        fixture: java.io.File,
        task: String,
        configurationCache: Boolean = true,
        gradleVersion: String? = null,
    ): BuildResult =
        GradleRunner.create()
            .withProjectDir(fixture)
            .withArguments(
                buildList {
                    add(task)
                    add("--stacktrace")
                    if (configurationCache) add("--configuration-cache")
                },
            )
            .withPluginClasspath()
            .let { runner -> gradleVersion?.let(runner::withGradleVersion) ?: runner }
            .buildAndFail()

    private fun reportFor(fixture: java.io.File) = reportsFor(fixture).single()

    private fun reportsFor(fixture: java.io.File): List<java.io.File> =
        fixture.resolve(".gradle/build-diagnostics/runs")
            .listFiles()
            ?.map { it.resolve("diagnostics.jsonl") }
            ?.filter { it.isFile }
            .orEmpty()

    private fun capabilitySummary(jsonl: String): String =
        "associatedProblemCount=" + Regex("\\\"associatedProblemCount\\\":(null|\\d+)")
            .find(jsonl)
            ?.groupValues
            ?.get(1)
}
