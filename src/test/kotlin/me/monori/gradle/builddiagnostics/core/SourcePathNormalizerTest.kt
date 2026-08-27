package me.monori.gradle.builddiagnostics.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SourcePathNormalizerTest {
    @Test
    fun `relativizes only absolute paths inside the consumer root`() {
        val normalizer = SourcePathNormalizer(java.nio.file.Path.of("/workspace/consumer"))

        assertEquals(
            "src/main/kotlin/Example.kt",
            normalizer.normalize("/workspace/consumer/src/main/kotlin/Example.kt")
        )
        assertEquals("/outside/Tool.kt", normalizer.normalize("/outside/Tool.kt"))
        assertEquals("relative/File.kt", normalizer.normalize("relative/File.kt"))
    }
}
