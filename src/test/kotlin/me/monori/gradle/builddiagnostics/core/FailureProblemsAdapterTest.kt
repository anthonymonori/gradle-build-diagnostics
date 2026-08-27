package me.monori.gradle.builddiagnostics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailureProblemsAdapterTest {
    @Test
    fun `reads available problem fields without Gradle problem types`() {
        val problems = FailureProblemsAdapter().extract(FakeFailure())

        assertEquals(1, problems.size)
        assertEquals(Severity.WARNING, problems.single().severity)
        assertEquals(Category.DEPRECATION, problems.single().category)
        assertEquals("Deprecated option", problems.single().message)
        assertEquals("src/main/kotlin/Example.kt", problems.single().location?.path)
        assertEquals(12, problems.single().location?.line)
    }

    @Test
    fun `missing runtime method is an empty compatibility result`() {
        assertTrue(FailureProblemsAdapter().extract(Any()).isEmpty())
    }

    @Suppress("unused")
    private class FakeFailure {
        fun getProblems() = listOf(FakeProblem())
    }

    @Suppress("unused")
    private class FakeProblem {
        fun getSeverity() = "WARNING"
        fun getContextualLabel() = "Deprecated option"
        fun getLocation() = FakeLocation()
    }

    @Suppress("unused")
    private class FakeLocation {
        fun getPath() = "src/main/kotlin/Example.kt"
        fun getLine() = 12
    }
}
