package com.phodal.routa.core.runner

import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.tool.AgentTools

/**
 * Agent runner backed by JetBrains Koog AIAgent framework.
 *
 * Creates a Koog AIAgent for each run, using the LLM config from
 * `~/.autodev/config.yaml` or an explicit [modelConfig].
 *
 * Tool calls are handled natively by Koog — when the LLM generates
 * a tool call (e.g., `report_to_parent`), Koog dispatches it to our
 * [SimpleTool] implementations which update the stores.
 */
class KoogAgentRunner(
    private val agentTools: AgentTools,
    private val workspaceId: String,
    private val modelConfig: NamedModelConfig? = null,
) : AgentRunner {

    private val factory by lazy {
        RoutaAgentFactory(agentTools, workspaceId)
    }

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        val agent = factory.createAgent(role, modelConfig)
        return try {
            agent.run(prompt)
        } finally {
            agent.close()
        }
    }
}
