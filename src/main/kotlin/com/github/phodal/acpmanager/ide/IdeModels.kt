package com.github.phodal.acpmanager.ide

import kotlinx.serialization.Serializable

/**
 * Result of an IDE tool call.
 */
data class ToolCallResult(
    val content: List<String>,
    val isError: Boolean = false,
) {
    companion object {
        fun ok(vararg texts: String) = ToolCallResult(texts.toList())
        fun error(message: String) = ToolCallResult(listOf(message), isError = true)
    }
}

/**
 * Definition of an IDE tool that can be called by agents.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParam>,
)

/**
 * A tool parameter definition.
 */
data class ToolParam(
    val type: String, // "string", "number", "boolean", "array", "object"
    val required: Boolean = true,
    val description: String? = null,
)

/**
 * IDE notification events sent to agents.
 */
sealed class IdeNotification {
    abstract val method: String

    /**
     * Fired when editor selection changes.
     */
    data class SelectionChanged(
        val filePath: String?,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val selectedText: String?,
    ) : IdeNotification() {
        override val method = METHOD
        companion object {
            const val METHOD = "selection_changed"
        }
    }

    /**
     * Fired when user explicitly sends context to an agent (@ mention).
     */
    data class AtMentioned(
        val filePath: String,
        val startLine: Int? = null,
        val endLine: Int? = null,
    ) : IdeNotification() {
        override val method = METHOD
        companion object {
            const val METHOD = "at_mentioned"
        }
    }

    /**
     * Fired when diagnostics change for a file.
     */
    data class DiagnosticsChanged(
        val uri: String,
    ) : IdeNotification() {
        override val method = METHOD
        companion object {
            const val METHOD = "diagnostics_changed"
        }
    }
}

/**
 * Diagnostic severity levels, matching IntelliJ's HighlightSeverity.
 */
enum class DiagnosticSeverity {
    ERROR, WARNING, WEAK_WARNING, INFO;

    companion object {
        fun from(name: String): DiagnosticSeverity = when (name.uppercase()) {
            "ERROR" -> ERROR
            "WARNING" -> WARNING
            "WEAK_WARNING", "WEAK WARNING" -> WEAK_WARNING
            else -> INFO
        }
    }
}

/**
 * A single diagnostic item.
 */
@Serializable
data class DiagnosticItem(
    val message: String,
    val severity: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/**
 * Diagnostics for a file.
 */
@Serializable
data class FileDiagnostics(
    val uri: String,
    val diagnostics: List<DiagnosticItem>,
)
