package com.github.phodal.acpmanager.config

/**
 * Well-known ACP agent presets with auto-detection.
 *
 * Provides common ACP-compliant CLI tools (Kimi, Gemini, Claude, Codex, etc.)
 * with their standard command-line invocations. Detects which agents are
 * installed on the system by checking PATH.
 */
data class AcpAgentPreset(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String>,
    val description: String,
    /**
     * Whether this agent uses a non-standard ACP API.
     * Claude Code natively supports ACP without needing an --acp flag.
     */
    val nonStandardApi: Boolean = false,
) {
    fun toConfig(): AcpAgentConfig = AcpAgentConfig(
        command = command,
        args = args,
        env = emptyMap(),
        description = name,
        autoApprove = false,
    )
}

/**
 * Known ACP agent presets.
 */
object AcpAgentPresets {
    val allPresets = listOf(
        AcpAgentPreset(
            id = "opencode",
            name = "OpenCode",
            command = "opencode",
            args = listOf("acp"),
            description = "OpenCode AI coding agent"
        ),
        AcpAgentPreset(
            id = "kimi",
            name = "Kimi",
            command = "kimi",
            args = listOf("acp"),
            description = "Moonshot AI's Kimi CLI"
        ),
        AcpAgentPreset(
            id = "gemini",
            name = "Gemini",
            command = "gemini",
            args = listOf("--experimental-acp"),
            description = "Google Gemini CLI"
        ),
        AcpAgentPreset(
            id = "claude",
            name = "Claude Code",
            command = "claude",
            args = emptyList(),
            description = "Anthropic Claude Code (native ACP support)",
            nonStandardApi = true,
        ),
        // Codex requires codex-acp wrapper: https://github.com/cola-io/codex-acp
        // Install with: npm install -g codex-acp
        AcpAgentPreset(
            id = "codex",
            name = "Codex",
            command = "codex-acp",
            args = emptyList(),
            description = "OpenAI Codex CLI (via codex-acp wrapper)"
        ),
        AcpAgentPreset(
            id = "copilot",
            name = "GitHub Copilot",
            command = "copilot",
            args = listOf("--acp"),
            description = "GitHub Copilot CLI"
        ),
        AcpAgentPreset(
            id = "auggie",
            name = "Auggie",
            command = "auggie",
            args = listOf("--acp"),
            description = "Augment Code's AI agent"
        ),
    )

    /**
     * Detect installed presets by checking if the command is in PATH.
     */
    fun detectInstalled(): List<AcpAgentPreset> {
        return allPresets.mapNotNull { preset ->
            val resolvedPath = findExecutable(preset.command)
            if (resolvedPath != null) {
                preset.copy(command = resolvedPath)
            } else {
                null
            }
        }
    }

    /**
     * Check if a command is available in PATH.
     * Returns the resolved path if available, null otherwise.
     */
    fun findExecutable(command: String): String? {
        return try {
            // If command is already an absolute path, check if it exists
            val file = java.io.File(command)
            if (file.isAbsolute) {
                return if (file.exists() && file.canExecute()) command else null
            }

            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val checkCmd = if (isWindows) listOf("where", command) else listOf("which", command)

            val process = ProcessBuilder(checkCmd)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotBlank()) {
                // On Windows, `where` may return multiple lines; take the first
                output.lines().firstOrNull()?.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a command is available (either in PATH or as absolute path).
     */
    fun isCommandAvailable(command: String): Boolean {
        return findExecutable(command) != null
    }
}
