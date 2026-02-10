package com.phodal.routa.core.store

import com.phodal.routa.core.model.Task
import com.phodal.routa.core.model.TaskStatus

/**
 * Persistent storage for task definitions and their lifecycle.
 */
interface TaskStore {

    /**
     * Save or update a task.
     */
    suspend fun save(task: Task)

    /**
     * Get a task by its ID.
     */
    suspend fun get(taskId: String): Task?

    /**
     * List all tasks in a workspace.
     */
    suspend fun listByWorkspace(workspaceId: String): List<Task>

    /**
     * List tasks by status.
     */
    suspend fun listByStatus(workspaceId: String, status: TaskStatus): List<Task>

    /**
     * List tasks assigned to a specific agent.
     */
    suspend fun listByAssignee(agentId: String): List<Task>

    /**
     * Find tasks that are ready to execute (all dependencies completed).
     */
    suspend fun findReadyTasks(workspaceId: String): List<Task>

    /**
     * Update the status of a task.
     */
    suspend fun updateStatus(taskId: String, status: TaskStatus)

    /**
     * Delete a task.
     */
    suspend fun delete(taskId: String)
}
