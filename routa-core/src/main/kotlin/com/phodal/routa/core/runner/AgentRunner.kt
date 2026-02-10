package com.phodal.routa.core.runner

import com.phodal.routa.core.model.AgentRole

/**
 * Abstraction for running an agent with a prompt and getting a text response.
 *
 * This decouples the orchestration logic from the LLM execution backend.
 * Implementations can use Koog, ACP, or a simple mock for testing.
 *
 * During execution, the agent may call tools (via the LLM's function calling API).
 * When tools like `report_to_parent` are called, they update the stores directly.
 * The orchestrator checks the stores after each run to determine next steps.
 */
interface AgentRunner {

    /**
     * Run an agent with the given prompt and return its text output.
     *
     * The agent identified by [agentId] should already exist in the AgentStore.
     * Tool calls may be executed during this run (depending on implementation).
     *
     * @param role The agent's role (for selecting the right model/config).
     * @param agentId The agent's ID in the store.
     * @param prompt The input prompt for the agent.
     * @return The agent's text output.
     */
    suspend fun run(role: AgentRole, agentId: String, prompt: String): String
}
