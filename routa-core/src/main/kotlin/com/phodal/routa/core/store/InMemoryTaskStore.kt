package com.phodal.routa.core.store

import com.phodal.routa.core.model.Task
import com.phodal.routa.core.model.TaskStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [TaskStore].
 */
class InMemoryTaskStore : TaskStore {

    private val tasks = ConcurrentHashMap<String, Task>()
    private val mutex = Mutex()

    override suspend fun save(task: Task) {
        tasks[task.id] = task
    }

    override suspend fun get(taskId: String): Task? {
        return tasks[taskId]
    }

    override suspend fun listByWorkspace(workspaceId: String): List<Task> {
        return tasks.values.filter { it.workspaceId == workspaceId }
    }

    override suspend fun listByStatus(workspaceId: String, status: TaskStatus): List<Task> {
        return tasks.values.filter { it.workspaceId == workspaceId && it.status == status }
    }

    override suspend fun listByAssignee(agentId: String): List<Task> {
        return tasks.values.filter { it.assignedTo == agentId }
    }

    override suspend fun findReadyTasks(workspaceId: String): List<Task> {
        val allTasks = listByWorkspace(workspaceId)
        val completedIds = allTasks
            .filter { it.status == TaskStatus.COMPLETED }
            .map { it.id }
            .toSet()

        return allTasks.filter { task ->
            task.status == TaskStatus.PENDING &&
                task.dependencies.all { depId -> depId in completedIds }
        }
    }

    override suspend fun updateStatus(taskId: String, status: TaskStatus) {
        mutex.withLock {
            tasks[taskId]?.let { task ->
                tasks[taskId] = task.copy(status = status)
            }
        }
    }

    override suspend fun delete(taskId: String) {
        tasks.remove(taskId)
    }
}
