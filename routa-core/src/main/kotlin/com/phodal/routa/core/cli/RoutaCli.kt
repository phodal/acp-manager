package com.phodal.routa.core.cli

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.coordinator.TaskSummary
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.CapabilityBasedRouter
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.provider.ThinkingPhase
import com.phodal.routa.core.runner.*
import com.phodal.routa.core.viewmodel.RoutaViewModel
import kotlinx.coroutines.*

/**
 * CLI entry point for the Routa multi-agent orchestrator.
 *
 * Uses the shared [RoutaViewModel] for orchestration state management,
 * the same ViewModel used by the IntelliJ plugin's DispatcherPanel.
 *
 * Reads config from `~/.autodev/config.yaml`:
 * - LLM config for ROUTA (planning) and GATE (verification)
 * - ACP agent config for CRAFTER (real coding agents like Codex, Claude Code)
 *
 * Uses [CapabilityBasedRouter] for dynamic provider selection:
 * - Each provider declares its capabilities (file editing, terminal, tool calling, etc.)
 * - The router automatically picks the best provider for each role
 * - Crafters run in parallel when using AgentProvider
 *
 * Usage:
 * ```bash
 * ./gradlew :routa-core:run
 * ./gradlew :routa-core:run --args="--cwd /path/to/project"
 * ./gradlew :routa-core:run --args="--crafter claude-code"
 * ```
 */
fun main(args: Array<String>) {
    println("╔══════════════════════════════════════════════╗")
    println("║         Routa Multi-Agent Orchestrator       ║")
    println("╠══════════════════════════════════════════════╣")
    println("║  ROUTA → plans tasks          (LLM/Koog)    ║")
    println("║  CRAFTER → implements tasks   (ACP Agent)   ║")
    println("║  GATE → verifies all work     (LLM/Koog)    ║")
    println("╚══════════════════════════════════════════════╝")
    println()

    // Check LLM config
    val configPath = RoutaConfigLoader.getConfigPath()
    if (!RoutaConfigLoader.hasConfig()) {
        println("⚠  No valid LLM config found at: $configPath")
        println()
        println("Please create ~/.autodev/config.yaml with:")
        println()
        println("  active: default")
        println("  configs:")
        println("    - name: default")
        println("      provider: deepseek")
        println("      apiKey: sk-...")
        println("      model: deepseek-chat")
        println("  acpAgents:")
        println("    codex:")
        println("      command: codex")
        println("      args: [\"--full-auto\"]")
        println("  activeCrafter: codex")
        return
    }

    val activeConfig = RoutaConfigLoader.getActiveModelConfig()!!
    println("✓ LLM config: ${activeConfig.provider} / ${activeConfig.model}")

    // Check ACP agent config
    val crafterInfo = RoutaConfigLoader.getActiveCrafterConfig()
    if (crafterInfo != null) {
        println("✓ CRAFTER backend: ACP agent '${crafterInfo.first}' (${crafterInfo.second.command})")
    } else {
        println("  CRAFTER backend: Koog LLM (no ACP agent configured)")
        println("  Tip: add 'acpAgents' to config.yaml for real coding agents")
    }
    println()

    // Resolve CWD and crafter override
    var cwd = System.getProperty("user.dir") ?: "."
    var crafterOverride: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--cwd" -> { i++; if (i < args.size) cwd = args[i] }
            "--crafter" -> { i++; if (i < args.size) crafterOverride = args[i] }
            else -> cwd = args[i]
        }
        i++
    }
    println("  Working directory: $cwd")

    // Create system
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Build the provider using capability-based routing
    val provider = buildProvider(scope, cwd, crafterOverride)

    // Print provider routing info
    printProviderInfo(provider)

    // Create the shared ViewModel
    val viewModel = RoutaViewModel(scope)

    println()
    println("Enter your requirement (or 'quit' to exit):")
    println("─".repeat(50))

    while (true) {
        print("\n> ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) {
            break
        }
        if (input.isEmpty()) continue

        val workspaceId = "cli-${System.currentTimeMillis()}"

        // Initialize the ViewModel for this session
        // Don't use enhanced prompt — the orchestrator handles prompt building
        viewModel.useEnhancedRoutaPrompt = false
        viewModel.initialize(provider, workspaceId)

        // Collect streams in parallel for real-time CLI output
        val phaseJob = scope.launch {
            viewModel.phase.collect { printPhase(it) }
        }
        val routaChunkJob = scope.launch {
            viewModel.routaChunks.collect { chunk -> printStreamChunk("routa", chunk) }
        }
        val crafterChunkJob = scope.launch {
            viewModel.crafterChunks.collect { (agentId, chunk) -> printStreamChunk(agentId, chunk) }
        }
        val gateChunkJob = scope.launch {
            viewModel.gateChunks.collect { chunk -> printStreamChunk("gate", chunk) }
        }

        try {
            val result = runBlocking {
                viewModel.execute(input)
            }
            printResult(result)

            // Print event replay summary
            val system = viewModel.system
            if (system != null) {
                val eventLog = runBlocking { system.eventBus.getTimestampedLog() }
                if (eventLog.isNotEmpty()) {
                    println()
                    println("📊 Event log: ${eventLog.size} critical events recorded")
                }
            }
        } catch (e: Exception) {
            println()
            println("✗ Error: ${e.message}")
            e.printStackTrace()
        } finally {
            phaseJob.cancel()
            routaChunkJob.cancel()
            crafterChunkJob.cancel()
            gateChunkJob.cancel()
            viewModel.reset()
        }
    }

    // Shutdown
    viewModel.dispose()
    runBlocking { provider.shutdown() }
    println("\nGoodbye!")
}

/**
 * Build an [AgentProvider] via [RoutaFactory.createProvider] with capability-based routing.
 *
 * This replaces the old [buildRunner] which used hardcoded CompositeAgentRunner.
 * Now each provider declares its capabilities, and the [CapabilityBasedRouter]
 * dynamically selects the best match for each role.
 */
private fun buildProvider(scope: CoroutineScope, cwd: String, crafterOverride: String? = null): AgentProvider {
    val routa = RoutaFactory.createInMemory(scope)

    // Resolve ACP agent config
    val crafterInfo = if (crafterOverride != null) {
        val agents = RoutaConfigLoader.getAcpAgents()
        val agent = agents[crafterOverride]
        if (agent != null) crafterOverride to agent else null
    } else {
        RoutaConfigLoader.getActiveCrafterConfig()
    }

    // Determine if the configured crafter is Claude Code
    val isClaudeCode = crafterInfo?.second?.command?.contains("claude") == true

    return RoutaFactory.createProvider(
        system = routa,
        workspaceId = "cli-workspace",
        cwd = cwd,
        acpConfig = if (!isClaudeCode) crafterInfo?.second else null,
        acpAgentKey = if (!isClaudeCode) (crafterInfo?.first ?: "codex") else "codex",
        claudePath = if (isClaudeCode) crafterInfo?.second?.command else null,
        modelConfig = RoutaConfigLoader.getActiveModelConfig(),
        resilient = true,
    )
}

/**
 * Print the provider routing table.
 */
private fun printProviderInfo(provider: AgentProvider) {
    if (provider is CapabilityBasedRouter) {
        println()
        println("  Provider routing (capability-based):")
        for (caps in provider.listProviders()) {
            val features = mutableListOf<String>()
            if (caps.supportsFileEditing) features.add("files")
            if (caps.supportsTerminal) features.add("terminal")
            if (caps.supportsToolCalling) features.add("tools")
            if (caps.supportsStreaming) features.add("streaming")
            if (caps.supportsInterrupt) features.add("interrupt")
            println("    ${caps.name} [priority=${caps.priority}] → ${features.joinToString(", ")}")
        }
    }
}

private fun printStreamChunk(agentId: String, chunk: StreamChunk) {
    when (chunk) {
        is StreamChunk.Text -> print(chunk.content)
        is StreamChunk.Thinking -> {
            when (chunk.phase) {
                ThinkingPhase.START -> print("\n    💭 ")
                ThinkingPhase.CHUNK -> print(chunk.content)
                ThinkingPhase.END -> println()
            }
        }
        is StreamChunk.ToolCall -> println("    [Tool: ${chunk.name} (${chunk.status})]")
        is StreamChunk.Error -> println("    ⚠ ${chunk.message}")
        is StreamChunk.Completed -> println("\n    [${chunk.stopReason}]")
        is StreamChunk.CompletionReport -> {
            val icon = if (chunk.success) "✓" else "✗"
            println("\n    $icon Report: ${chunk.summary.take(100)}")
            if (chunk.filesModified.isNotEmpty()) {
                println("      Files: ${chunk.filesModified.joinToString(", ")}")
            }
        }
        is StreamChunk.Heartbeat -> { /* silent */ }
    }
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
