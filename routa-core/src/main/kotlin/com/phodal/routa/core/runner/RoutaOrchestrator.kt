package com.phodal.routa.core.runner

import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.model.*
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.StreamChunk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * The multi-agent orchestration loop.
 *
 * Implements the full ROUTA → CRAFTER → GATE workflow:
 *
 * ```
 * User Request
 *   → ROUTA plans (@@@task blocks)
 *     → Wave of CRAFTER agents execute tasks (sequential by default)
 *       → Each CRAFTER reports completion
 *         → GATE verifies all work
 *           → APPROVED: done
 *           → NOT APPROVED: fix tasks → CRAFTER again
 * ```
 *
 * Supports both the legacy [AgentRunner] interface and the new [AgentProvider]
 * interface. By default, Crafters execute sequentially. Set [parallelCrafters] = true
 * to enable parallel execution within a wave. Streaming chunks are forwarded
 * via [onStreamChunk].
 *
 * **Tool calling strategy:**
 * - If the LLM supports function calling (via Koog), tools like `report_to_parent`
 *   are called automatically and update the stores.
 * - If the LLM only produces text, the orchestrator parses the output and
 *   calls the appropriate tools on behalf of the agent.
 *
 * Usage:
 * ```kotlin
 * // Legacy (sequential)
 * val orchestrator = RoutaOrchestrator(routa, agentRunner, "my-workspace")
 *
 * // New (parallel + streaming)
 * val orchestrator = RoutaOrchestrator(routa, agentProvider, "my-workspace",
 *     onStreamChunk = { agentId, chunk -> println("[$agentId] $chunk") })
 *
 * val result = orchestrator.execute("Add user authentication to the API")
 * ```
 */
class RoutaOrchestrator(
    private val routa: RoutaSystem,
    private val runner: AgentRunner,
    private val workspaceId: String,
    private val maxWaves: Int = 3,
    /**
     * Whether to execute CRAFTERs in a wave in parallel.
     * When false (default), CRAFTERs execute sequentially one at a time.
     * When true, all CRAFTERs in a wave execute concurrently via coroutineScope + async.
     */
    private val parallelCrafters: Boolean = false,
    private val onPhaseChange: (suspend (OrchestratorPhase) -> Unit)? = null,
    private val onStreamChunk: ((agentId: String, chunk: StreamChunk) -> Unit)? = null,
) {

    // If the runner is also an AgentProvider, use its extended capabilities
    private val provider: AgentProvider? = runner as? AgentProvider

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Execute the full multi-agent orchestration flow.
     *
     * @param userRequest The user's requirement/task description.
     * @return The orchestration result.
     */
    suspend fun execute(userRequest: String): OrchestratorResult {
        emitPhase(OrchestratorPhase.Initializing)

        // ── Phase 1: ROUTA plans ────────────────────────────────────────
        emitPhase(OrchestratorPhase.Planning)

        val routaAgentId = routa.coordinator.initialize(workspaceId)

        val planPrompt = buildString {
            appendLine("## User Request")
            appendLine()
            appendLine(userRequest)
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("Please analyze this request and break it down into @@@task blocks.")
            appendLine("Each task should have: Title, Objective, Scope, Definition of Done, and Verification commands.")
            appendLine("Use the @@@task ... @@@ format as specified in your instructions.")
        }

        // Use streaming for ROUTA planning if provider supports it
        val planOutput = if (provider != null) {
            provider.runStreaming(AgentRole.ROUTA, routaAgentId, planPrompt) { chunk ->
                onStreamChunk?.invoke(routaAgentId, chunk)
            }
        } else {
            runner.run(AgentRole.ROUTA, routaAgentId, planPrompt)
        }

        emitPhase(OrchestratorPhase.PlanReady(planOutput))

        // ── Phase 2: Parse and register tasks ───────────────────────────

        val taskIds = routa.coordinator.registerTasks(planOutput)
        if (taskIds.isEmpty()) {
            return OrchestratorResult.NoTasks(planOutput)
        }

        emitPhase(OrchestratorPhase.TasksRegistered(taskIds.size))

        // ── Phase 3: Execute waves ──────────────────────────────────────

        for (wave in 1..maxWaves) {
            emitPhase(OrchestratorPhase.WaveStarting(wave))

            // Execute all ready tasks with CRAFTER agents
            val delegations = routa.coordinator.executeNextWave()
            if (delegations.isEmpty()) {
                // No more tasks to execute, check if we're done
                val phase = routa.coordinator.coordinationState.value.phase
                if (phase == CoordinationPhase.COMPLETED) {
                    break
                }
                continue
            }

            // Run CRAFTERs — parallel or sequential based on configuration
            if (parallelCrafters && provider != null && delegations.size > 1) {
                // ── Parallel execution via coroutineScope + async ──
                coroutineScope {
                    val jobs = delegations.map { (crafterId, taskId) ->
                        async {
                            runSingleCrafter(crafterId, taskId)
                        }
                    }
                    jobs.awaitAll()
                }
            } else {
                // ── Sequential execution (default) ──
                for ((crafterId, taskId) in delegations) {
                    runSingleCrafter(crafterId, taskId)
                }
            }

            // ── Phase 4: GATE verifies ──────────────────────────────────

            emitPhase(OrchestratorPhase.VerificationStarting(wave))

            val gateAgentId = routa.coordinator.startVerification()
            if (gateAgentId == null) {
                // No tasks need verification → we're done
                break
            }

            val gateContext = buildGateContext(gateAgentId)
            // Include ALL review task IDs so the Gate can report on each
            val reviewTasks = routa.context.taskStore.listByStatus(workspaceId, TaskStatus.REVIEW_REQUIRED)
            val taskIdsList = reviewTasks.joinToString(", ") { it.id }
            val gateContextWithIdentity = injectAgentIdentity(
                gateContext, gateAgentId, taskIdsList,
            )
            val gateOutput = if (provider != null) {
                provider.runStreaming(AgentRole.GATE, gateAgentId, gateContextWithIdentity) { chunk ->
                    onStreamChunk?.invoke(gateAgentId, chunk)
                }
            } else {
                runner.run(AgentRole.GATE, gateAgentId, gateContextWithIdentity)
            }

            // Ensure the GATE's verdict is reported
            ensureGateReport(gateAgentId, gateOutput)

            // Clean up provider resources for this gate agent
            provider?.cleanup(gateAgentId)

            emitPhase(OrchestratorPhase.VerificationCompleted(gateAgentId, gateOutput))

            // ── Phase 5: Check verdict (store-based, not event-based) ───
            // We check task statuses directly rather than relying on async events
            // because the event handler runs in a separate coroutine.

            val allTasks = routa.context.taskStore.listByWorkspace(workspaceId)
            val needsFixTasks = allTasks.filter { it.status == TaskStatus.NEEDS_FIX }
            val completedTasks = allTasks.filter { it.status == TaskStatus.COMPLETED }

            when {
                allTasks.isNotEmpty() && allTasks.all { it.status == TaskStatus.COMPLETED } -> {
                    emitPhase(OrchestratorPhase.Completed)
                    return buildSuccessResult()
                }
                needsFixTasks.isNotEmpty() -> {
                    emitPhase(OrchestratorPhase.NeedsFix(wave))
                    // Reset NEEDS_FIX tasks to PENDING for next wave
                    resetNeedsFixTasks()
                    continue
                }
                else -> {
                    // Might have more tasks to process
                    continue
                }
            }
        }

        // Final check (store-based)
        val finalTasks = routa.context.taskStore.listByWorkspace(workspaceId)
        return if (finalTasks.isNotEmpty() && finalTasks.all { it.status == TaskStatus.COMPLETED }) {
            emitPhase(OrchestratorPhase.Completed)
            buildSuccessResult()
        } else {
            emitPhase(OrchestratorPhase.MaxWavesReached(maxWaves))
            OrchestratorResult.MaxWavesReached(maxWaves, routa.coordinator.getTaskSummary())
        }
    }

    // ── Single Crafter execution ────────────────────────────────────────

    /**
     * Run a single CRAFTER agent with streaming (if available) and report handling.
     */
    private suspend fun runSingleCrafter(crafterId: String, taskId: String) {
        emitPhase(OrchestratorPhase.CrafterRunning(crafterId, taskId))

        val taskContext = routa.coordinator.buildAgentContext(crafterId)
            ?: return // No task assigned
        val context = injectAgentIdentity(taskContext, crafterId, taskId)

        val crafterOutput = if (provider != null) {
            provider.runStreaming(AgentRole.CRAFTER, crafterId, context) { chunk ->
                onStreamChunk?.invoke(crafterId, chunk)
            }
        } else {
            runner.run(AgentRole.CRAFTER, crafterId, context)
        }

        // Ensure the CRAFTER's work is reported
        // (If Koog tool calling worked, report_to_parent was already called.
        //  If not, we do it here based on the text output.)
        ensureCrafterReport(crafterId, taskId, crafterOutput)

        // Clean up provider resources for this agent
        provider?.cleanup(crafterId)

        emitPhase(OrchestratorPhase.CrafterCompleted(crafterId, taskId))
    }

    // ── Identity injection ────────────────────────────────────────────

    /**
     * Inject the agent's identity (agentId, taskId) into the prompt so the LLM
     * knows what values to pass when calling tools like `report_to_parent`.
     */
    private fun injectAgentIdentity(prompt: String, agentId: String, taskId: String): String {
        return buildString {
            appendLine("--- Agent Identity ---")
            appendLine("agent_id: $agentId")
            appendLine("task_id: $taskId")
            appendLine("----------------------")
            appendLine()
            append(prompt)
        }
    }

    // ── Ensure reports are filed ────────────────────────────────────────

    /**
     * If the CRAFTER didn't call report_to_parent via tool call,
     * do it on their behalf based on the text output.
     */
    private suspend fun ensureCrafterReport(crafterId: String, taskId: String, output: String) {
        val agent = routa.context.agentStore.get(crafterId) ?: return

        // If agent is already COMPLETED, Koog tool calling handled it
        if (agent.status == AgentStatus.COMPLETED) return

        // Agent didn't call report_to_parent — do it for them
        val report = CompletionReport(
            agentId = crafterId,
            taskId = taskId,
            summary = extractSummary(output),
            filesModified = extractFilesModified(output),
            success = !output.contains("FAILED", ignoreCase = true) &&
                !output.contains("ERROR", ignoreCase = true),
        )

        routa.tools.reportToParent(crafterId, report)
    }

    /**
     * If the GATE didn't call report_to_parent via tool call,
     * parse the verdict from text and do it for them.
     */
    private suspend fun ensureGateReport(gateAgentId: String, output: String) {
        val agent = routa.context.agentStore.get(gateAgentId) ?: return

        // If agent is already COMPLETED, Koog tool calling handled it
        if (agent.status == AgentStatus.COMPLETED) return

        // Find all tasks being verified
        val reviewTasks = routa.context.taskStore.listByStatus(workspaceId, TaskStatus.REVIEW_REQUIRED)

        for (task in reviewTasks) {
            // Compute per-task verdict by looking for task-specific context in the output
            val taskSection = findTaskSection(output, task)
            val approvedForTask = taskSection.contains("APPROVED", ignoreCase = true) &&
                !taskSection.contains("NOT APPROVED", ignoreCase = true) &&
                !taskSection.contains("NOT_APPROVED", ignoreCase = true)

            val report = CompletionReport(
                agentId = gateAgentId,
                taskId = task.id,
                summary = extractSummary(taskSection.ifEmpty { output }),
                success = approvedForTask,
            )
            routa.tools.reportToParent(gateAgentId, report)
        }
    }

    /**
     * Find the section of the GATE output relevant to a specific task.
     * Falls back to the full output if no task-specific section is found.
     */
    private fun findTaskSection(output: String, task: Task): String {
        // Try to find a section mentioning the task ID or title
        val lines = output.lines()
        val taskIdentifiers = listOfNotNull(task.id, task.title).filter { it.isNotBlank() }

        for (identifier in taskIdentifiers) {
            val startIdx = lines.indexOfFirst { it.contains(identifier, ignoreCase = true) }
            if (startIdx >= 0) {
                // Take lines from the task mention to the next task section or end
                val sectionLines = mutableListOf<String>()
                for (i in startIdx until lines.size) {
                    val line = lines[i]
                    // Stop at the next task section header (but include the first match)
                    if (i > startIdx && taskIdentifiers.none { line.contains(it, ignoreCase = true) } &&
                        (line.startsWith("## ") || line.startsWith("---"))) {
                        break
                    }
                    sectionLines.add(line)
                }
                return sectionLines.joinToString("\n")
            }
        }

        // Fallback: return the full output
        return output
    }

    // ── Context building ────────────────────────────────────────────────

    /**
     * Build the verification context for the GATE agent.
     * Includes all completed tasks, their CRAFTER reports, and acceptance criteria.
     */
    private suspend fun buildGateContext(gateAgentId: String): String {
        val roleContext = routa.coordinator.buildAgentContext(gateAgentId) ?: ""
        val state = routa.coordinator.coordinationState.value
        val tasks = routa.context.taskStore.listByStatus(workspaceId, TaskStatus.REVIEW_REQUIRED)

        return buildString {
            appendLine(roleContext)
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("# Tasks to Verify")
            appendLine()

            for (task in tasks) {
                appendLine("## ${task.title}")
                appendLine()
                appendLine("**Objective:** ${task.objective}")
                appendLine()

                if (task.acceptanceCriteria.isNotEmpty()) {
                    appendLine("**Acceptance Criteria:**")
                    task.acceptanceCriteria.forEach { appendLine("- $it") }
                    appendLine()
                }

                if (task.completionSummary != null) {
                    appendLine("**Crafter Report:** ${task.completionSummary}")
                    appendLine()
                }

                // Include CRAFTER conversation for evidence
                val crafterId = task.assignedTo
                if (crafterId != null) {
                    val conversation = routa.context.conversationStore.getLastN(crafterId, 5)
                    if (conversation.isNotEmpty()) {
                        appendLine("**Crafter Conversation (last ${conversation.size} messages):**")
                        for (msg in conversation) {
                            appendLine("> [${msg.role}]: ${msg.content.take(500)}")
                        }
                        appendLine()
                    }
                }

                if (task.verificationCommands.isNotEmpty()) {
                    appendLine("**Verification Commands:**")
                    task.verificationCommands.forEach { appendLine("- `$it`") }
                    appendLine()
                }

                appendLine("---")
            }

            appendLine()
            appendLine("Please verify each task against its Acceptance Criteria.")
            appendLine("Output your verdict: ✅ APPROVED or ❌ NOT APPROVED, with evidence.")
        }
    }

    /**
     * Reset NEEDS_FIX tasks back to PENDING for the next wave.
     */
    private suspend fun resetNeedsFixTasks() {
        val tasks = routa.context.taskStore.listByStatus(workspaceId, TaskStatus.NEEDS_FIX)
        for (task in tasks) {
            routa.context.taskStore.save(
                task.copy(
                    status = TaskStatus.PENDING,
                    assignedTo = null,
                    updatedAt = java.time.Instant.now().toString(),
                )
            )
        }
    }

    // ── Output parsing helpers ──────────────────────────────────────────

    /**
     * Extract a summary from agent output (first 2-3 sentences or lines).
     */
    private fun extractSummary(output: String): String {
        val lines = output.lines().filter { it.isNotBlank() }
        return lines.take(3).joinToString(" ").take(500)
    }

    /**
     * Try to extract file paths mentioned in the output.
     */
    private fun extractFilesModified(output: String): List<String> {
        val fileRegex = Regex("""(?:src|lib|app|test)/[\w/.-]+\.\w+""")
        return fileRegex.findAll(output).map { it.value }.distinct().toList()
    }

    private suspend fun buildSuccessResult(): OrchestratorResult.Success {
        val summary = routa.coordinator.getTaskSummary()
        return OrchestratorResult.Success(summary)
    }

    private suspend fun emitPhase(phase: OrchestratorPhase) {
        onPhaseChange?.invoke(phase)
    }
}

// ── Orchestrator phases (for UI/CLI callbacks) ──────────────────────────

sealed class OrchestratorPhase {
    data object Initializing : OrchestratorPhase()
    data object Planning : OrchestratorPhase()
    data class PlanReady(val planOutput: String) : OrchestratorPhase()
    data class TasksRegistered(val count: Int) : OrchestratorPhase()
    data class WaveStarting(val wave: Int) : OrchestratorPhase()
    data class CrafterRunning(val crafterId: String, val taskId: String) : OrchestratorPhase()
    data class CrafterCompleted(val crafterId: String, val taskId: String) : OrchestratorPhase()
    data class VerificationStarting(val wave: Int) : OrchestratorPhase()
    data class VerificationCompleted(val gateId: String, val output: String) : OrchestratorPhase()
    data class NeedsFix(val wave: Int) : OrchestratorPhase()
    data object Completed : OrchestratorPhase()
    data class MaxWavesReached(val waves: Int) : OrchestratorPhase()
}

// ── Orchestrator results ────────────────────────────────────────────────

sealed class OrchestratorResult {
    data class Success(
        val taskSummaries: List<com.phodal.routa.core.coordinator.TaskSummary>,
    ) : OrchestratorResult()

    data class NoTasks(val planOutput: String) : OrchestratorResult()

    data class MaxWavesReached(
        val waves: Int,
        val taskSummaries: List<com.phodal.routa.core.coordinator.TaskSummary>,
    ) : OrchestratorResult()

    data class Failed(val error: String) : OrchestratorResult()
}
