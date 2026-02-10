package com.phodal.routa.core.tool

import kotlinx.serialization.Serializable

/**
 * Unified result type for all agent coordination tools.
 */
@Serializable
data class ToolResult(
    /** Whether the tool call succeeded. */
    val success: Boolean,

    /** Human-readable result message or data (JSON). */
    val data: String,

    /** Error message if [success] is false. */
    val error: String? = null,
) {
    companion object {
        fun ok(data: String) = ToolResult(success = true, data = data)
        fun fail(error: String) = ToolResult(success = false, data = "", error = error)
    }
}
