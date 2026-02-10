package com.phodal.routa.core.cli

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.coordinator.TaskSummary
import com.phodal.routa.core.runner.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * CLI entry point for the Routa multi-agent orchestrator.
 *
 * Reads LLM config from `~/.autodev/config.yaml`, then enters an interactive
 * loop where the user inputs requirements and Routa orchestrates agents to
 * implement them.
 *
 * Usage:
 * ```bash
 * ./gradlew :routa-core:run
 * ```
 */
fun main(args: Array<String>) {
    println("╔══════════════════════════════════════════════╗")
    println("║         Routa Multi-Agent Orchestrator       ║")
    println("╠══════════════════════════════════════════════╣")
    println("║  ROUTA → plans tasks                        ║")
    println("║  CRAFTER → implements each task             ║")
    println("║  GATE → verifies all work                   ║")
    println("╚══════════════════════════════════════════════╝")
    println()

    // Check config
    val configPath = RoutaConfigLoader.getConfigPath()
    if (!RoutaConfigLoader.hasConfig()) {
        println("⚠  No valid config found at: $configPath")
        println()
        println("Please create ~/.autodev/config.yaml with:")
        println()
        println("  active: default")
        println("  configs:")
        println("    - name: default")
        println("      provider: deepseek")
        println("      apiKey: sk-...")
        println("      model: deepseek-chat")
        println()
        println("Supported providers: openai, anthropic, google, deepseek, ollama, openrouter")
        return
    }

    val activeConfig = RoutaConfigLoader.getActiveModelConfig()!!
    println("✓ Config loaded from: $configPath")
    println("  Provider: ${activeConfig.provider}")
    println("  Model: ${activeConfig.model}")
    println()

    // Create system
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val routa = RoutaFactory.createInMemory(scope)
    val runner = KoogAgentRunner(routa.tools, "cli-workspace")

    println("Enter your requirement (or 'quit' to exit):")
    println("─".repeat(50))

    while (true) {
        print("\n> ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) {
            break
        }
        if (input.isEmpty()) continue

        val orchestrator = RoutaOrchestrator(
            routa = RoutaFactory.createInMemory(scope),
            runner = runner,
            workspaceId = "cli-${System.currentTimeMillis()}",
            onPhaseChange = { phase -> printPhase(phase) }
        )

        try {
            val result = runBlocking {
                orchestrator.execute(input)
            }
            printResult(result)
        } catch (e: Exception) {
            println()
            println("✗ Error: ${e.message}")
            e.printStackTrace()
        }
    }

    routa.coordinator.shutdown()
    println("\nGoodbye!")
}

private fun printPhase(phase: OrchestratorPhase) {
    when (phase) {
        is OrchestratorPhase.Initializing ->
            println("\n⏳ Initializing...")
        is OrchestratorPhase.Planning ->
            println("🎯 ROUTA is planning tasks...")
        is OrchestratorPhase.PlanReady -> {
            println()
            println("📋 Plan ready:")
            println("─".repeat(40))
            println(phase.planOutput.take(2000))
            println("─".repeat(40))
        }
        is OrchestratorPhase.TasksRegistered ->
            println("✓ ${phase.count} task(s) registered")
        is OrchestratorPhase.WaveStarting ->
            println("\n⚙️  Wave ${phase.wave} — executing tasks...")
        is OrchestratorPhase.CrafterRunning ->
            println("  🔨 CRAFTER running task ${phase.taskId.take(8)}...")
        is OrchestratorPhase.CrafterCompleted ->
            println("  ✓ CRAFTER completed task ${phase.taskId.take(8)}")
        is OrchestratorPhase.VerificationStarting ->
            println("\n✅ GATE verifying wave ${phase.wave}...")
        is OrchestratorPhase.VerificationCompleted -> {
            println("  GATE verdict:")
            println("  ${phase.output.take(500)}")
        }
        is OrchestratorPhase.NeedsFix ->
            println("⚠  Wave ${phase.wave} needs fixes, retrying...")
        is OrchestratorPhase.Completed ->
            println("\n🎉 All tasks completed and verified!")
        is OrchestratorPhase.MaxWavesReached ->
            println("\n⚠  Max waves (${phase.waves}) reached")
    }
}

private fun printResult(result: OrchestratorResult) {
    println()
    println("═".repeat(50))
    when (result) {
        is OrchestratorResult.Success -> {
            println("✅ ORCHESTRATION COMPLETE")
            println()
            printTaskSummaries(result.taskSummaries)
        }
        is OrchestratorResult.NoTasks -> {
            println("⚠  ROUTA produced no tasks")
            println("Output: ${result.planOutput.take(500)}")
        }
        is OrchestratorResult.MaxWavesReached -> {
            println("⚠  Max waves (${result.waves}) reached — some tasks may be incomplete")
            println()
            printTaskSummaries(result.taskSummaries)
        }
        is OrchestratorResult.Failed -> {
            println("✗ FAILED: ${result.error}")
        }
    }
    println("═".repeat(50))
}

private fun printTaskSummaries(summaries: List<TaskSummary>) {
    if (summaries.isEmpty()) {
        println("  (no tasks)")
        return
    }
    for (summary in summaries) {
        val icon = when (summary.verdict) {
            com.phodal.routa.core.model.VerificationVerdict.APPROVED -> "✅"
            com.phodal.routa.core.model.VerificationVerdict.NOT_APPROVED -> "❌"
            com.phodal.routa.core.model.VerificationVerdict.BLOCKED -> "⚠️"
            null -> "⏳"
        }
        println("  $icon ${summary.title} [${summary.status}]")
        if (summary.assignedAgent != null) {
            println("     Agent: ${summary.assignedAgent} (${summary.assignedRole})")
        }
    }
}
