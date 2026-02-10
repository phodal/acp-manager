package com.github.phodal.acpmanager.mcp

import kotlinx.serialization.Serializable

/**
 * MCP-specific models for IDE integration.
 * 
 * These models mirror Claude Code's MCP tool arguments and notification payloads.
 */

// ===== Tool Arguments =====

@Serializable
data class OpenFileArgs(
    val filePath: String,
    val makeFrontmost: Boolean = true
)

@Serializable
data class OpenFilesInEditorArgs(
    val file_paths: List<String>
)

@Serializable
data class CloseTabToolArgs(
    val tab_name: String
)

@Serializable
data class ReformatFileArgs(
    val file_path: String
)

@Serializable
data class OpenDiffToolArgs(
    val old_file_path: String,
    val new_file_contents: String,
    val tab_name: String = "Diff"
)

@Serializable
data class GetDiagnosticsArgs(
    val uri: String? = null,
    val severity: String? = null
)

@Serializable
data class NoArgs(
    val dummy: String? = null
)

// ===== Tool Results =====

@Serializable
data class OpenedFilesResults(
    val opened_files: List<String>
)

@Serializable
data class FileDiagnostic(
    val uri: String,
    val diagnostics: List<Diagnostic>
)

@Serializable
data class Diagnostic(
    val message: String,
    val severity: String, // "ERROR", "WARNING", "INFO", "HINT"
    val range: CodeRange,
    val source: String? = null,
    val code: String? = null
)

@Serializable
data class CodeRange(
    val start: CursorPosition,
    val end: CursorPosition
)

@Serializable
data class CursorPosition(
    val line: Int,
    val character: Int
)

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT;

    companion object {
        fun from(name: String): DiagnosticSeverity {
            return when (name.uppercase()) {
                "ERROR" -> ERROR
                "WARNING" -> WARNING
                "INFO" -> INFO
                "HINT" -> HINT
                else -> HINT
            }
        }
    }
}

// ===== Notifications =====

@Serializable
data class SelectionChangedNotification(
    val range: CodeRange,
    val text: String?,
    val filePath: String?
)

@Serializable
data class AtMentionedNotification(
    val filePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null
)

@Serializable
data class IdeConnectedNotification(
    val pid: Long,
    val pluginVersion: String,
    val ideVersion: String,
    val isPluginVersionUnsupported: Boolean = false
)

@Serializable
data class DiagnosticsChangedNotification(
    val uri: String
)

/**
 * Notification method names used in MCP protocol.
 */
object NotificationMethods {
    const val SELECTION_CHANGED = "notifications/selection_changed"
    const val AT_MENTIONED = "notifications/at_mentioned"
    const val IDE_CONNECTED = "notifications/ide_connected"
    const val DIAGNOSTICS_CHANGED = "notifications/diagnostics_changed"
}

