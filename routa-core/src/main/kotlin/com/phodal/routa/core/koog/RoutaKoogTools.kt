package com.phodal.routa.core.koog

import ai.koog.agents.core.tools.SimpleTool
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.CompletionReport
import com.phodal.routa.core.model.ModelTier
import com.phodal.routa.core.tool.AgentTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Koog-compatible [SimpleTool] wrappers for the 12 Routa coordination tools.
 *
 * These tools can be registered in a Koog [ai.koog.agents.core.tools.ToolRegistry]
 * and used by Koog-powered agents to coordinate multi-agent workflows.
 *
 * Usage:
 * ```kotlin
 * val toolRegistry = RoutaToolRegistry.create(agentTools, workspaceId)
 * ```
 */

// ── list_agents ─────────────────────────────────────────────────────────

@Serializable
data class ListAgentsArgs(
    val workspaceId: String,
)

class ListAgentsTool(
    private val agentTools: AgentTools,
    private val defaultWorkspaceId: String,
) : SimpleTool<ListAgentsArgs>(
    argsSerializer = ListAgentsArgs.serializer(),
    name = "list_agents",
    description = "List all agents in the workspace. Shows each agent's ID, name, role (ROUTA/CRAFTER/GATE), " +
        "status, and parent. Use this to discover sibling agents and avoid file conflicts.",
) {
    override suspend fun execute(args: ListAgentsArgs): String {
        val result = agentTools.listAgents(args.workspaceId.ifEmpty { defaultWorkspaceId })
        return result.data
    }
}

// ── read_agent_conversation ─────────────────────────────────────────────

@Serializable
data class ReadAgentConversationArgs(
    val agentId: String,
    val lastN: Int? = null,
    val startTurn: Int? = null,
    val endTurn: Int? = null,
    val includeToolCalls: Boolean = true,
)

class ReadAgentConversationTool(
    private val agentTools: AgentTools,
) : SimpleTool<ReadAgentConversationArgs>(
    argsSerializer = ReadAgentConversationArgs.serializer(),
    name = "read_agent_conversation",
    description = "Read another agent's conversation history. Use this to review what a delegated agent did, " +
        "see their tool calls, and understand their progress. Supports reading full history, " +
        "last N messages, or a specific turn range.",
) {
    override suspend fun execute(args: ReadAgentConversationArgs): String {
        val result = agentTools.readAgentConversation(
            agentId = args.agentId,
            lastN = args.lastN,
            startTurn = args.startTurn,
            endTurn = args.endTurn,
            includeToolCalls = args.includeToolCalls,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── create_agent ────────────────────────────────────────────────────────

@Serializable
data class CreateAgentArgs(
    val name: String,
    val role: String,
    val workspaceId: String = "",
    val parentId: String? = null,
    val modelTier: String? = null,
)

class CreateAgentTool(
    private val agentTools: AgentTools,
    private val defaultWorkspaceId: String,
) : SimpleTool<CreateAgentArgs>(
    argsSerializer = CreateAgentArgs.serializer(),
    name = "create_agent",
    description = "Create a new agent with the specified role. " +
        "Roles: ROUTA (coordinator), CRAFTER (implementor), GATE (verifier). " +
        "Model tiers: SMART (for planning/verification), FAST (for implementation).",
) {
    override suspend fun execute(args: CreateAgentArgs): String {
        val role = try {
            AgentRole.valueOf(args.role.uppercase())
        } catch (e: IllegalArgumentException) {
            return "Error: Invalid role '${args.role}'. Must be one of: ROUTA, CRAFTER, GATE"
        }

        val modelTier = args.modelTier?.let {
            try {
                ModelTier.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                return "Error: Invalid modelTier '${args.modelTier}'. Must be SMART or FAST"
            }
        }

        val result = agentTools.createAgent(
            name = args.name,
            role = role,
            workspaceId = args.workspaceId.ifEmpty { defaultWorkspaceId },
            parentId = args.parentId,
            modelTier = modelTier,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── delegate ────────────────────────────────────────────────────────────

@Serializable
data class DelegateTaskArgs(
    val agentId: String,
    val taskId: String,
    val callerAgentId: String,
)

class DelegateTaskTool(
    private val agentTools: AgentTools,
) : SimpleTool<DelegateTaskArgs>(
    argsSerializer = DelegateTaskArgs.serializer(),
    name = "delegate_task",
    description = "Delegate a task to a specific agent. The task must already exist. " +
        "This assigns the task to the agent and activates it.",
) {
    override suspend fun execute(args: DelegateTaskArgs): String {
        val result = agentTools.delegate(
            agentId = args.agentId,
            taskId = args.taskId,
            callerAgentId = args.callerAgentId,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── message_agent ───────────────────────────────────────────────────────

@Serializable
data class MessageAgentArgs(
    val fromAgentId: String,
    val toAgentId: String,
    val message: String,
)

class MessageAgentTool(
    private val agentTools: AgentTools,
) : SimpleTool<MessageAgentArgs>(
    argsSerializer = MessageAgentArgs.serializer(),
    name = "send_message_to_agent",
    description = "Send a message to another agent. Use for inter-agent communication: " +
        "conflict reports, fix requests, additional context, etc.",
) {
    override suspend fun execute(args: MessageAgentArgs): String {
        val result = agentTools.messageAgent(
            fromAgentId = args.fromAgentId,
            toAgentId = args.toAgentId,
            message = args.message,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── report_to_parent ────────────────────────────────────────────────────

@Serializable
data class ReportToParentArgs(
    val agentId: String,
    val taskId: String,
    val summary: String,
    val filesModified: List<String> = emptyList(),
    val success: Boolean = true,
)

class ReportToParentTool(
    private val agentTools: AgentTools,
) : SimpleTool<ReportToParentArgs>(
    argsSerializer = ReportToParentArgs.serializer(),
    name = "report_to_parent",
    description = "Send a completion report to the parent agent. " +
        "REQUIRED for all delegated agents (Crafter and Gate). " +
        "Include: what you did, verification results, any risks/follow-ups.",
) {
    override suspend fun execute(args: ReportToParentArgs): String {
        val report = CompletionReport(
            agentId = args.agentId,
            taskId = args.taskId,
            summary = args.summary,
            filesModified = args.filesModified,
            success = args.success,
        )
        val result = agentTools.reportToParent(args.agentId, report)
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── wake_or_create_task_agent ──────────────────────────────────────────

@Serializable
data class WakeOrCreateTaskAgentArgs(
    val taskId: String,
    val contextMessage: String,
    val callerAgentId: String,
    val agentName: String? = null,
    val modelTier: String? = null,
)

class WakeOrCreateTaskAgentTool(
    private val agentTools: AgentTools,
    private val defaultWorkspaceId: String,
) : SimpleTool<WakeOrCreateTaskAgentArgs>(
    argsSerializer = WakeOrCreateTaskAgentArgs.serializer(),
    name = "wake_or_create_task_agent",
    description = "Wake an existing agent or create a new one for a task. " +
        "Checks if the task has an active/pending agent and wakes it with the context message. " +
        "If no viable agent exists, creates a new Crafter agent and assigns it to the task. " +
        "Use this when task dependencies become ready and you need to ensure an agent is working on it.",
) {
    override suspend fun execute(args: WakeOrCreateTaskAgentArgs): String {
        val modelTier = args.modelTier?.let {
            try {
                ModelTier.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                return "Error: Invalid modelTier '${args.modelTier}'. Must be SMART or FAST"
            }
        }

        val result = agentTools.wakeOrCreateTaskAgent(
            taskId = args.taskId,
            contextMessage = args.contextMessage,
            callerAgentId = args.callerAgentId,
            workspaceId = defaultWorkspaceId,
            agentName = args.agentName,
            modelTier = modelTier,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── send_message_to_task_agent ─────────────────────────────────────────

@Serializable
data class SendMessageToTaskAgentArgs(
    val taskId: String,
    val message: String,
    val callerAgentId: String,
)

class SendMessageToTaskAgentTool(
    private val agentTools: AgentTools,
) : SimpleTool<SendMessageToTaskAgentArgs>(
    argsSerializer = SendMessageToTaskAgentArgs.serializer(),
    name = "send_message_to_task_agent",
    description = "Send a message to the agent working on a specific task. " +
        "More convenient than send_message_to_agent because you only need the task ID, " +
        "not the agent ID. Use this to ask a task agent to make corrections, " +
        "provide additional context, or request changes.",
) {
    override suspend fun execute(args: SendMessageToTaskAgentArgs): String {
        val result = agentTools.sendMessageToTaskAgent(
            taskId = args.taskId,
            message = args.message,
            callerAgentId = args.callerAgentId,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── get_agent_status ───────────────────────────────────────────────────

@Serializable
data class GetAgentStatusArgs(
    val agentId: String,
)

class GetAgentStatusTool(
    private val agentTools: AgentTools,
) : SimpleTool<GetAgentStatusArgs>(
    argsSerializer = GetAgentStatusArgs.serializer(),
    name = "get_agent_status",
    description = "Get detailed status of a specific agent including its current status, " +
        "role, message count, assigned tasks, and timestamps. " +
        "Use this to check on a delegated agent's progress.",
) {
    override suspend fun execute(args: GetAgentStatusArgs): String {
        val result = agentTools.getAgentStatus(args.agentId)
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── get_agent_summary ──────────────────────────────────────────────────

@Serializable
data class GetAgentSummaryArgs(
    val agentId: String,
)

class GetAgentSummaryTool(
    private val agentTools: AgentTools,
) : SimpleTool<GetAgentSummaryArgs>(
    argsSerializer = GetAgentSummaryArgs.serializer(),
    name = "get_agent_summary",
    description = "Get a summary of what an agent did. Returns agent status, " +
        "last response, tool call counts, and assigned tasks. " +
        "Use this for a quick overview before reading the full conversation.",
) {
    override suspend fun execute(args: GetAgentSummaryArgs): String {
        val result = agentTools.getAgentSummary(args.agentId)
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── subscribe_to_events ────────────────────────────────────────────────

@Serializable
data class SubscribeToEventsArgs(
    val agentId: String,
    val agentName: String = "",
    val eventTypes: List<String>,
    val excludeSelf: Boolean = true,
)

class SubscribeToEventsTool(
    private val agentTools: AgentTools,
) : SimpleTool<SubscribeToEventsArgs>(
    argsSerializer = SubscribeToEventsArgs.serializer(),
    name = "subscribe_to_events",
    description = "Subscribe to workspace events. You will be notified when matching events occur. " +
        "Event types: \"agent:*\" (all agent events), \"agent:created\", \"agent:completed\", " +
        "\"agent:status_changed\", \"agent:message\", \"task:*\" (all task events), " +
        "\"task:status_changed\", \"task:delegated\", \"*\" (all events). " +
        "Use this to monitor other agents, task completions, etc.",
) {
    override suspend fun execute(args: SubscribeToEventsArgs): String {
        val result = agentTools.subscribeToEvents(
            agentId = args.agentId,
            agentName = args.agentName,
            eventTypes = args.eventTypes,
            excludeSelf = args.excludeSelf,
        )
        return if (result.success) result.data else "Error: ${result.error}"
    }
}

// ── unsubscribe_from_events ────────────────────────────────────────────

@Serializable
data class UnsubscribeFromEventsArgs(
    val subscriptionId: String,
)

class UnsubscribeFromEventsTool(
    private val agentTools: AgentTools,
) : SimpleTool<UnsubscribeFromEventsArgs>(
    argsSerializer = UnsubscribeFromEventsArgs.serializer(),
    name = "unsubscribe_from_events",
    description = "Unsubscribe from workspace events. " +
        "Use the subscription ID returned from subscribe_to_events.",
) {
    override suspend fun execute(args: UnsubscribeFromEventsArgs): String {
        val result = agentTools.unsubscribeFromEvents(args.subscriptionId)
        return if (result.success) result.data else "Error: ${result.error}"
    }
}
