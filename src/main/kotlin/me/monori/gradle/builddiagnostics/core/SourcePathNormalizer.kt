package me.monori.gradle.builddiagnostics.core

import java.nio.file.Path

internal class SourcePathNormalizer(private val root: Path) {
    fun normalize(value: String?): String? = value?.let { path ->
        runCatching {
            val candidate = Path.of(path).normalize()
            if (candidate.isAbsolute && candidate.startsWith(root)) root.relativize(candidate)
                .toString() else path
        }.getOrDefault(path)
    }
}
