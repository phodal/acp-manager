package com.github.phodal.acpmanager.dispatcher

import com.github.phodal.acpmanager.dispatcher.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore

/**
 * Default implementation of [AgentDispatcher].
 *
 * Orchestrates:
 * 1. Plan generation via [PlanGenerator]
 * 2. Task execution via [AgentExecutor] with concurrency control
 * 3. State and log management
 *
 * This implementation is UI-agnostic and can be used from both
 * IDEA plugin and terminal-based test harness.
 */
class DefaultAgentDispatcher(
    private val planGenerator: PlanGenerator,
    private val agentExecutor: AgentExecutor,
    private val scope: CoroutineScope,
) : AgentDispatcher {

    private val _state = MutableStateFlow(DispatcherState())
    override val state: StateFlow<DispatcherState> = _state.asStateFlow()

    private val _logStream = MutableSharedFlow<AgentLogEntry>(
        replay = 100,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val logStream: Flow<AgentLogEntry> = _logStream.asSharedFlow()

    private var executionJob: Job? = null
    private val taskJobs = mutableMapOf<String, Job>()
    /** Stores the output/result of each completed task for context passing. */
    private val taskResults = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun startPlanning(userInput: String) {
        val masterKey = _state.value.masterAgentKey
            ?: throw IllegalStateException("No master agent configured")

        updateStatus(DispatcherStatus.PLANNING)
        emitLog(LogLevel.INF, "Master", message = "Starting plan generation...")

        try {
            val plan = planGenerator.generatePlan(
                masterAgentKey = masterKey,
                userInput = userInput,
                availableAgents = _state.value.agents,
            )

            _state.update { it.copy(plan = plan, status = DispatcherStatus.PLANNED) }
            emitLog(LogLevel.INF, "Master", message = "Plan generated with ${plan.tasks.size} tasks, " +
                    "strategy: ${plan.strategy}, max parallelism: ${plan.maxParallelism}")

            if (plan.thinking.isNotBlank()) {
                emitLog(LogLevel.INF, "Master", message = plan.thinking)
            }
        } catch (e: Exception) {
            updateStatus(DispatcherStatus.FAILED)
            emitLog(LogLevel.ERR, "Master", message = "Planning failed: ${e.message}")
            _state.update { it.copy(error = e.message) }
            throw e
        }
    }

    override fun updateTaskAgent(taskId: String, agentKey: String) {
        _state.update { current ->
            val updatedPlan = current.plan?.let { plan ->
                plan.copy(tasks = plan.tasks.map { task ->
                    if (task.id == taskId) task.copy(assignedAgent = agentKey)
                    else task
                })
            }
            current.copy(plan = updatedPlan)
        }
    }

    override fun updateMaxParallelism(maxParallelism: Int) {
        _state.update { current ->
            current.copy(plan = current.plan?.copy(maxParallelism = maxParallelism.coerceIn(1, 5)))
        }
    }

    override suspend fun executePlan() {
        val plan = _state.value.plan ?: throw IllegalStateException("No plan to execute")

        // For SINGLE_AGENT strategy, override all task agents to the master agent
        val effectivePlan = if (plan.strategy == ExecutionStrategy.SINGLE_AGENT) {
            val masterKey = _state.value.masterAgentKey
            if (masterKey != null) {
                emitLog(LogLevel.INF, "Master", message = "Single-agent mode: all tasks will use '$masterKey'")
                val updatedTasks = plan.tasks.map { it.copy(assignedAgent = masterKey) }
                val updated = plan.copy(tasks = updatedTasks, maxParallelism = 1)
                _state.update { it.copy(plan = updated) }
                updated
            } else plan
        } else plan

        taskResults.clear()

        updateStatus(DispatcherStatus.RUNNING)
        emitLog(LogLevel.INF, "Master", message = "Starting plan execution...")

        executionJob = scope.launch {
            try {
                executeTasksWithDependencies(effectivePlan)
                // Check if any tasks failed before marking as COMPLETED
                val hasFailed = effectivePlan.tasks.any { it.status == AgentTaskStatus.FAILED }
                if (hasFailed) {
                    updateStatus(DispatcherStatus.FAILED)
                    emitLog(LogLevel.ERR, "Master", message = "Some tasks failed.")
                } else {
                    updateStatus(DispatcherStatus.COMPLETED)
                    emitLog(LogLevel.INF, "Master", message = "All tasks completed successfully.")
                }
            } catch (e: CancellationException) {
                updateStatus(DispatcherStatus.PAUSED)
                emitLog(LogLevel.WRN, "Master", message = "Execution cancelled.")
                throw e
            } catch (e: Exception) {
                updateStatus(DispatcherStatus.FAILED)
                emitLog(LogLevel.ERR, "Master", message = "Execution failed: ${e.message}")
            }
        }

        executionJob?.join()
    }

    /**
     * Execute tasks respecting dependencies and parallelism constraints.
     * Passes completed task results as context to dependent tasks.
     */
    private suspend fun executeTasksWithDependencies(plan: DispatchPlan) {
        val maxParallelism = plan.maxParallelism.coerceAtLeast(1)
        val semaphore = Semaphore(maxParallelism)

        val completedTasks = mutableSetOf<String>()
        val pendingTasks = plan.tasks.toMutableList()
        val allTasks = plan.tasks.associateBy { it.id }

        while (pendingTasks.isNotEmpty()) {
            // Find tasks whose dependencies are all satisfied
            val readyTasks = pendingTasks.filter { task ->
                task.dependencies.all { dep -> dep in completedTasks }
            }

            if (readyTasks.isEmpty() && pendingTasks.isNotEmpty()) {
                // Deadlock: remaining tasks have unsatisfied deps
                emitLog(LogLevel.ERR, "Master", message = "Deadlock detected: ${pendingTasks.size} tasks blocked")
                for (task in pendingTasks) {
                    updateTaskStatus(task.id, AgentTaskStatus.BLOCKED)
                }
                throw IllegalStateException("Deadlock: tasks have circular or unsatisfied dependencies")
            }

            // Launch ready tasks respecting semaphore
            coroutineScope {
                val launchedJobs = readyTasks.map { task ->
                    pendingTasks.remove(task)

                    // Build context from completed dependency tasks
                    val dependencyContext = buildDependencyContext(task, allTasks)

                    launch {
                        semaphore.acquire()
                        try {
                            executeTask(task, dependencyContext)
                            completedTasks.add(task.id)
                        } finally {
                            semaphore.release()
                        }
                    }
                }
                launchedJobs.forEach { it.join() }
            }
        }
    }

    /**
     * Build context string from completed dependency tasks.
     * Returns null if the task has no dependencies or no results are available.
     */
    private fun buildDependencyContext(task: AgentTask, allTasks: Map<String, AgentTask>): String? {
        if (task.dependencies.isEmpty()) return null

        val contextParts = task.dependencies.mapNotNull { depId ->
            val depTask = allTasks[depId]
            val result = taskResults[depId]
            if (depTask != null && result != null) {
                "[${depTask.title}] (${depId}):\n$result"
            } else null
        }

        if (contextParts.isEmpty()) return null

        return "=== Context from completed tasks ===\n" +
                contextParts.joinToString("\n---\n") +
                "\n=== End context ==="
    }

    /**
     * Execute a single task, optionally with context from dependency tasks.
     */
    private suspend fun executeTask(task: AgentTask, dependencyContext: String? = null) {
        val agentKey = task.assignedAgent
        if (agentKey == null) {
            emitLog(LogLevel.ERR, "Master", task.id, "Task '${task.title}' has no assigned agent")
            updateTaskStatus(task.id, AgentTaskStatus.FAILED)
            return
        }

        updateTaskStatus(task.id, AgentTaskStatus.RUNNING)
        emitLog(LogLevel.INF, agentKey, task.id, "Starting: ${task.title}")

        // Build the effective prompt: original description + dependency context
        val effectivePrompt = if (dependencyContext != null) {
            emitLog(LogLevel.INF, agentKey, task.id, "Injecting context from ${task.dependencies.size} dependency task(s)")
            "${task.description}\n\n$dependencyContext"
        } else {
            task.description
        }

        try {
            val resultBuilder = StringBuilder()
            val logFlow = agentExecutor.execute(agentKey, effectivePrompt, task.id)
            logFlow.collect { logEntry ->
                _logStream.emit(logEntry)
                _state.update { it.copy(logs = it.logs + logEntry) }
                // Collect task output for context passing
                if (logEntry.level == LogLevel.INF && logEntry.taskId == task.id) {
                    resultBuilder.appendLine(logEntry.message)
                }
            }

            // Store the result for downstream dependent tasks
            val result = resultBuilder.toString().trim()
            if (result.isNotBlank()) {
                taskResults[task.id] = result
                updateTaskResult(task.id, result)
            }

            updateTaskStatus(task.id, AgentTaskStatus.DONE)
            emitLog(LogLevel.INF, agentKey, task.id, "Completed: ${task.title}")
        } catch (e: CancellationException) {
            updateTaskStatus(task.id, AgentTaskStatus.BLOCKED)
            throw e
        } catch (e: Exception) {
            updateTaskStatus(task.id, AgentTaskStatus.FAILED)
            emitLog(LogLevel.ERR, agentKey, task.id, "Failed: ${task.title} — ${e.message}")
        }
    }

    override suspend fun cancelAll() {
        emitLog(LogLevel.WRN, "Master", message = "Cancelling all tasks...")
        executionJob?.cancel()
        taskJobs.values.forEach { it.cancel() }
        taskJobs.clear()

        _state.update { current ->
            val updatedPlan = current.plan?.let { plan ->
                plan.copy(tasks = plan.tasks.map { task ->
                    if (task.status == AgentTaskStatus.RUNNING || task.status == AgentTaskStatus.ACTIVE) {
                        task.copy(status = AgentTaskStatus.BLOCKED)
                    } else task
                })
            }
            current.copy(plan = updatedPlan, status = DispatcherStatus.PAUSED)
        }
    }

    override fun setMasterAgent(agentKey: String) {
        _state.update { it.copy(masterAgentKey = agentKey) }
    }

    override fun setAgentRoles(roles: List<AgentRole>) {
        _state.update { it.copy(agents = roles, totalAgentCount = roles.size) }
    }

    override fun reset() {
        executionJob?.cancel()
        taskJobs.values.forEach { it.cancel() }
        taskJobs.clear()
        taskResults.clear()
        _state.value = DispatcherState()
    }

    // --- Helpers ---

    private fun updateStatus(status: DispatcherStatus) {
        _state.update { it.copy(status = status) }
    }

    private fun updateTaskStatus(taskId: String, status: AgentTaskStatus) {
        _state.update { current ->
            val updatedPlan = current.plan?.let { plan ->
                plan.copy(tasks = plan.tasks.map { task ->
                    if (task.id == taskId) task.copy(status = status) else task
                })
            }
            current.copy(plan = updatedPlan)
        }
    }

    private fun updateTaskResult(taskId: String, result: String) {
        _state.update { current ->
            val updatedPlan = current.plan?.let { plan ->
                plan.copy(tasks = plan.tasks.map { task ->
                    if (task.id == taskId) task.copy(result = result.take(2000)) else task
                })
            }
            current.copy(plan = updatedPlan)
        }
    }

    private suspend fun emitLog(level: LogLevel, source: String, taskId: String? = null, message: String) {
        val entry = AgentLogEntry(
            level = level,
            source = source,
            taskId = taskId,
            message = message,
        )
        _logStream.emit(entry)
        _state.update { it.copy(logs = it.logs + entry) }
    }
}
