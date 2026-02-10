package com.phodal.routa.core.tool

import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.event.EventBus
import com.phodal.routa.core.model.*
import com.phodal.routa.core.store.AgentStore
import com.phodal.routa.core.store.ConversationStore
import com.phodal.routa.core.store.TaskStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * The 7 agent coordination tools that enable multi-agent collaboration.
 *
 * These tools are designed to be exposed via MCP (Model Context Protocol) so that
 * LLM-powered agents can call them during their conversation turns.
 *
 * Mapping from Issue #21:
 * - list_agents() → [listAgents]
 * - read_agent_conversation() → [readAgentConversation]
 * - create_agent() → [createAgent]
 * - delegate() → [delegate]
 * - message_agent() → [messageAgent]
 * - wait_for_agent() → [waitForAgent]
 * - report_to_parent() → [reportToParent]
 */
class AgentTools(
    private val agentStore: AgentStore,
    private val conversationStore: ConversationStore,
    private val taskStore: TaskStore,
    private val eventBus: EventBus,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {

    /**
     * List all agents in the workspace.
     *
     * Used by Crafters to discover sibling agents and avoid file conflicts,
     * and by Gates to find implementors whose work needs verification.
     */
    suspend fun listAgents(workspaceId: String): ToolResult {
        val agents = agentStore.listByWorkspace(workspaceId)
        val summary = agents.map { agent ->
            mapOf(
                "id" to agent.id,
                "name" to agent.name,
                "role" to agent.role.name,
                "status" to agent.status.name,
                "parentId" to (agent.parentId ?: "none"),
            )
        }
        return ToolResult.ok(json.encodeToString(summary))
    }

    /**
     * Read another agent's conversation history.
     *
     * Critical for conflict avoidance (Crafters check what siblings touched)
     * and for verification (Gates review what implementors did).
     *
     * @param agentId The agent whose conversation to read.
     * @param lastN Optional: only return the last N messages.
     * @param startTurn Optional: start from this turn number.
     * @param endTurn Optional: end at this turn number.
     * @param includeToolCalls Whether to include tool call messages.
     */
    suspend fun readAgentConversation(
        agentId: String,
        lastN: Int? = null,
        startTurn: Int? = null,
        endTurn: Int? = null,
        includeToolCalls: Boolean = true,
    ): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")

        var messages = when {
            lastN != null -> conversationStore.getLastN(agentId, lastN)
            startTurn != null && endTurn != null -> conversationStore.getByTurnRange(agentId, startTurn, endTurn)
            else -> conversationStore.getConversation(agentId)
        }

        if (!includeToolCalls) {
            messages = messages.filter { it.role != MessageRole.TOOL }
        }

        val summary = messages.map { msg ->
            mapOf(
                "role" to msg.role.name,
                "content" to msg.content,
                "timestamp" to msg.timestamp,
                "turn" to (msg.turn?.toString() ?: ""),
            )
        }

        return ToolResult.ok(json.encodeToString(summary))
    }

    /**
     * Create a new agent with the specified role.
     *
     * Called by Routa to spin up Crafter or Gate agents for delegated tasks.
     *
     * @param name Human-readable name (e.g., "crafter-auth-module").
     * @param role The agent role (ROUTA, CRAFTER, or GATE).
     * @param workspaceId The workspace to create the agent in.
     * @param parentId The ID of the agent creating this one.
     * @param modelTier Optional model tier override.
     */
    suspend fun createAgent(
        name: String,
        role: AgentRole,
        workspaceId: String,
        parentId: String? = null,
        modelTier: ModelTier? = null,
    ): ToolResult {
        val now = Instant.now().toString()
        val agent = Agent(
            id = UUID.randomUUID().toString(),
            name = name,
            role = role,
            modelTier = modelTier ?: role.defaultModelTier,
            workspaceId = workspaceId,
            parentId = parentId,
            status = AgentStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )

        agentStore.save(agent)
        eventBus.emit(AgentEvent.AgentCreated(agent.id, workspaceId, parentId))

        return ToolResult.ok(json.encodeToString(agent))
    }

    /**
     * Delegate a task to a specific agent.
     *
     * Called by Routa to assign tasks to Crafter agents.
     * The task must already exist in the TaskStore.
     *
     * @param agentId The agent to delegate to.
     * @param taskId The task to assign.
     * @param callerAgentId The agent performing the delegation (for auditing).
     */
    suspend fun delegate(
        agentId: String,
        taskId: String,
        callerAgentId: String,
    ): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")
        val task = taskStore.get(taskId)
            ?: return ToolResult.fail("Task not found: $taskId")

        // Update task assignment and status
        val now = Instant.now().toString()
        val updatedTask = task.copy(
            assignedTo = agentId,
            status = TaskStatus.IN_PROGRESS,
            updatedAt = now,
        )
        taskStore.save(updatedTask)

        // Activate the agent
        agentStore.updateStatus(agentId, AgentStatus.ACTIVE)

        // Emit events
        eventBus.emit(AgentEvent.TaskDelegated(taskId, agentId, callerAgentId))
        eventBus.emit(AgentEvent.TaskStatusChanged(taskId, task.status, TaskStatus.IN_PROGRESS))
        eventBus.emit(AgentEvent.AgentStatusChanged(agentId, agent.status, AgentStatus.ACTIVE))

        return ToolResult.ok(
            """{"delegated": true, "agentId": "$agentId", "taskId": "$taskId", "agentName": "${agent.name}"}"""
        )
    }

    /**
     * Send a message to another agent.
     *
     * Used for inter-agent communication:
     * - Crafter → Routa: "I found a conflict, need guidance"
     * - Gate → Crafter: "Fix these issues: ..."
     * - Routa → Crafter: "Here's additional context"
     *
     * @param fromAgentId The sending agent.
     * @param toAgentId The receiving agent.
     * @param message The message content.
     */
    suspend fun messageAgent(
        fromAgentId: String,
        toAgentId: String,
        message: String,
    ): ToolResult {
        val fromAgent = agentStore.get(fromAgentId)
            ?: return ToolResult.fail("Sender agent not found: $fromAgentId")
        val toAgent = agentStore.get(toAgentId)
            ?: return ToolResult.fail("Recipient agent not found: $toAgentId")

        // Record the message in the recipient's conversation
        val now = Instant.now().toString()
        val msg = Message(
            id = UUID.randomUUID().toString(),
            agentId = toAgentId,
            role = MessageRole.USER,
            content = "[From ${fromAgent.name} (${fromAgent.role.displayName})]: $message",
            timestamp = now,
        )
        conversationStore.append(msg)

        // Emit event
        eventBus.emit(AgentEvent.MessageReceived(fromAgentId, toAgentId, message))

        return ToolResult.ok(
            """{"sent": true, "from": "${fromAgent.name}", "to": "${toAgent.name}"}"""
        )
    }

    /**
     * Wait for an agent to complete its work.
     *
     * Blocks until the agent's status becomes COMPLETED or ERROR,
     * or until the timeout is reached.
     *
     * @param agentId The agent to wait for.
     * @param timeoutMs Maximum wait time in milliseconds (default: 5 minutes).
     */
    suspend fun waitForAgent(
        agentId: String,
        timeoutMs: Long = 300_000,
    ): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")

        // If already completed, return immediately
        if (agent.status == AgentStatus.COMPLETED || agent.status == AgentStatus.ERROR) {
            return ToolResult.ok(
                """{"agentId": "$agentId", "status": "${agent.status.name}", "waited": false}"""
            )
        }

        // Wait for the status change event
        return try {
            withTimeout(timeoutMs) {
                val event = eventBus.events.first { event ->
                    event is AgentEvent.AgentStatusChanged &&
                        event.agentId == agentId &&
                        (event.newStatus == AgentStatus.COMPLETED || event.newStatus == AgentStatus.ERROR)
                }
                val finalStatus = (event as AgentEvent.AgentStatusChanged).newStatus
                ToolResult.ok(
                    """{"agentId": "$agentId", "status": "${finalStatus.name}", "waited": true}"""
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult.fail("Timeout waiting for agent $agentId after ${timeoutMs}ms")
        }
    }

    /**
     * Report completion to the parent agent.
     *
     * REQUIRED for all delegated agents (Crafter and Gate).
     * This is how the Routa knows a child agent finished its work.
     *
     * @param agentId The reporting agent.
     * @param report The completion report.
     */
    suspend fun reportToParent(
        agentId: String,
        report: CompletionReport,
    ): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")

        val parentId = agent.parentId
            ?: return ToolResult.fail("Agent $agentId has no parent — only delegated agents can report to parent")

        val parentAgent = agentStore.get(parentId)
            ?: return ToolResult.fail("Parent agent not found: $parentId")

        // Record the report as a message in the parent's conversation
        val now = Instant.now().toString()
        val reportContent = buildString {
            appendLine("[Completion Report from ${agent.name} (${agent.role.displayName})]")
            appendLine("Task: ${report.taskId}")
            appendLine("Summary: ${report.summary}")
            if (report.filesModified.isNotEmpty()) {
                appendLine("Files modified: ${report.filesModified.joinToString(", ")}")
            }
            if (report.verificationResults.isNotEmpty()) {
                appendLine("Verification:")
                report.verificationResults.forEach { (cmd, result) ->
                    appendLine("  $cmd → $result")
                }
            }
            appendLine("Success: ${report.success}")
        }

        conversationStore.append(
            Message(
                id = UUID.randomUUID().toString(),
                agentId = parentId,
                role = MessageRole.USER,
                content = reportContent,
                timestamp = now,
            )
        )

        // Update task status if task exists
        val task = taskStore.get(report.taskId)
        if (task != null) {
            val newStatus = when {
                agent.role == AgentRole.GATE && report.success -> TaskStatus.COMPLETED
                agent.role == AgentRole.GATE && !report.success -> TaskStatus.NEEDS_FIX
                agent.role == AgentRole.CRAFTER && report.success -> TaskStatus.REVIEW_REQUIRED
                else -> task.status
            }
            if (newStatus != task.status) {
                val updatedTask = task.copy(
                    status = newStatus,
                    updatedAt = now,
                    completionSummary = if (agent.role == AgentRole.CRAFTER) report.summary else task.completionSummary,
                    verificationReport = if (agent.role == AgentRole.GATE) report.summary else task.verificationReport,
                    verificationVerdict = if (agent.role == AgentRole.GATE) {
                        if (report.success) VerificationVerdict.APPROVED else VerificationVerdict.NOT_APPROVED
                    } else task.verificationVerdict,
                )
                taskStore.save(updatedTask)
                eventBus.emit(AgentEvent.TaskStatusChanged(report.taskId, task.status, newStatus))
            }
        }

        // Mark agent as completed
        agentStore.updateStatus(agentId, AgentStatus.COMPLETED)
        eventBus.emit(AgentEvent.AgentStatusChanged(agentId, agent.status, AgentStatus.COMPLETED))
        eventBus.emit(AgentEvent.AgentCompleted(agentId, parentId, report))

        return ToolResult.ok(
            """{"reported": true, "to": "${parentAgent.name}", "taskId": "${report.taskId}"}"""
        )
    }
}
