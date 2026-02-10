package com.phodal.routa.core.event

import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.model.CompletionReport
import com.phodal.routa.core.model.TaskStatus
import kotlinx.serialization.Serializable

/**
 * Events emitted by the multi-agent system.
 *
 * These events drive the coordination between agents:
 * - Routa listens for [AgentCompleted] to know when to send the next wave
 * - Crafters listen for [MessageReceived] to handle fix requests
 * - Gates listen for [TaskStatusChanged] to start verification
 */
@Serializable
sealed class AgentEvent {

    /** An agent was created. */
    @Serializable
    data class AgentCreated(
        val agentId: String,
        val workspaceId: String,
        val parentId: String?,
    ) : AgentEvent()

    /** An agent's status changed. */
    @Serializable
    data class AgentStatusChanged(
        val agentId: String,
        val oldStatus: AgentStatus,
        val newStatus: AgentStatus,
    ) : AgentEvent()

    /** An agent completed its work and reported to parent. */
    @Serializable
    data class AgentCompleted(
        val agentId: String,
        val parentId: String,
        val report: CompletionReport,
    ) : AgentEvent()

    /** A message was sent from one agent to another. */
    @Serializable
    data class MessageReceived(
        val fromAgentId: String,
        val toAgentId: String,
        val message: String,
    ) : AgentEvent()

    /** A task's status changed. */
    @Serializable
    data class TaskStatusChanged(
        val taskId: String,
        val oldStatus: TaskStatus,
        val newStatus: TaskStatus,
    ) : AgentEvent()

    /** A task was delegated to an agent. */
    @Serializable
    data class TaskDelegated(
        val taskId: String,
        val agentId: String,
        val delegatedBy: String,
    ) : AgentEvent()
}
