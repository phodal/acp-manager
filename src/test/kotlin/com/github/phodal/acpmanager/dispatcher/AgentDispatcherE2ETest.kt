package com.github.phodal.acpmanager.dispatcher

import com.github.phodal.acpmanager.dispatcher.model.*
import com.github.phodal.acpmanager.dispatcher.terminal.TerminalAgentExecutor
import com.github.phodal.acpmanager.dispatcher.terminal.TerminalPlanGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * E2E tests for the multi-agent dispatcher.
 *
 * Uses [TerminalAgentExecutor] and [TerminalPlanGenerator] to test the full
 * dispatch lifecycle without real ACP agents.
 */
class AgentDispatcherE2ETest {

    private lateinit var executor: TerminalAgentExecutor
    private lateinit var planGenerator: TerminalPlanGenerator
    private lateinit var dispatcher: DefaultAgentDispatcher
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        executor = TerminalAgentExecutor()
        planGenerator = TerminalPlanGenerator()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dispatcher = DefaultAgentDispatcher(planGenerator, executor, scope)

        // Register some test agents
        executor.registerAgent("coder")
        executor.registerAgent("reviewer")
        executor.registerAgent("researcher")

        dispatcher.setAgentRoles(
            listOf(
                AgentRole(id = "coder", name = "Coder", acpAgentKey = "claude"),
                AgentRole(id = "reviewer", name = "Reviewer", acpAgentKey = "codex"),
                AgentRole(id = "researcher", name = "Researcher", acpAgentKey = "gemini"),
            )
        )
        dispatcher.setMasterAgent("claude")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `test single task plan generation and execution`() = runTest {
        // Set up a simple plan
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Implement feature",
                        description = "Implement a login feature",
                        assignedAgent = "coder",
                    )
                ),
                maxParallelism = 1,
                thinking = "Simple single task",
            )
        )

        executor.setSimulatedResult(
            taskId = "task-1",
            messages = listOf("Analyzing requirements...", "Writing code...", "Done."),
            delayMs = 10,
        )

        // Execute planning
        dispatcher.startPlanning("Implement login feature")

        // Verify plan was generated
        val state = dispatcher.state.value
        assertNotNull(state.plan)
        val plan = state.plan!!
        assertEquals(1, plan.tasks.size)
        assertEquals("Implement feature", plan.tasks[0].title)

        // Execute the plan
        dispatcher.executePlan()

        // Verify completion
        val finalState = dispatcher.state.value
        assertEquals(DispatcherStatus.COMPLETED, finalState.status)
        val finalPlan = finalState.plan!!
        assertEquals(AgentTaskStatus.DONE, finalPlan.tasks[0].status)

        // Verify logs were emitted
        assertTrue(executor.executionLogs.isNotEmpty())
        assertTrue(executor.executionLogs.any { it.message.contains("Analyzing") })
    }

    @Test
    fun `test parallel task execution`() = runTest {
        // Set up a plan with parallel tasks
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Research JWT",
                        description = "Research JWT best practices",
                        assignedAgent = "researcher",
                        parallelGroup = 0,
                    ),
                    AgentTask(
                        id = "task-2",
                        title = "Setup auth module",
                        description = "Create auth module structure",
                        assignedAgent = "coder",
                        parallelGroup = 0,
                    ),
                    AgentTask(
                        id = "task-3",
                        title = "Code review",
                        description = "Review auth implementation",
                        assignedAgent = "reviewer",
                        dependencies = listOf("task-1", "task-2"),
                    ),
                ),
                maxParallelism = 2,
                thinking = "Tasks 1 and 2 can run in parallel, task 3 depends on both",
            )
        )

        // Set up simulated results
        executor.setSimulatedResult("task-1", listOf("Researching...", "Found best practices."), delayMs = 20)
        executor.setSimulatedResult("task-2", listOf("Creating module...", "Module created."), delayMs = 15)
        executor.setSimulatedResult("task-3", listOf("Reviewing code...", "Approved."), delayMs = 10)

        // Execute
        dispatcher.startPlanning("Implement user authentication module")
        dispatcher.executePlan()

        // Verify
        val finalState = dispatcher.state.value
        assertEquals(DispatcherStatus.COMPLETED, finalState.status)
        val plan = finalState.plan!!
        assertEquals(3, plan.tasks.size)

        for (task in plan.tasks) {
            assertEquals("Task ${task.id} should be DONE", AgentTaskStatus.DONE, task.status)
        }
    }

    @Test
    fun `test task failure handling`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Failing task",
                        description = "This will fail",
                        assignedAgent = "coder",
                    ),
                ),
                maxParallelism = 1,
            )
        )

        executor.setSimulatedResult(
            taskId = "task-1",
            messages = listOf("Starting..."),
            delayMs = 10,
            shouldFail = true,
            failMessage = "Deadlock in concurrent transaction",
        )

        dispatcher.startPlanning("Do something")
        dispatcher.executePlan()

        val finalState = dispatcher.state.value
        val finalPlan = finalState.plan!!
        assertEquals(AgentTaskStatus.FAILED, finalPlan.tasks[0].status)

        // Verify error was logged
        assertTrue(finalState.logs.any { it.level == LogLevel.ERR && it.message.contains("Failed") })
    }

    @Test
    fun `test update task agent assignment`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Test task",
                        description = "A test task",
                        assignedAgent = "coder",
                    ),
                ),
                maxParallelism = 1,
            )
        )

        dispatcher.startPlanning("Test")

        // Change agent assignment
        dispatcher.updateTaskAgent("task-1", "reviewer")

        val state = dispatcher.state.value
        assertEquals("reviewer", state.plan!!.tasks[0].assignedAgent)
    }

    @Test
    fun `test update max parallelism`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(id = "task-1", title = "T1", description = "D1", assignedAgent = "coder"),
                ),
                maxParallelism = 1,
            )
        )

        dispatcher.startPlanning("Test")

        dispatcher.updateMaxParallelism(3)
        assertEquals(3, dispatcher.state.value.plan!!.maxParallelism)

        // Should clamp to max 5
        dispatcher.updateMaxParallelism(10)
        assertEquals(5, dispatcher.state.value.plan!!.maxParallelism)

        // Should clamp to min 1
        dispatcher.updateMaxParallelism(0)
        assertEquals(1, dispatcher.state.value.plan!!.maxParallelism)
    }

    @Test
    fun `test dispatcher reset`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(id = "task-1", title = "T1", description = "D1", assignedAgent = "coder"),
                ),
                maxParallelism = 1,
            )
        )

        dispatcher.startPlanning("Test")
        assertNotNull(dispatcher.state.value.plan)

        dispatcher.reset()
        val state = dispatcher.state.value
        assertEquals(DispatcherStatus.IDLE, state.status)
        assertNull(state.plan)
        assertTrue(state.logs.isEmpty())
    }

    @Test
    fun `test state transitions during lifecycle`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(id = "task-1", title = "T1", description = "D1", assignedAgent = "coder"),
                ),
                maxParallelism = 1,
            )
        )

        executor.setSimulatedResult("task-1", listOf("Working..."), delayMs = 10)

        // Initial state
        assertEquals(DispatcherStatus.IDLE, dispatcher.state.value.status)

        // After planning
        dispatcher.startPlanning("Test")
        assertEquals(DispatcherStatus.PLANNED, dispatcher.state.value.status)

        // After execution
        dispatcher.executePlan()
        assertEquals(DispatcherStatus.COMPLETED, dispatcher.state.value.status)
    }

    @Test
    fun `test task with no assigned agent fails gracefully`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Unassigned task",
                        description = "No agent assigned",
                        assignedAgent = null,
                    ),
                ),
                maxParallelism = 1,
            )
        )

        dispatcher.startPlanning("Test")
        dispatcher.executePlan()

        val finalState = dispatcher.state.value
        val finalPlan = finalState.plan!!
        assertEquals(AgentTaskStatus.FAILED, finalPlan.tasks[0].status)
        assertTrue(finalState.logs.any { it.message.contains("no assigned agent") })
    }

    @Test
    fun `test log stream captures all events`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(id = "task-1", title = "T1", description = "D1", assignedAgent = "coder"),
                    AgentTask(id = "task-2", title = "T2", description = "D2", assignedAgent = "reviewer",
                        dependencies = listOf("task-1")),
                ),
                maxParallelism = 1,
            )
        )

        executor.setSimulatedResult("task-1", listOf("Step 1a", "Step 1b"), delayMs = 5)
        executor.setSimulatedResult("task-2", listOf("Step 2a"), delayMs = 5)

        dispatcher.startPlanning("Test")
        dispatcher.executePlan()

        val allLogs = dispatcher.state.value.logs
        // Should have logs from planning + task execution
        assertTrue("Should have multiple log entries", allLogs.size >= 4)

        // Verify task-specific logs
        val task1Logs = allLogs.filter { it.taskId == "task-1" }
        assertTrue("Should have logs for task-1", task1Logs.isNotEmpty())
    }

    @Test
    fun `test context passing between dependent tasks`() = runTest {
        // task-2 depends on task-1. After task-1 completes, its output
        // should be injected as context into task-2's prompt.
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Analyze codebase",
                        description = "Analyze the project structure",
                        assignedAgent = "researcher",
                    ),
                    AgentTask(
                        id = "task-2",
                        title = "Generate docs",
                        description = "Generate documentation based on analysis",
                        assignedAgent = "coder",
                        dependencies = listOf("task-1"),
                    ),
                ),
                maxParallelism = 1,
            )
        )

        executor.setSimulatedResult("task-1", listOf("Found 3 modules", "Main entry: App.kt"), delayMs = 5)
        executor.setSimulatedResult("task-2", listOf("Generating docs..."), delayMs = 5)

        dispatcher.startPlanning("Generate project docs")
        dispatcher.executePlan()

        // Verify task-2 received context from task-1
        val task2Prompt = executor.receivedPrompts["task-2"]
        assertNotNull("task-2 should have received a prompt", task2Prompt)
        assertTrue(
            "task-2 prompt should contain task-1 output",
            task2Prompt!!.contains("Found 3 modules")
        )
        assertTrue(
            "task-2 prompt should contain context header",
            task2Prompt.contains("Context from completed tasks")
        )

        // Verify task-1 result is stored
        val finalState = dispatcher.state.value
        val finalPlan = finalState.plan!!
        val task1 = finalPlan.tasks.first { it.id == "task-1" }
        assertNotNull("task-1 should have a result", task1.result)
        assertTrue("task-1 result should contain output", task1.result!!.contains("Found 3 modules"))
    }

    @Test
    fun `test no context for tasks without dependencies`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Independent task",
                        description = "Do something independent",
                        assignedAgent = "coder",
                    ),
                ),
                maxParallelism = 1,
            )
        )

        executor.setSimulatedResult("task-1", listOf("Done"), delayMs = 5)

        dispatcher.startPlanning("Test")
        dispatcher.executePlan()

        // Task without dependencies should receive only its own description
        val task1Prompt = executor.receivedPrompts["task-1"]
        assertNotNull(task1Prompt)
        assertEquals("Do something independent", task1Prompt)
    }

    @Test
    fun `test single agent strategy overrides all task agents`() = runTest {
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(
                        id = "task-1",
                        title = "Step 1",
                        description = "First step",
                        assignedAgent = "researcher",
                    ),
                    AgentTask(
                        id = "task-2",
                        title = "Step 2",
                        description = "Second step",
                        assignedAgent = "coder",
                        dependencies = listOf("task-1"),
                    ),
                ),
                maxParallelism = 2,
                strategy = ExecutionStrategy.SINGLE_AGENT,
            )
        )

        executor.setSimulatedResult("task-1", listOf("Result 1"), delayMs = 5)
        executor.setSimulatedResult("task-2", listOf("Result 2"), delayMs = 5)

        dispatcher.startPlanning("Test single agent")
        dispatcher.executePlan()

        val finalState = dispatcher.state.value
        assertEquals(DispatcherStatus.COMPLETED, finalState.status)

        // In SINGLE_AGENT mode, all tasks should use the master agent key ("claude")
        val finalPlan = finalState.plan!!
        for (task in finalPlan.tasks) {
            assertEquals("claude", task.assignedAgent)
        }

        // Max parallelism should be forced to 1
        assertEquals(1, finalPlan.maxParallelism)

        // Verify single agent log
        assertTrue(finalState.logs.any { it.message.contains("Single-agent mode") })
    }

    @Test
    fun `test multi-task context chain A to B to C`() = runTest {
        // A -> B -> C chain: C should receive context from B (which already has A's context in its prompt)
        planGenerator.setFixedPlan(
            DispatchPlan(
                tasks = listOf(
                    AgentTask(id = "task-1", title = "Research", description = "Research topic",
                        assignedAgent = "researcher"),
                    AgentTask(id = "task-2", title = "Implement", description = "Implement feature",
                        assignedAgent = "coder", dependencies = listOf("task-1")),
                    AgentTask(id = "task-3", title = "Review", description = "Review code",
                        assignedAgent = "reviewer", dependencies = listOf("task-2")),
                ),
                maxParallelism = 1,
            )
        )

        executor.setSimulatedResult("task-1", listOf("Research finding: use pattern X"), delayMs = 5)
        executor.setSimulatedResult("task-2", listOf("Implemented using pattern X"), delayMs = 5)
        executor.setSimulatedResult("task-3", listOf("Code looks good"), delayMs = 5)

        dispatcher.startPlanning("Full pipeline")
        dispatcher.executePlan()

        // task-2 should have context from task-1
        val task2Prompt = executor.receivedPrompts["task-2"]!!
        assertTrue("task-2 should reference task-1 output", task2Prompt.contains("Research finding: use pattern X"))

        // task-3 should have context from task-2
        val task3Prompt = executor.receivedPrompts["task-3"]!!
        assertTrue("task-3 should reference task-2 output", task3Prompt.contains("Implemented using pattern X"))
    }
}
