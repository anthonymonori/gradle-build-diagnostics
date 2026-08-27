package me.monori.gradle.builddiagnostics.core

import java.nio.file.Path

/**
 * Keeps all diagnostic callbacks for one build writing to the same run folder.
 *
 * Gradle can call this plugin from separate listener and finalization objects. Without this
 * registry, each object could create its own folder or miss part of the build. Once a build is
 * finished, the next build gets a new writer and a new run folder.
 */
internal object RunArtifactWriterRegistry {
    private val writers = mutableMapOf<Path, RunArtifactWriter>()

    fun acquire(baseDirectory: Path, create: () -> RunArtifactWriter): RunArtifactWriter =
        synchronized(writers) {
            val key = baseDirectory.toAbsolutePath().normalize()
            val existing = writers[key]
            if (existing == null || existing.isFinished) create().also {
                writers[key] = it
            } else existing
        }
}
