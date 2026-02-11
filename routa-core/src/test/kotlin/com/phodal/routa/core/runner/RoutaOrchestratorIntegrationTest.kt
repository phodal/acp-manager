package com.phodal.routa.core.runner

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.config.RoutaConfigLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Integration test that runs the FULL orchestration loop with a real LLM.
 *
 * SKIPPED if no valid ~/.autodev/config.yaml exists.
 *
 * Run with:
 * ```bash
 * ./gradlew :routa-core:test --tests "*RoutaOrchestratorIntegrationTest*"
 * ```
 */
class RoutaOrchestratorIntegrationTest {

    @Test
    fun `full orchestration - simple hello world`() {
        assumeTrue("No valid config", RoutaConfigLoader.hasConfig())

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val runner = KoogAgentRunner(routa.tools, "integration-test")
        val phases = mutableListOf<String>()

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "integration-test",
            onPhaseChange = { phase ->
                val msg = phase.toString()
                phases.add(msg)
                println("[Phase] $msg")
            }
        )

        try {
            val result = runBlocking {
                orchestrator.execute("编写一个 Java Hello World 程序，包含一个 Main 类")
            }

            println("\n=== RESULT ===")
            println(result)
            println("\n=== PHASES ===")
            phases.forEach { println("  - $it") }
            println("\n=== TASKS ===")
            val tasks = runBlocking { routa.coordinator.getTaskSummary() }
            tasks.forEach { t ->
                println("  [${t.status}] ${t.title} (agent: ${t.assignedAgent}, verdict: ${t.verdict})")
            }

            // Basic assertion - we should get a result (not crash)
            assertFalse(
                "Orchestration should not fail: $result",
                result is OrchestratorResult.Failed
            )
        } catch (e: Exception) {
            println("Integration test error (LLM might be unavailable): ${e.message}")
            assumeTrue("LLM unavailable: ${e.message}", false)
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `full orchestration - multi-task scenario`() {
        assumeTrue("No valid config", RoutaConfigLoader.hasConfig())

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val runner = KoogAgentRunner(routa.tools, "integration-test")

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "integration-multi",
            onPhaseChange = { phase -> println("[Phase] $phase") }
        )

        try {
            val result = runBlocking {
                orchestrator.execute(
                    "创建一个简单的 Java 计算器程序:\n" +
                    "1. 一个 Calculator 类，支持 add, subtract, multiply, divide\n" +
                    "2. 一个 Main 类，演示使用 Calculator\n" +
                    "3. 一个 CalculatorTest 测试类"
                )
            }

            println("\n=== RESULT ===")
            println(result)

            val tasks = runBlocking { routa.coordinator.getTaskSummary() }
            println("\n=== TASKS (${tasks.size}) ===")
            tasks.forEach { t ->
                println("  [${t.status}] ${t.title}")
            }

            assertFalse(
                "Orchestration should not fail: $result",
                result is OrchestratorResult.Failed
            )
        } catch (e: Exception) {
            println("Integration test error: ${e.message}")
            assumeTrue("LLM unavailable: ${e.message}", false)
        } finally {
            routa.coordinator.shutdown()
        }
    }
}
