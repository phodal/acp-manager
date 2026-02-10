package com.phodal.routa.core.runner

import com.phodal.routa.core.model.AgentRole

/**
 * Composite agent runner that routes different roles to different backends.
 *
 * Default strategy:
 * - **ROUTA** (coordinator): Uses Koog LLM for planning (text output with @@@task blocks)
 * - **CRAFTER** (implementor): Uses ACP to spawn a real coding agent that can edit files
 * - **GATE** (verifier): Uses Koog LLM for verification (reviews conversations)
 *
 * When no ACP agent is configured, falls back to Koog for all roles.
 *
 * Usage:
 * ```kotlin
 * val runner = CompositeAgentRunner(
 *     koogRunner = KoogAgentRunner(tools, workspace),
 *     acpRunner = AcpAgentRunner("codex", config, cwd),
 * )
 * ```
 */
class CompositeAgentRunner(
    private val koogRunner: AgentRunner,
    private val acpRunner: AgentRunner? = null,
) : AgentRunner {

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        val runner = when (role) {
            AgentRole.ROUTA -> koogRunner    // Plans via LLM
            AgentRole.CRAFTER -> acpRunner ?: koogRunner  // Real agent or fallback to LLM
            AgentRole.GATE -> koogRunner     // Verifies via LLM
        }

        return runner.run(role, agentId, prompt)
    }
}
