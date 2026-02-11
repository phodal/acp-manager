package com.phodal.routa.core.tool

import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.event.AgentEventSubscriptionService
import com.phodal.routa.core.event.EventBus
import com.phodal.routa.core.model.*
import com.phodal.routa.core.store.AgentStore
import com.phodal.routa.core.store.ConversationStore
import com.phodal.routa.core.store.TaskStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * The 12 agent coordination tools that enable multi-agent collaboration.
 *
 * These tools are designed to be exposed via MCP (Model Context Protocol) so that
 * LLM-powered agents can call them during their conversation turns.
 *
 * Core tools (from Issue #21):
 * - list_agents() → [listAgents]
 * - read_agent_conversation() → [readAgentConversation]
 * - create_agent() → [createAgent]
 * - delegate() → [delegate]
 * - message_agent() → [messageAgent]
 * - report_to_parent() → [reportToParent]
 *
 * Task-agent lifecycle tools:
 * - wake_or_create_task_agent() → [wakeOrCreateTaskAgent]
 * - send_message_to_task_agent() → [sendMessageToTaskAgent]
 * - get_agent_status() → [getAgentStatus]
 * - get_agent_summary() → [getAgentSummary]
 *
 * Event subscription tools:
 * - subscribe_to_events() → [subscribeToEvents]
 * - unsubscribe_from_events() → [unsubscribeFromEvents]
 */
class AgentTools(
    private val agentStore: AgentStore,
    private val conversationStore: ConversationStore,
    private val taskStore: TaskStore,
    private val eventBus: EventBus,
    private val subscriptionService: AgentEventSubscriptionService? = null,
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

    // ── Task-Agent Lifecycle Tools ──────────────────────────────────────

    /**
     * Wake an existing agent or create a new one for a task.
     *
     * Resolution strategy:
     * 1. Look up the task to find its assignedTo agent
     * 2. Check the agent's status (most recent assignment)
     * 3. If the agent is ACTIVE or PENDING: send the context message (wake)
     * 4. If no viable agent: create a new Crafter, assign it to the task, and start it
     *
     * This is the core primitive for task orchestration — when a task's
     * dependencies become ready, the coordinator calls this to ensure
     * an agent is working on it.
     *
     * @param taskId The task to wake or create an agent for.
     * @param contextMessage Message with synthesized context from completed dependencies.
     * @param callerAgentId The agent calling this tool (for auditing).
     * @param workspaceId The workspace this task belongs to.
     * @param agentName Optional custom name for a new agent.
     * @param modelTier Optional model tier for a new agent.
     */
    suspend fun wakeOrCreateTaskAgent(
        taskId: String,
        contextMessage: String,
        callerAgentId: String,
        workspaceId: String,
        agentName: String? = null,
        modelTier: ModelTier? = null,
    ): ToolResult {
        val task = taskStore.get(taskId)
            ?: return ToolResult.fail("Task not found: $taskId")

        // Try to wake an existing agent
        val assignedAgentId = task.assignedTo
        if (assignedAgentId != null) {
            val agent = agentStore.get(assignedAgentId)
            if (agent != null && (agent.status == AgentStatus.ACTIVE || agent.status == AgentStatus.PENDING)) {
                // Agent is alive — send it the context message
                val now = Instant.now().toString()
                val msg = Message(
                    id = UUID.randomUUID().toString(),
                    agentId = assignedAgentId,
                    role = MessageRole.USER,
                    content = "[Task Wake from ${callerAgentId}]: $contextMessage",
                    timestamp = now,
                )
                conversationStore.append(msg)
                eventBus.emit(AgentEvent.MessageReceived(callerAgentId, assignedAgentId, contextMessage))

                return ToolResult.ok(
                    json.encodeToString(
                        mapOf(
                            "action" to "woke_existing",
                            "agentId" to assignedAgentId,
                            "agentName" to agent.name,
                            "agentStatus" to agent.status.name,
                            "taskId" to taskId,
                        )
                    )
                )
            }
        }

        // No viable agent found — create a new one
        val now = Instant.now().toString()
        val newAgentName = agentName ?: "crafter-${task.title.take(40).replace("\\s+".toRegex(), "-").lowercase()}"
        val newAgent = Agent(
            id = UUID.randomUUID().toString(),
            name = newAgentName,
            role = AgentRole.CRAFTER,
            modelTier = modelTier ?: AgentRole.CRAFTER.defaultModelTier,
            workspaceId = workspaceId,
            parentId = callerAgentId,
            status = AgentStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            metadata = mapOf("taskId" to taskId),
        )
        agentStore.save(newAgent)

        // Assign agent to task
        val updatedTask = task.copy(
            assignedTo = newAgent.id,
            status = TaskStatus.IN_PROGRESS,
            updatedAt = now,
        )
        taskStore.save(updatedTask)

        // Send the initial context message
        conversationStore.append(
            Message(
                id = UUID.randomUUID().toString(),
                agentId = newAgent.id,
                role = MessageRole.USER,
                content = contextMessage,
                timestamp = now,
            )
        )

        // Emit events
        eventBus.emit(AgentEvent.AgentCreated(newAgent.id, workspaceId, callerAgentId))
        eventBus.emit(AgentEvent.TaskDelegated(taskId, newAgent.id, callerAgentId))
        eventBus.emit(AgentEvent.TaskStatusChanged(taskId, task.status, TaskStatus.IN_PROGRESS))

        return ToolResult.ok(
            json.encodeToString(
                mapOf(
                    "action" to "created_new",
                    "agentId" to newAgent.id,
                    "agentName" to newAgent.name,
                    "taskId" to taskId,
                    "taskTitle" to task.title,
                )
            )
        )
    }

    /**
     * Send a message to the agent currently assigned to a task.
     *
     * This is a higher-level convenience over [messageAgent] — you only
     * need the task ID, not the agent ID. The tool automatically finds
     * which agent is assigned.
     *
     * Use this when you want to ask a task agent to make corrections,
     * provide additional context, or request changes to their work.
     *
     * @param taskId The task whose assigned agent should receive the message.
     * @param message The message content.
     * @param callerAgentId The sending agent.
     */
    suspend fun sendMessageToTaskAgent(
        taskId: String,
        message: String,
        callerAgentId: String,
    ): ToolResult {
        val task = taskStore.get(taskId)
            ?: return ToolResult.fail("Task not found: $taskId")

        val assignedAgentId = task.assignedTo
            ?: return ToolResult.fail("Task '${task.title}' has no agent assigned. Use delegate or wake_or_create_task_agent first.")

        val assignedAgent = agentStore.get(assignedAgentId)
            ?: return ToolResult.fail("Assigned agent not found: $assignedAgentId")

        val callerAgent = agentStore.get(callerAgentId)

        // Record the message in the recipient's conversation
        val now = Instant.now().toString()
        val callerName = callerAgent?.name ?: callerAgentId
        val msg = Message(
            id = UUID.randomUUID().toString(),
            agentId = assignedAgentId,
            role = MessageRole.USER,
            content = "[From $callerName]: $message",
            timestamp = now,
        )
        conversationStore.append(msg)

        // Emit event
        eventBus.emit(AgentEvent.MessageReceived(callerAgentId, assignedAgentId, message))

        return ToolResult.ok(
            json.encodeToString(
                mapOf(
                    "sent" to "true",
                    "toAgentId" to assignedAgentId,
                    "toAgentName" to assignedAgent.name,
                    "taskId" to taskId,
                    "taskTitle" to task.title,
                )
            )
        )
    }

    /**
     * Get detailed status of a specific agent.
     *
     * Returns the agent's current status, message count, task assignment,
     * and timestamps. Use this to check on a delegated agent's progress
     * before deciding whether to read the full conversation.
     *
     * @param agentId The agent to check.
     */
    suspend fun getAgentStatus(agentId: String): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")

        val messageCount = conversationStore.getMessageCount(agentId)
        val tasks = taskStore.listByAssignee(agentId)

        val statusInfo = buildMap {
            put("id", agent.id)
            put("name", agent.name)
            put("role", agent.role.name)
            put("status", agent.status.name)
            put("modelTier", agent.modelTier.name)
            put("messageCount", messageCount.toString())
            put("parentId", agent.parentId ?: "none")
            put("createdAt", agent.createdAt)
            put("updatedAt", agent.updatedAt)
            if (tasks.isNotEmpty()) {
                put("assignedTaskIds", tasks.joinToString(",") { it.id })
                put("assignedTaskTitles", tasks.joinToString(",") { it.title })
            }
            agent.metadata.forEach { (k, v) -> put("meta_$k", v) }
        }

        return ToolResult.ok(json.encodeToString(statusInfo))
    }

    /**
     * Get a summary of what an agent did.
     *
     * Provides a quick overview without reading the full conversation:
     * - Agent status and basic info
     * - Last assistant response (truncated)
     * - Tool call counts by tool name
     * - Assigned tasks
     *
     * Use this for a quick overview before deciding whether to
     * read the full conversation with [readAgentConversation].
     *
     * @param agentId The agent to summarize.
     */
    suspend fun getAgentSummary(agentId: String): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")

        val messages = conversationStore.getConversation(agentId)
        val tasks = taskStore.listByAssignee(agentId)

        // Find last assistant message
        val lastAssistantMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }

        // Count tool calls
        val toolCallCounts = mutableMapOf<String, Int>()
        messages.filter { it.role == MessageRole.TOOL && it.toolName != null }
            .forEach { msg ->
                val toolName = msg.toolName!!
                toolCallCounts[toolName] = (toolCallCounts[toolName] ?: 0) + 1
            }

        // Build summary
        val summary = buildString {
            appendLine("## Agent Summary: \"${agent.name}\"")
            appendLine()
            appendLine("- **Agent ID:** ${agent.id}")
            appendLine("- **Role:** ${agent.role.displayName}")
            appendLine("- **Status:** ${agent.status.name}")
            appendLine("- **Model Tier:** ${agent.modelTier.name}")
            appendLine("- **Messages:** ${messages.size}")
            appendLine("- **Created:** ${agent.createdAt}")
            appendLine("- **Last Updated:** ${agent.updatedAt}")
            if (agent.parentId != null) {
                appendLine("- **Parent Agent:** ${agent.parentId}")
            }

            if (tasks.isNotEmpty()) {
                appendLine()
                appendLine("### Assigned Tasks")
                tasks.forEach { task ->
                    appendLine("- ${task.title} (${task.id}) — ${task.status.name}")
                }
            }

            if (toolCallCounts.isNotEmpty()) {
                appendLine()
                appendLine("### Tool Calls")
                toolCallCounts.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                    appendLine("- $name: $count calls")
                }
            }

            if (lastAssistantMessage != null) {
                appendLine()
                appendLine("### Last Response")
                val truncated = if (lastAssistantMessage.content.length > 1000) {
                    lastAssistantMessage.content.take(1000) + "..."
                } else {
                    lastAssistantMessage.content
                }
                appendLine(truncated)
            }

            appendLine()
            appendLine("_Use `read_agent_conversation(agentId=\"${agentId}\")` to see the full conversation._")
        }

        return ToolResult.ok(summary)
    }

    // ── Event Subscription Tools ────────────────────────────────────────

    /**
     * Lazy-initialized subscription service.
     *
     * If no [subscriptionService] was injected, creates one on-the-fly
     * backed by the current [eventBus].
     */
    private val effectiveSubscriptionService: AgentEventSubscriptionService by lazy {
        (subscriptionService ?: AgentEventSubscriptionService(eventBus)).also {
            it.startListening()
        }
    }

    /**
     * Subscribe an agent to workspace events.
     *
     * The agent will be notified when matching events occur. Events are batched
     * and can be retrieved via [drainPendingEvents].
     *
     * Event type patterns:
     * - `"*"` — all events
     * - `"agent:*"` — all agent events (created, status_changed, completed, message)
     * - `"task:*"` — all task events (status_changed, delegated)
     * - `"agent:created"` — when a new agent is created
     * - `"agent:completed"` — when an agent finishes and reports to parent
     * - `"agent:status_changed"` — when an agent's status changes
     * - `"agent:message"` — when an agent sends a message
     * - `"task:status_changed"` — when a task's status changes
     * - `"task:delegated"` — when a task is delegated to an agent
     *
     * @param agentId The subscribing agent's ID.
     * @param agentName The subscribing agent's name.
     * @param eventTypes Event type patterns to match.
     * @param excludeSelf Whether to exclude events caused by this agent (default: true).
     */
    suspend fun subscribeToEvents(
        agentId: String,
        agentName: String,
        eventTypes: List<String>,
        excludeSelf: Boolean = true,
    ): ToolResult {
        val agent = agentStore.get(agentId)
            ?: return ToolResult.fail("Agent not found: $agentId")

        val subscriptionId = effectiveSubscriptionService.subscribe(
            agentId = agentId,
            agentName = agentName.ifEmpty { agent.name },
            eventTypes = eventTypes,
            excludeSelf = excludeSelf,
        )

        return ToolResult.ok(
            json.encodeToString(
                mapOf(
                    "subscriptionId" to subscriptionId,
                    "agentId" to agentId,
                    "eventTypes" to eventTypes.joinToString(","),
                    "excludeSelf" to excludeSelf.toString(),
                )
            )
        )
    }

    /**
     * Unsubscribe from workspace events.
     *
     * @param subscriptionId The subscription ID returned from [subscribeToEvents].
     */
    suspend fun unsubscribeFromEvents(
        subscriptionId: String,
    ): ToolResult {
        val removed = effectiveSubscriptionService.unsubscribe(subscriptionId)
        return if (removed) {
            ToolResult.ok(
                """{"unsubscribed": true, "subscriptionId": "$subscriptionId"}"""
            )
        } else {
            ToolResult.fail("Subscription not found: $subscriptionId")
        }
    }

    /**
     * Drain pending events for an agent.
     *
     * This is NOT exposed as an MCP tool — it's called internally when an agent
     * becomes idle, to deliver batched event notifications.
     *
     * @param agentId The agent to drain events for.
     * @return List of pending events formatted as a string.
     */
    suspend fun drainPendingEvents(agentId: String): ToolResult {
        val events = effectiveSubscriptionService.drainPendingEvents(agentId)
        if (events.isEmpty()) {
            return ToolResult.ok("""{"pendingEvents": [], "count": 0}""")
        }

        val formatted = events.map { delivered ->
            mapOf(
                "eventType" to delivered.eventType,
                "timestamp" to delivered.timestamp.toString(),
                "subscriptionId" to delivered.subscriptionId,
                "details" to delivered.event.toSummary(),
            )
        }

        return ToolResult.ok(json.encodeToString(formatted))
    }
}

/**
 * Format an AgentEvent as a human-readable summary string.
 */
private fun AgentEvent.toSummary(): String = when (this) {
    is AgentEvent.AgentCreated -> "Agent $agentId created in workspace $workspaceId"
    is AgentEvent.AgentStatusChanged -> "Agent $agentId: $oldStatus → $newStatus"
    is AgentEvent.AgentCompleted -> "Agent $agentId completed (parent: $parentId)"
    is AgentEvent.MessageReceived -> "Message from $fromAgentId to $toAgentId"
    is AgentEvent.TaskStatusChanged -> "Task $taskId: $oldStatus → $newStatus"
    is AgentEvent.TaskDelegated -> "Task $taskId delegated to agent $agentId by $delegatedBy"
}
