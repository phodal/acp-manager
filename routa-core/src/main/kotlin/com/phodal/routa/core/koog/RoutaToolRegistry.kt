package com.phodal.routa.core.koog

import ai.koog.agents.core.tools.ToolRegistry
import com.phodal.routa.core.tool.AgentTools

/**
 * Factory for building a Koog [ToolRegistry] containing all Routa coordination tools.
 *
 * Usage with a Koog AIAgent:
 * ```kotlin
 * val toolRegistry = RoutaToolRegistry.create(routa.tools, "my-workspace")
 *
 * val agent = AIAgent(
 *     promptExecutor = executor,
 *     systemPrompt = RouteDefinitions.ROUTA.systemPrompt,
 *     llmModel = model,
 *     toolRegistry = toolRegistry
 * )
 * ```
 */
object RoutaToolRegistry {

    /**
     * Create a [ToolRegistry] with all 12 Routa coordination tools.
     *
     * @param agentTools The underlying AgentTools implementation.
     * @param workspaceId Default workspace ID for tools that need it.
     */
    fun create(agentTools: AgentTools, workspaceId: String): ToolRegistry {
        return ToolRegistry {
            // Core coordination tools
            tool(ListAgentsTool(agentTools, workspaceId))
            tool(ReadAgentConversationTool(agentTools))
            tool(CreateAgentTool(agentTools, workspaceId))
            tool(DelegateTaskTool(agentTools))
            tool(MessageAgentTool(agentTools))
            tool(ReportToParentTool(agentTools))
            // Task-agent lifecycle tools
            tool(WakeOrCreateTaskAgentTool(agentTools, workspaceId))
            tool(SendMessageToTaskAgentTool(agentTools))
            tool(GetAgentStatusTool(agentTools))
            tool(GetAgentSummaryTool(agentTools))
            // Event subscription tools
            tool(SubscribeToEventsTool(agentTools))
            tool(UnsubscribeFromEventsTool(agentTools))
        }
    }
}
