@file:OptIn(ExperimentalUuidApi::class)

package com.phodal.routa.example.a2a.scenario

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.model.Task
import com.phodal.routa.example.a2a.agent.KoogGateA2AExecutor
import com.phodal.routa.example.a2a.agent.KoogPlannerA2AExecutor
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.server.cio.CIO as ServerCIO
import kotlinx.coroutines.*
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * End-to-end scenario with **real Koog AI Agents** communicating via A2A.
 *
 * This scenario actually calls an LLM (Ollama, DeepSeek, etc.) — the agents
 * autonomously plan tasks, create sub-agents, delegate work, and verify results.
 *
 * ## What happens:
 *
 * 1. A shared RoutaSystem is created (in-memory stores + coordinator)
 * 2. A Koog **Planner Agent** (ROUTA role) is started as an A2A server
 *    - It has access to all 12 Routa coordination tools
 *    - The LLM autonomously decides which tools to call
 * 3. A Koog **Gate Agent** (GATE role) is started as an A2A server
 *    - It verifies the planner's work by inspecting agent state
 * 4. A user requirement is sent to the Planner via A2A
 * 5. The Planner's LLM autonomously:
 *    - Creates CRAFTER agents via `create_agent`
 *    - Breaks the requirement into tasks
 *    - Delegates tasks via `delegate_task`
 *    - Reports via `report_to_parent`
 * 6. The Gate agent is asked to verify the work
 *    - Uses `list_agents`, `get_agent_status`, `read_agent_conversation`
 *    - Provides a verification verdict
 *
 * ## Prerequisites
 *
 * A valid LLM config in `~/.autodev/config.yaml`:
 * ```yaml
 * active: default
 * configs:
 *   - name: default
 *     provider: ollama
 *     model: llama3.2
 * ```
 *
 * ## Usage
 * ```bash
 * ./gradlew :examples:agent-hub-a2a-example:runRealAgents
 * ```
 */

const val PLANNER_A2A_PORT = 9200
const val PLANNER_A2A_PATH = "/koog-planner"
const val PLANNER_A2A_CARD_PATH = "$PLANNER_A2A_PATH/agent-card.json"

const val GATE_A2A_PORT = 9201
const val GATE_A2A_PATH = "/koog-gate"
const val GATE_A2A_CARD_PATH = "$GATE_A2A_PATH/agent-card.json"

suspend fun main() {
    println("╔════════════════════════════════════════════════════════════╗")
    println("║  Routa Agent Hub — Real AI Agent E2E via A2A              ║")
    println("║  Planner (ROUTA) + Gate (GATE) with real LLM calls       ║")
    println("╚════════════════════════════════════════════════════════════╝")
    println()

    // ── Step 0: Check LLM config ────────────────────────────────────────
    val modelConfig = RoutaConfigLoader.getActiveModelConfig()
    if (modelConfig == null) {
        println("✗ No LLM config found at: ${RoutaConfigLoader.getConfigPath()}")
        println()
        println("Please create ~/.autodev/config.yaml with:")
        println("  active: default")
        println("  configs:")
        println("    - name: default")
        println("      provider: ollama")
        println("      model: llama3.2")
        println()
        println("Or use any provider: deepseek, openai, anthropic, etc.")
        return
    }
    println("✓ LLM config: ${modelConfig.provider} / ${modelConfig.model}")
    println()

    // ── Step 1: Create shared RoutaSystem ───────────────────────────────
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val routa = RoutaFactory.createInMemory(scope)
    val workspaceId = "real-agent-test"
    val routaAgentId = routa.coordinator.initialize(workspaceId)
    println("✓ RoutaSystem initialized (workspace: $workspaceId, routaId: ${routaAgentId.take(8)}...)")

    // Pre-create some tasks for the planner to work with
    seedTasks(routa, workspaceId)
    println("✓ Seeded sample tasks")
    println()

    // ── Step 2: Start Planner A2A Server ────────────────────────────────
    println("Starting Koog Planner Agent (ROUTA role) on port $PLANNER_A2A_PORT...")
    val plannerCard = buildPlannerAgentCard()
    val plannerServer = A2AServer(
        agentExecutor = KoogPlannerA2AExecutor(routa, workspaceId, modelConfig),
        agentCard = plannerCard,
    )
    val plannerTransport = HttpJSONRPCServerTransport(plannerServer)

    val plannerJob = scope.launch {
        plannerTransport.start(
            engineFactory = ServerCIO,
            port = PLANNER_A2A_PORT,
            path = PLANNER_A2A_PATH,
            wait = true,
            agentCard = plannerCard,
            agentCardPath = PLANNER_A2A_CARD_PATH,
        )
    }

    // ── Step 3: Start Gate A2A Server ───────────────────────────────────
    println("Starting Koog Gate Agent (GATE role) on port $GATE_A2A_PORT...")
    val gateCard = buildGateAgentCard()
    val gateServer = A2AServer(
        agentExecutor = KoogGateA2AExecutor(routa, workspaceId, modelConfig),
        agentCard = gateCard,
    )
    val gateTransport = HttpJSONRPCServerTransport(gateServer)

    val gateJob = scope.launch {
        gateTransport.start(
            engineFactory = ServerCIO,
            port = GATE_A2A_PORT,
            path = GATE_A2A_PATH,
            wait = true,
            agentCard = gateCard,
            agentCardPath = GATE_A2A_CARD_PATH,
        )
    }

    delay(2000) // Wait for servers to start
    println("✓ Both A2A servers started")
    println()

    // ── Step 4: Send requirement to Planner via A2A ─────────────────────
    println("═".repeat(60))
    println("  PHASE 1: Sending requirement to Planner Agent (ROUTA)")
    println("═".repeat(60))
    println()

    val plannerResult = sendToAgent(
        port = PLANNER_A2A_PORT,
        path = PLANNER_A2A_PATH,
        cardPath = PLANNER_A2A_CARD_PATH,
        message = buildString {
            appendLine("You are the ROUTA coordinator for workspace '$workspaceId'.")
            appendLine("Your agent ID is: $routaAgentId")
            appendLine()
            appendLine("The workspace already has tasks seeded. Please:")
            appendLine("1. Use list_agents to see the current agents")
            appendLine("2. Create 2 CRAFTER agents named 'api-developer' and 'test-writer'")
            appendLine("3. Check their status with get_agent_status")
            appendLine("4. Send a message to api-developer asking it to start work")
            appendLine("5. List all agents again to confirm the roster")
            appendLine()
            appendLine("User requirement: Build a simple REST API with a /health endpoint and unit tests.")
        }
    )

    println()
    println("── Planner Agent Response ──")
    println(plannerResult.take(2000))
    println("─".repeat(40))
    println()

    // ── Step 5: Send verification request to Gate via A2A ───────────────
    println("═".repeat(60))
    println("  PHASE 2: Sending verification request to Gate Agent (GATE)")
    println("═".repeat(60))
    println()

    val gateResult = sendToAgent(
        port = GATE_A2A_PORT,
        path = GATE_A2A_PATH,
        cardPath = GATE_A2A_CARD_PATH,
        message = buildString {
            appendLine("You are the GATE verifier for workspace '$workspaceId'.")
            appendLine()
            appendLine("Please verify the work done by the planner:")
            appendLine("1. Use list_agents to see all agents that were created")
            appendLine("2. For each agent, use get_agent_status to check their state")
            appendLine("3. Use get_agent_summary for a quick overview of each agent")
            appendLine("4. Read the conversation of at least one agent with read_agent_conversation")
            appendLine("5. Provide your verification verdict: were agents created? Were tasks managed?")
        }
    )

    println()
    println("── Gate Agent Response ──")
    println(gateResult.take(2000))
    println("─".repeat(40))
    println()

    // ── Step 6: Final system state ──────────────────────────────────────
    println("═".repeat(60))
    println("  FINAL STATE: Agent Roster & Event Log")
    println("═".repeat(60))
    println()

    val finalAgents = routa.tools.listAgents(workspaceId)
    println("Agents:\n${finalAgents.data}")

    val eventLog = routa.eventBus.getTimestampedLog()
    println("\nEvent log: ${eventLog.size} events recorded")
    for (entry in eventLog.takeLast(10)) {
        println("  ${entry.timestamp}: ${entry.event}")
    }

    println()
    println("═".repeat(60))
    println("  ✅ Real AI Agent E2E scenario complete!")
    println("═".repeat(60))

    // Cleanup
    routa.coordinator.shutdown()
    plannerJob.cancel()
    gateJob.cancel()
    scope.cancel()
}

private suspend fun sendToAgent(
    port: Int,
    path: String,
    cardPath: String,
    message: String,
): String {
    // Use custom HTTP client with longer timeout for LLM processing
    val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 300_000 // 5 minutes for LLM to process
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 300_000
        }
    }
    val transport = HttpJSONRPCClientTransport(
        url = "http://localhost:$port$path",
        baseHttpClient = httpClient
    )
    val resolver = UrlAgentCardResolver(baseUrl = "http://localhost:$port", path = cardPath)
    val client = A2AClient(transport = transport, agentCardResolver = resolver)

    return try {
        client.connect()
        val agentCard = client.cachedAgentCard()
        println("  Connected to: ${agentCard.name} (${agentCard.description.take(60)}...)")

        val contextId = "real-test-${System.currentTimeMillis()}"
        val msg = Message(
            messageId = Uuid.random().toString(),
            role = Role.User,
            parts = listOf(TextPart(message)),
            contextId = contextId,
        )

        println("  Sending message to agent (LLM will process autonomously)...")
        val response = client.sendMessage(Request(MessageSendParams(message = msg)))
        val reply = response.data as? Message ?: return "Error: unexpected response type"
        reply.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    } finally {
        transport.close()
        httpClient.close()
    }
}

private suspend fun seedTasks(routa: RoutaSystem, workspaceId: String) {
    val tasks = listOf(
        Task(
            id = "task-api",
            title = "Implement REST API",
            objective = "Create a /health endpoint that returns 200 OK with JSON status",
            workspaceId = workspaceId,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        ),
        Task(
            id = "task-tests",
            title = "Write unit tests",
            objective = "Write tests for the /health endpoint to verify JSON response",
            workspaceId = workspaceId,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        ),
    )
    tasks.forEach { routa.context.taskStore.save(it) }
}

private fun buildPlannerAgentCard() = AgentCard(
    protocolVersion = "0.3.0",
    name = "Koog Planner Agent (ROUTA)",
    description = "Real Koog AI Agent with ROUTA role — autonomously plans tasks, " +
        "creates sub-agents, and delegates work using LLM-powered decision making.",
    version = "0.1.0",
    url = "http://localhost:$PLANNER_A2A_PORT$PLANNER_A2A_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "http://localhost:$PLANNER_A2A_PORT$PLANNER_A2A_PATH",
            transport = TransportProtocol.JSONRPC,
        )
    ),
    capabilities = AgentCapabilities(
        streaming = false,
        pushNotifications = false,
        stateTransitionHistory = false,
    ),
    defaultInputModes = listOf("text"),
    defaultOutputModes = listOf("text"),
    skills = listOf(
        AgentSkill(
            id = "autonomous_planning",
            name = "Autonomous Task Planning & Agent Coordination",
            description = "Uses LLM to autonomously plan tasks, create agents, " +
                "delegate work, and monitor progress via Routa coordination tools.",
            examples = listOf("Build a REST API with authentication and tests"),
            tags = listOf("planning", "autonomous", "multi-agent", "llm")
        )
    ),
    supportsAuthenticatedExtendedCard = false,
)

private fun buildGateAgentCard() = AgentCard(
    protocolVersion = "0.3.0",
    name = "Koog Gate Agent (GATE)",
    description = "Real Koog AI Agent with GATE role — autonomously verifies " +
        "work done by other agents using LLM-powered analysis.",
    version = "0.1.0",
    url = "http://localhost:$GATE_A2A_PORT$GATE_A2A_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "http://localhost:$GATE_A2A_PORT$GATE_A2A_PATH",
            transport = TransportProtocol.JSONRPC,
        )
    ),
    capabilities = AgentCapabilities(
        streaming = false,
        pushNotifications = false,
        stateTransitionHistory = false,
    ),
    defaultInputModes = listOf("text"),
    defaultOutputModes = listOf("text"),
    skills = listOf(
        AgentSkill(
            id = "autonomous_verification",
            name = "Autonomous Work Verification",
            description = "Uses LLM to autonomously inspect agent state, " +
                "read conversations, and verify work quality.",
            examples = listOf("Verify that agents completed their tasks correctly"),
            tags = listOf("verification", "autonomous", "quality", "llm")
        )
    ),
    supportsAuthenticatedExtendedCard = false,
)
