package com.phodal.routa.core.store

import com.phodal.routa.core.model.Agent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [AgentStore].
 *
 * Suitable for testing and single-process scenarios.
 * For multi-process persistence, use a file-based or database implementation.
 */
class InMemoryAgentStore : AgentStore {

    private val agents = ConcurrentHashMap<String, Agent>()
    private val mutex = Mutex()

    override suspend fun save(agent: Agent) {
        agents[agent.id] = agent
    }

    override suspend fun get(agentId: String): Agent? {
        return agents[agentId]
    }

    override suspend fun listByWorkspace(workspaceId: String): List<Agent> {
        return agents.values.filter { it.workspaceId == workspaceId }
    }

    override suspend fun listByParent(parentId: String): List<Agent> {
        return agents.values.filter { it.parentId == parentId }
    }

    override suspend fun listByRole(workspaceId: String, role: AgentRole): List<Agent> {
        return agents.values.filter { it.workspaceId == workspaceId && it.role == role }
    }

    override suspend fun listByStatus(workspaceId: String, status: AgentStatus): List<Agent> {
        return agents.values.filter { it.workspaceId == workspaceId && it.status == status }
    }

    override suspend fun delete(agentId: String) {
        agents.remove(agentId)
    }

    override suspend fun updateStatus(agentId: String, status: AgentStatus) {
        mutex.withLock {
            agents[agentId]?.let { agent ->
                agents[agentId] = agent.copy(status = status)
            }
        }
    }
}
