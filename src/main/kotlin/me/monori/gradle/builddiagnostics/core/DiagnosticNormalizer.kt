package me.monori.gradle.builddiagnostics.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class NormalizationLimits(
    val maxMessageBytes: Int = 4 * KiB,
    val maxContextBytes: Int = 16 * KiB,
)

/** Controls whether captured text is scrubbed before it is written to disk. */
internal enum class RedactionMode {
    /** Remove common credentials and private keys, plus consumer-supplied patterns. */
    CONSERVATIVE,
    /** Keep captured text unchanged; appropriate only for trusted local use. */
    DISABLED;

    companion object {
        fun from(value: String): RedactionMode? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

internal class DiagnosticNormalizer(
    private val limits: NormalizationLimits = NormalizationLimits(),
    redactionMode: RedactionMode = RedactionMode.CONSERVATIVE,
    additionalRedactions: List<Regex> = emptyList(),
    private val pathNormalizer: (String?) -> String? = { it },
) {
    private val redactions = when (redactionMode) {
        RedactionMode.CONSERVATIVE -> ConservativeRedactions.patterns + additionalRedactions
        RedactionMode.DISABLED -> emptyList()
    }

    fun normalize(draft: DiagnosticDraft): NormalizedDiagnostic {
        val message = clean(draft.message, limits.maxMessageBytes)
        var truncated = message.second
        var redacted = message.third
        var remaining = limits.maxContextBytes
        val context = buildList {
            for (line in draft.context) {
                if (remaining <= 0) {
                    truncated = true; break
                }
                val cleaned = clean(line, remaining)
                add(cleaned.first)
                remaining -= cleaned.first.toByteArray(StandardCharsets.UTF_8).size
                truncated = truncated || cleaned.second
                redacted = redacted || cleaned.third
            }
        }
        val normalized = draft.copy(
            message = message.first,
            context = context,
            location = draft.location?.copy(path = pathNormalizer(draft.location.path))
        )
        return NormalizedDiagnostic(normalized, fingerprint(normalized), truncated, redacted)
    }

    private fun clean(value: String, byteLimit: Int): Triple<String, Boolean, Boolean> {
        var text = value.replace(ANSI, "").replace("\r\n", "\n").replace('\r', '\n')
        text = text.filter { it == '\n' || it == '\t' || it.code >= 0x20 }
        var redacted = false
        redactions.forEach { regex ->
            text = regex.replace(text) { match ->
                redacted = true
                when {
                    match.value.contains("PRIVATE KEY") -> "[REDACTED_PRIVATE_KEY]"
                    match.value.startsWith(
                        "http", ignoreCase = true
                    ) -> match.groupValues[1] + "[REDACTED_CREDENTIALS]@"

                    else -> match.value.substringBefore(
                        ":", match.value.substringBefore("=", "")
                    ) + ": [REDACTED]"
                }
            }
        }
        val bounded = truncateUtf8(text, byteLimit)
        return Triple(bounded.first, bounded.second, redacted)
    }

    private fun fingerprint(draft: DiagnosticDraft): String {
        val material = listOf(
            draft.severity,
            draft.category,
            draft.origin,
            draft.message,
            draft.context.joinToString("\n"),
            draft.taskPath,
            draft.location?.path,
            draft.location?.line,
            draft.location?.column
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun truncateUtf8(text: String, limit: Int): Pair<String, Boolean> {
        if (text.toByteArray(StandardCharsets.UTF_8).size <= limit) return text to false
        val suffix = "…[truncated]"
        val target = (limit - suffix.toByteArray(StandardCharsets.UTF_8).size).coerceAtLeast(0)
        val out = StringBuilder()
        text.codePoints().forEach { codePoint ->
            val candidate = String(Character.toChars(codePoint))
            if ((out.toString() + candidate).toByteArray(StandardCharsets.UTF_8).size <= target) out.append(
                candidate
            )
        }
        return (out.toString() + suffix) to true
    }

    private companion object {
        // Terminal colour and formatting escape sequences are noise in durable artifacts.
        val ANSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    }
}

/** Built-in patterns for commonly found sensitive data in build output. */
private object ConservativeRedactions {
    // Credentials embedded in HTTP(S) repository URLs.
    private val urlCredentials = Regex("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@")

    // Bearer tokens passed through HTTP authorization headers.
    private val bearerTokens = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+")

    // Common key/value spellings used in command output and configuration errors.
    private val namedSecrets =
        Regex("(?i)((?:api[-_]?key|token|password|secret)\\s*[:=]\\s*)[^\\s,;]+")

    // Multi-line PEM private keys.
    private val privateKeys =
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")

    val patterns = listOf(urlCredentials, bearerTokens, namedSecrets, privateKeys)
}
