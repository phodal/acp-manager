package com.phodal.routa.example.a2a.agent

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.koog.RoutaToolRegistry
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.role.RouteDefinitions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A2A AgentExecutor that runs a **real Koog AIAgent** with the ROUTA (planner) role.
 *
 * This is NOT a programmatic test — the agent actually calls an LLM (Ollama, DeepSeek, etc.)
 * which autonomously decides how to use the 12 Routa coordination tools:
 *
 * - `list_agents` — discover existing agents
 * - `create_agent` — spawn CRAFTER/GATE sub-agents
 * - `delegate_task` — assign tasks to agents
 * - `get_agent_status` / `get_agent_summary` — monitor progress
 * - `send_message_to_agent` — inter-agent communication
 * - `wake_or_create_task_agent` — lifecycle management
 * - `report_to_parent` — report completion
 * - `subscribe_to_events` / `unsubscribe_from_events` — event monitoring
 *
 * ## Architecture
 * ```
 * User → A2A → KoogPlannerA2AExecutor
 *                  ↓
 *              Koog AIAgent (LLM + singleRunStrategy)
 *                  ↓ tool calls
 *              RoutaToolRegistry (12 tools)
 *                  ↓
 *              AgentTools → RoutaSystem (stores, coordinator)
 * ```
 *
 * The LLM sees the user requirement and autonomously:
 * 1. Creates worker agents via `create_agent`
 * 2. Plans and delegates tasks
 * 3. Monitors progress via `get_agent_status`
 * 4. Reports results
 */
class KoogPlannerA2AExecutor(
    private val system: RoutaSystem,
    private val workspaceId: String,
    private val modelConfig: NamedModelConfig,
    private val maxIterations: Int = 20,
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val userMessage = context.params.message
        val userText = userMessage.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n") { it.text }

        println("[KoogPlanner] Received request: ${userText.take(100)}...")

        // Build a real Koog AIAgent with ROUTA role and all coordination tools
        val executor = RoutaAgentFactory.createExecutor(modelConfig)
        val model = RoutaAgentFactory.createModel(modelConfig)
        val toolRegistry = RoutaToolRegistry.create(system.tools, workspaceId)

        val roleDefinition = RouteDefinitions.forRole(AgentRole.ROUTA)

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = roleDefinition.systemPrompt,
            toolRegistry = toolRegistry,
            maxIterations = maxIterations,
            strategy = singleRunStrategy(),
        )

        // Run the agent — it will autonomously call tools via LLM
        println("[KoogPlanner] Starting Koog AIAgent with ${toolRegistry.tools.size} tools...")
        val result = try {
            agent.run(userText)
        } catch (e: Exception) {
            println("[KoogPlanner] Agent error: ${e.message}")
            "Error executing planner agent: ${e.message}"
        }

        println("[KoogPlanner] Agent completed. Result length: ${result.length}")

        // Send result back via A2A
        val responseMessage = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(result)),
            contextId = context.contextId,
        )
        eventProcessor.sendMessage(responseMessage)
    }
}
