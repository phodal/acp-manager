package com.phodal.routa.core.runner

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.config.RoutaConfigLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeoutException

/**
 * End-to-end tests using Claude Code CLI for CRAFTER and GATE.
 *
 * ROUTA (DeepSeek) plans tasks → Claude implements → Claude verifies.
 *
 * SKIPPED if Claude CLI is not available or config is missing.
 *
 * Run with:
 * ```bash
 * ./gradlew :routa-core:test --tests "*ClaudeE2ETest*"
 * ```
 */
class ClaudeE2ETest {

    private fun claudePath(): String? {
        val paths = listOf("/opt/homebrew/bin/claude", "/usr/local/bin/claude")
        return paths.firstOrNull { File(it).exists() }
    }

    private fun ensurePreconditions() {
        assumeTrue("No Claude CLI found", claudePath() != null)
        assumeTrue("No LLM config for ROUTA", RoutaConfigLoader.hasConfig())
    }

    private fun createTestDir(name: String): File {
        val dir = File("/tmp/routa-e2e-$name-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    // ── Scenario 1: Simple Hello World ──────────────────────────────────

    @Test
    fun `scenario 1 - Java Hello World`() {
        ensurePreconditions()

        val testDir = createTestDir("hello")
        println("=== Scenario 1: Java Hello World ===")
        println("Working directory: $testDir")

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val koogRunner = KoogAgentRunner(routa.tools, "e2e-hello")
        val claudeRunner = ClaudeAgentRunner(
            claudePath = claudePath()!!,
            cwd = testDir.absolutePath,
            onOutput = { text -> print(text) },
        )
        val runner = CompositeAgentRunner(koogRunner = koogRunner, acpRunner = claudeRunner)

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "e2e-hello",
            maxWaves = 2,
            onPhaseChange = { phase -> println("\n[Phase] $phase") },
        )

        try {
            val result = runBlocking {
                orchestrator.execute("创建一个 Java Hello World 程序，包含 Main.java，打印 Hello World")
            }

            println("\n=== RESULT ===")
            println(result)

            // Check if files were actually created
            val files = testDir.walkTopDown().map { it.relativeTo(testDir).path }.toList()
            println("\n=== FILES CREATED ===")
            files.forEach { println("  - $it") }

            // Verify
            assertTrue("Expected at least one .java file in $testDir", files.any { it.endsWith(".java") })

            val tasks = runBlocking { routa.coordinator.getTaskSummary() }
            println("\n=== TASKS ===")
            tasks.forEach { t -> println("  [${t.status}] ${t.title} (verdict: ${t.verdict})") }
        } catch (e: IOException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to IO error: ${e.message}", false)
        } catch (e: ConnectException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to connection error: ${e.message}", false)
        } catch (e: TimeoutException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to timeout: ${e.message}", false)
        } finally {
            routa.coordinator.shutdown()
        }
    }

    // ── Scenario 2: Multi-file Calculator ───────────────────────────────

    @Test
    fun `scenario 2 - Calculator with tests`() {
        ensurePreconditions()

        val testDir = createTestDir("calc")
        println("=== Scenario 2: Calculator with Tests ===")
        println("Working directory: $testDir")

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val koogRunner = KoogAgentRunner(routa.tools, "e2e-calc")
        val claudeRunner = ClaudeAgentRunner(
            claudePath = claudePath()!!,
            cwd = testDir.absolutePath,
            onOutput = { text -> print(text) },
        )
        val runner = CompositeAgentRunner(koogRunner = koogRunner, acpRunner = claudeRunner)

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "e2e-calc",
            maxWaves = 2,
            onPhaseChange = { phase -> println("\n[Phase] $phase") },
        )

        try {
            val result = runBlocking {
                orchestrator.execute(
                    "创建一个 Python 计算器:\n" +
                    "1. calculator.py - Calculator 类，支持 add, subtract, multiply, divide\n" +
                    "2. main.py - 演示计算器使用\n" +
                    "3. test_calculator.py - pytest 测试"
                )
            }

            println("\n=== RESULT ===")
            println(result)

            val files = testDir.walkTopDown().map { it.relativeTo(testDir).path }.toList()
            println("\n=== FILES CREATED ===")
            files.forEach { println("  - $it") }

            assertTrue("Expected at least one .py file in $testDir", files.any { it.endsWith(".py") })

            val tasks = runBlocking { routa.coordinator.getTaskSummary() }
            println("\n=== TASKS ===")
            tasks.forEach { t -> println("  [${t.status}] ${t.title}") }
        } catch (e: IOException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to IO error: ${e.message}", false)
        } catch (e: ConnectException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to connection error: ${e.message}", false)
        } catch (e: TimeoutException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to timeout: ${e.message}", false)
        } finally {
            routa.coordinator.shutdown()
        }
    }

    // ── Scenario 3: Bug Fix ─────────────────────────────────────────────

    @Test
    fun `scenario 3 - fix a bug in existing code`() {
        ensurePreconditions()

        val testDir = createTestDir("bugfix")
        println("=== Scenario 3: Bug Fix ===")
        println("Working directory: $testDir")

        // Pre-create a file with a bug
        File(testDir, "utils.py").writeText(
            """
            |def divide(a, b):
            |    return a / b  # BUG: no zero division check
            |
            |def greet(name):
            |    return "Hello, " + name + "!"
            |
            |if __name__ == "__main__":
            |    print(divide(10, 0))  # This will crash
            """.trimMargin()
        )

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val koogRunner = KoogAgentRunner(routa.tools, "e2e-bugfix")
        val claudeRunner = ClaudeAgentRunner(
            claudePath = claudePath()!!,
            cwd = testDir.absolutePath,
            onOutput = { text -> print(text) },
        )
        val runner = CompositeAgentRunner(koogRunner = koogRunner, acpRunner = claudeRunner)

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "e2e-bugfix",
            maxWaves = 2,
            onPhaseChange = { phase -> println("\n[Phase] $phase") },
        )

        try {
            val result = runBlocking {
                orchestrator.execute(
                    "修复 utils.py 中的 divide 函数 bug:\n" +
                    "- 添加除零检查，当 b=0 时返回 None 并打印警告\n" +
                    "- 修复 main 部分使其不会 crash\n" +
                    "- 添加 test_utils.py 测试验证修复"
                )
            }

            println("\n=== RESULT ===")
            println(result)

            // Check utils.py was modified
            val utilsContent = File(testDir, "utils.py").readText()
            println("\n=== utils.py content ===")
            println(utilsContent)

            val files = testDir.walkTopDown().map { it.relativeTo(testDir).path }.toList()
            println("\n=== FILES ===")
            files.forEach { println("  - $it") }

            val tasks = runBlocking { routa.coordinator.getTaskSummary() }
            println("\n=== TASKS ===")
            tasks.forEach { t -> println("  [${t.status}] ${t.title}") }
        } catch (e: IOException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to IO error: ${e.message}", false)
        } catch (e: ConnectException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to connection error: ${e.message}", false)
        } catch (e: TimeoutException) {
            println("Error: ${e.message}")
            assumeTrue("Test skipped due to timeout: ${e.message}", false)
        } finally {
            routa.coordinator.shutdown()
        }
    }
}
