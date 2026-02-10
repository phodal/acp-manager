package com.phodal.routa.core.store

import com.phodal.routa.core.model.Agent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus

/**
 * Persistent storage for Agent instances.
 *
 * Implementations must be thread-safe as multiple coroutines
 * may read/write concurrently.
 */
interface AgentStore {

    /**
     * Save or update an agent.
     */
    suspend fun save(agent: Agent)

    /**
     * Get an agent by its ID.
     * @return The agent, or null if not found.
     */
    suspend fun get(agentId: String): Agent?

    /**
     * List all agents in a workspace.
     */
    suspend fun listByWorkspace(workspaceId: String): List<Agent>

    /**
     * List all child agents of a given parent.
     */
    suspend fun listByParent(parentId: String): List<Agent>

    /**
     * List agents by role in a workspace.
     */
    suspend fun listByRole(workspaceId: String, role: AgentRole): List<Agent>

    /**
     * List agents by status in a workspace.
     */
    suspend fun listByStatus(workspaceId: String, status: AgentStatus): List<Agent>

    /**
     * Delete an agent by ID.
     */
    suspend fun delete(agentId: String)

    /**
     * Update the status of an agent.
     */
    suspend fun updateStatus(agentId: String, status: AgentStatus)
}
