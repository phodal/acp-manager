package com.phodal.routa.example.a2a.agent

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.koog.RoutaToolRegistry
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.role.RouteDefinitions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A2A AgentExecutor that runs a **real Koog AIAgent** with the GATE (verifier) role.
 *
 * The GATE agent uses LLM to autonomously verify the work done by other agents:
 *
 * 1. Uses `list_agents` to discover all agents in the workspace
 * 2. Uses `get_agent_status` to check each agent's progress
 * 3. Uses `read_agent_conversation` to review what was done
 * 4. Uses `get_agent_summary` for a quick overview
 * 5. Provides a verification verdict
 *
 * ## Architecture
 * ```
 * Verification Request → A2A → KoogGateA2AExecutor
 *                                  ↓
 *                              Koog AIAgent (LLM + GATE role)
 *                                  ↓ tool calls
 *                              RoutaToolRegistry (12 tools)
 *                                  ↓
 *                              AgentTools → RoutaSystem
 * ```
 */
class KoogGateA2AExecutor(
    private val system: RoutaSystem,
    private val workspaceId: String,
    private val modelConfig: NamedModelConfig,
    private val maxIterations: Int = 30, // Increased for thorough verification
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

        println("[KoogGate] Received verification request: ${userText.take(100)}...")

        val executor = RoutaAgentFactory.createExecutor(modelConfig)
        val model = RoutaAgentFactory.createModel(modelConfig)
        val toolRegistry = RoutaToolRegistry.create(system.tools, workspaceId)

        val roleDefinition = RouteDefinitions.forRole(AgentRole.GATE)

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = roleDefinition.systemPrompt,
            toolRegistry = toolRegistry,
            maxIterations = maxIterations,
            strategy = singleRunStrategy(),
        )

        println("[KoogGate] Starting Koog AIAgent (GATE) for verification...")
        val result = try {
            agent.run(userText)
        } catch (e: Exception) {
            println("[KoogGate] Agent error: ${e.message}")
            "Error executing gate agent: ${e.message}"
        }

        println("[KoogGate] Verification completed. Result length: ${result.length}")

        val responseMessage = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(result)),
            contextId = context.contextId,
        )
        eventProcessor.sendMessage(responseMessage)
    }
}
