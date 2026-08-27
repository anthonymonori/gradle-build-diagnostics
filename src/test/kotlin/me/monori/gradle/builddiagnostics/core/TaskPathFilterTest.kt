package me.monori.gradle.builddiagnostics.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskPathFilterTest {
    @Test
    fun `inclusions use full task path globs and exclusions win`() {
        val filter =
            TaskPathFilter(includes = listOf(":compile*"), excludes = listOf(":compileTest*"))

        assertTrue(filter.allows(":compileKotlin"))
        assertFalse(filter.allows(":compileTestKotlin"))
        assertFalse(filter.allows(":test"))
    }
}
