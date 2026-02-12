package com.phodal.routa.core.provider

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.tool.AgentTools
import com.phodal.routa.core.config.RoutaConfigLoader
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.koog.TextBasedToolExecutor
import com.phodal.routa.core.koog.ToolCallExtractor
import com.phodal.routa.core.koog.ToolCallStreamFilter
import kotlinx.coroutines.flow.cancellable
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace Agent provider — uses **text-based tool calling** (not native function calling).
 *
 * Instead of passing tool definitions to the LLM as function-calling parameters
 * (which many models handle poorly), this provider:
 *
 * 1. Embeds tool descriptions directly in the system prompt
 * 2. Instructs the LLM to emit tool calls as `<tool_call>` XML blocks
 * 3. Parses tool calls from the LLM's text response using [ToolCallExtractor]
 * 4. Executes tool calls using [TextBasedToolExecutor]
 * 5. Feeds results back as `<tool_result>` blocks in a follow-up user message
 * 6. Loops until the LLM stops emitting tool calls or max iterations is reached
 *
 * This approach is inspired by Intent by Augment's `agent-tool-executor.ts` and
 * `tool-call-extractor.ts`, where tool calls are extracted from text rather than
 * relying on the LLM provider's native tool/function-calling API.
 *
 * ## Why Text-Based Tool Calling?
 *
 * - **Better compatibility**: Works with any LLM, including those with poor
 *   function-calling support (many open-source models)
 * - **More reliable**: The LLM can see and reason about tool calls in context
 * - **Simpler debugging**: Tool calls are visible in the conversation text
 * - **Flexible parsing**: Supports XML, JSON, and markdown code block formats
 *
 * ## Tool Call Format
 *
 * The LLM generates tool calls in XML format:
 * ```xml
 * <tool_call>
 * {"name": "read_file", "arguments": {"path": "src/main.kt"}}
 * </tool_call>
 * ```
 *
 * Results are fed back as:
 * ```xml
 * <tool_result>
 * <tool_name>read_file</tool_name>
 * <status>success</status>
 * <output>
 * ... file contents ...
 * </output>
 * </tool_result>
 * ```
 *
 * @see ToolCallExtractor for parsing tool calls from text
 * @see TextBasedToolExecutor for executing parsed tool calls
 * @see KoogAgentProvider for the native function-calling approach
 */
class WorkspaceAgentProvider(
    private val agentTools: AgentTools,
    private val workspaceId: String,
    private val cwd: String,
    private val modelConfig: NamedModelConfig? = null,
    private val maxIterations: Int = 20,
) : AgentProvider {

    // Track active agents for isHealthy / interrupt
    private val activeAgents = ConcurrentHashMap<String, RunningAgent>()

    private data class RunningAgent(
        val role: AgentRole,
        @Volatile var cancelled: Boolean = false,
    )

    // ── System Prompt ────────────────────────────────────────────────────

    companion object {
        /**
         * Build the workspace agent system prompt with tool descriptions embedded.
         *
         * This prompt teaches the LLM to use `<tool_call>` XML format for invoking
         * tools, instead of relying on native function-calling parameters.
         *
         * Design: Workspace Agent is a **Coordinator** role (inspired by Intent).
         * - It plans, delegates, and verifies
         * - It does NOT implement code directly
         * - It has read-only file access for understanding the codebase
         * - Implementation is delegated to Implementor agents via @@@task blocks
         */
        fun buildSystemPrompt(cwd: String): String = """
            |## Workspace Coordinator
            |
            |You plan, delegate, and verify. You do NOT implement code yourself. You NEVER edit files directly.
            |**You have no file editing tools available. Delegation to implementor agents is the ONLY way code gets written.**
            |
            |## Hard Rules (CRITICAL)
            |
            |1. **NEVER edit code** — You have no file editing tools. Delegate implementation to implementor agents.
            |2. **NEVER use checkboxes for tasks** — No `- [ ]` lists. Use `@@@task` blocks ONLY (see syntax below).
            |3. **Spec first, always** — Create/update the spec BEFORE any delegation.
            |4. **Wait for approval** — Present the plan and STOP. Wait for user approval before delegating.
            |
            |## Working Directory
            |
            |Your workspace root is: $cwd
            |All file paths are relative to this directory.
            |
            |## Workflow (FOLLOW IN ORDER)
            |
            |1. **Understand**: Ask 1-4 clarifying questions if requirements are unclear
            |2. **Explore**: Use `list_files` and `read_file` to understand the codebase
            |3. **Spec**: Write the spec with tasks at the TOP using `@@@task` blocks
            |4. **STOP**: Present the plan to the user. Say "Please review and approve the plan above."
            |5. **Wait**: Do NOT proceed until the user approves
            |6. **Delegate**: After approval, tasks will be delegated to implementor agents
            |
            |## Spec Format
            |
            |- **Goal**: One sentence, user-visible outcome
            |- **Tasks**: Use `@@@task` blocks (see syntax below)
            |- **Acceptance Criteria**: Testable checklist (no vague language)
            |- **Non-goals**: What's explicitly out of scope
            |- **Assumptions**: Mark uncertain ones with "(confirm?)"
            |- **Verification Plan**: Commands/tests to run
            |
            |## Task Syntax (CRITICAL)
            |
            |**NEVER use markdown checkboxes** like `- [ ] Task name`. These do NOT create tasks.
            |
            |**ALWAYS use `@@@task` blocks:**
            |
            |```
            |@@@task
            |# Task Title Here
            |
            |## Objective
            |- What this task achieves
            |
            |## Scope
            |- What files/areas are in scope (and what is not)
            |
            |## Definition of Done
            |- Specific completion checks
            |
            |## Verification
            |- Exact commands or steps the implementor should run
            |
            |## Output required
            |- What to report back (1–3 sentences)
            |@@@
            |```
            |
            |**Rules:**
            |- One `@@@task` block per task
            |- First `# Heading` = task title
            |- Content below = task body
            |- Auto-converts to Task Note when saved
            |
            |## Available Tools (READ-ONLY)
            |
            |You have the following tools available for **reading and exploring** the codebase.
            |To use a tool, emit a `<tool_call>` block with a JSON object containing `name` and `arguments`.
            |
            |**CRITICAL**: Always include ALL required parameters. NEVER emit empty arguments `{}`.
            |
            |---
            |
            |### read_file
            |
            |Read the contents of a file in the workspace.
            |
            |**Parameters:**
            |- `path` (string, REQUIRED): File path relative to workspace root
            |
            |**Example:**
            |<tool_call>
            |{"name": "read_file", "arguments": {"path": "README.md"}}
            |</tool_call>
            |
            |**WRONG (will fail):**
            |<tool_call>
            |{"name": "read_file", "arguments": {}}
            |</tool_call>
            |
            |---
            |
            |### list_files
            |
            |List files and directories in a path.
            |
            |**Parameters:**
            |- `path` (string, optional, default: "."): Directory path relative to workspace root
            |
            |**Example:**
            |<tool_call>
            |{"name": "list_files", "arguments": {"path": "src"}}
            |</tool_call>
            |
            |---
            |
            |## Tool Call Format
            |
            |```
            |<tool_call>
            |{"name": "TOOL_NAME", "arguments": {"param1": "value1", "param2": "value2"}}
            |</tool_call>
            |```
            |
            |**Rules:**
            |1. **ALWAYS include required parameters** — `read_file` REQUIRES `path`
            |2. **JSON format** — Content inside `<tool_call>` must be valid JSON
            |3. **One tool per block** — Each `<tool_call>` block contains one tool invocation
            |4. **Wait for results** — After emitting tool calls, wait for `<tool_result>` responses
            |
            |## Important Reminders
            |
            |- You NEVER edit files directly. You have no file editing tools.
            |- Delegate ALL implementation to Implementor agents via `@@@task` blocks.
            |- Keep the Spec up to date — update it when plans change or decisions are made.
            |- When you're done planning, present the spec and STOP. Wait for user approval.
        """.trimMargin()
    }

    // ── AgentProvider: Run (with tool call loop) ────────────────────────

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        val components = createLLMComponents()
        val toolExecutor = TextBasedToolExecutor(cwd)

        activeAgents[agentId] = RunningAgent(role)

        return try {
            runAgentLoop(
                executor = components.executor,
                model = components.model,
                systemPrompt = components.systemPrompt,
                userPrompt = prompt,
                toolExecutor = toolExecutor,
                agentId = agentId,
            )
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            activeAgents.remove(agentId)
        }
    }

    /**
     * The core agent loop:
     * 1. Send prompt to LLM (without native tool definitions)
     * 2. Check response for `<tool_call>` blocks
     * 3. If found: execute tools, format results, add to conversation, repeat
     * 4. If not found: return the final response
     */
    private suspend fun runAgentLoop(
        executor: SingleLLMPromptExecutor,
        model: LLModel,
        systemPrompt: String,
        userPrompt: String,
        toolExecutor: TextBasedToolExecutor,
        agentId: String,
    ): String {
        // Build conversation history as a list of messages
        val conversationMessages = mutableListOf<Pair<String, String>>() // role -> content
        conversationMessages.add("user" to userPrompt)

        var lastResponse = ""

        for (iteration in 1..maxIterations) {
            // Check for cancellation
            if (activeAgents[agentId]?.cancelled == true) {
                return lastResponse.ifEmpty { "[Agent cancelled]" }
            }

            // Build prompt with full conversation history
            val llmPrompt = prompt(id = "workspace-$agentId-iter$iteration") {
                system(systemPrompt)
                for ((role, content) in conversationMessages) {
                    when (role) {
                        "user" -> user(content)
                        "assistant" -> assistant(content)
                    }
                }
            }

            // Execute LLM call WITHOUT tool definitions (text-based approach)
            val responses = executor.execute(llmPrompt, model, tools = emptyList())
            val responseText = responses
                .filterIsInstance<Message.Response>()
                .joinToString("") { it.content }

            lastResponse = responseText

            // Check for tool calls in the response
            val toolCalls = ToolCallExtractor.extractToolCalls(responseText)

            if (toolCalls.isEmpty()) {
                // No tool calls — LLM is done, return the final response
                return responseText
            }

            // Add assistant's response to conversation
            conversationMessages.add("assistant" to responseText)

            // Execute the tool calls
            val results = toolExecutor.executeAll(toolCalls)
            val resultMessage = toolExecutor.formatResults(results)

            // Add tool results as a user message (the LLM will see these)
            conversationMessages.add("user" to resultMessage)
        }

        // Max iterations reached
        return lastResponse.ifEmpty { "[Agent reached max iterations ($maxIterations)]" }
    }

    // ── AgentProvider: Streaming ─────────────────────────────────────────

    override suspend fun runStreaming(
        role: AgentRole,
        agentId: String,
        prompt: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        val components = createLLMComponents()
        val toolExecutor = TextBasedToolExecutor(cwd)

        activeAgents[agentId] = RunningAgent(role)
        onChunk(StreamChunk.Heartbeat())

        return try {
            runStreamingAgentLoop(
                executor = components.executor,
                model = components.model,
                systemPrompt = components.systemPrompt,
                userPrompt = prompt,
                toolExecutor = toolExecutor,
                agentId = agentId,
                onChunk = onChunk,
            )
        } catch (e: Exception) {
            onChunk(StreamChunk.Error(e.message ?: "Unknown error", recoverable = false))
            "Error: ${e.message}"
        } finally {
            activeAgents.remove(agentId)
        }
    }

    /**
     * Streaming version of the agent loop.
     *
     * Uses [ToolCallStreamFilter] to intercept `<tool_call>` XML blocks from
     * the streaming text, preventing raw XML from appearing in the UI. Instead,
     * clean text is emitted as [StreamChunk.Text] and tool calls are emitted as
     * [StreamChunk.ToolCall] with proper status transitions (STARTED → COMPLETED/FAILED).
     *
     * This gives the UI:
     * - Real-time streaming of the agent's thinking/reasoning text
     * - Clean tool call panels (via [ToolCallPanel]) with collapsible details
     * - Tool execution status updates (spinner → checkmark/error)
     * - Multi-iteration support (agent can make multiple rounds of tool calls)
     */
    private suspend fun runStreamingAgentLoop(
        executor: SingleLLMPromptExecutor,
        model: LLModel,
        systemPrompt: String,
        userPrompt: String,
        toolExecutor: TextBasedToolExecutor,
        agentId: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        val conversationMessages = mutableListOf<Pair<String, String>>()
        conversationMessages.add("user" to userPrompt)

        val fullOutput = StringBuilder()

        for (iteration in 1..maxIterations) {
            if (activeAgents[agentId]?.cancelled == true) {
                return fullOutput.toString().ifEmpty { "[Agent cancelled]" }
            }

            val llmPrompt = prompt(id = "workspace-$agentId-iter$iteration") {
                system(systemPrompt)
                for ((role, content) in conversationMessages) {
                    when (role) {
                        "user" -> user(content)
                        "assistant" -> assistant(content)
                    }
                }
            }

            // Use ToolCallStreamFilter to intercept <tool_call> blocks from the stream.
            // Clean text (outside tool call blocks) is emitted as StreamChunk.Text in real-time.
            // Tool call blocks are silently buffered and NOT shown as text.
            val filter = ToolCallStreamFilter()

            executor.executeStreaming(llmPrompt, model, tools = emptyList())
                .cancellable()
                .collect { frame ->
                    when (frame) {
                        is StreamFrame.Append -> {
                            // Feed text through the filter:
                            // - Clean text → emitted as StreamChunk.Text immediately
                            // - <tool_call> blocks → silently intercepted (not shown as text)
                            filter.feed(frame.text) { cleanText ->
                                if (cleanText.isNotEmpty()) {
                                    onChunk(StreamChunk.Text(cleanText))
                                }
                            }
                        }
                        is StreamFrame.End -> {
                            // Flush any remaining buffered text (handles partial tags)
                            filter.flush { remaining ->
                                if (remaining.isNotEmpty()) {
                                    onChunk(StreamChunk.Text(remaining))
                                }
                            }
                        }
                        is StreamFrame.ToolCall -> {
                            // Native tool calls shouldn't happen (we passed empty tools)
                            onChunk(StreamChunk.ToolCall(frame.name, ToolCallStatus.IN_PROGRESS))
                        }
                    }
                }

            // Get the full response (including tool call XML) for conversation history
            val fullResponse = filter.getFullText()
            val cleanResponse = ToolCallExtractor.removeToolCalls(fullResponse)
            fullOutput.append(cleanResponse)

            // Extract all tool calls from the full response
            val allToolCalls = ToolCallExtractor.extractToolCalls(fullResponse)

            if (allToolCalls.isEmpty()) {
                // No tool calls — agent is done
                onChunk(StreamChunk.Completed("end"))
                return fullOutput.toString()
            }

            // Add assistant response to conversation (with tool call XML intact)
            conversationMessages.add("assistant" to fullResponse)

            // Execute tool calls ONE BY ONE with proper STARTED → COMPLETED pairing.
            // The StreamChunkAdapter tracks currentToolCallId as a single value,
            // so we must pair each STARTED with its COMPLETED before the next one.
            val results = mutableListOf<TextBasedToolExecutor.ToolResult>()

            for (call in allToolCalls) {
                // Emit STARTED for this tool call
                onChunk(StreamChunk.ToolCall(
                    name = call.name,
                    status = ToolCallStatus.STARTED,
                    arguments = call.arguments.toString(),
                ))

                // Execute the tool
                val result = toolExecutor.execute(call)
                results.add(result)

                // Emit COMPLETED/FAILED immediately after execution
                val status = if (result.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
                onChunk(StreamChunk.ToolCall(
                    name = result.toolName,
                    status = status,
                    result = result.output.take(500),
                ))
            }

            // Format results and add to conversation for next iteration
            val resultMessage = toolExecutor.formatResults(results)
            conversationMessages.add("user" to resultMessage)

            filter.reset()
        }

        onChunk(StreamChunk.Completed("max_iterations"))
        return fullOutput.toString()
    }

    // ── AgentProvider: Health Check ──────────────────────────────────────

    override fun isHealthy(agentId: String): Boolean {
        val agent = activeAgents[agentId] ?: return true
        return !agent.cancelled
    }

    // ── AgentProvider: Interrupt ─────────────────────────────────────────

    override suspend fun interrupt(agentId: String) {
        activeAgents[agentId]?.cancelled = true
    }

    // ── AgentProvider: Capabilities ──────────────────────────────────────

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        name = "Workspace Agent (Text-Based Tool Calling)",
        supportsStreaming = true,
        supportsInterrupt = true,
        supportsHealthCheck = true,
        supportsFileEditing = true,
        supportsTerminal = false,
        supportsToolCalling = true,
        maxConcurrentAgents = 5,
        priority = 8,
    )

    // ── AgentProvider: Cleanup ───────────────────────────────────────────

    override suspend fun cleanup(agentId: String) {
        activeAgents.remove(agentId)
    }

    override suspend fun shutdown() {
        activeAgents.clear()
    }

    // ── Internal: LLM creation ──────────────────────────────────────────

    private data class LLMComponents(
        val executor: SingleLLMPromptExecutor,
        val model: LLModel,
        val systemPrompt: String,
    )

    private fun createLLMComponents(): LLMComponents {
        val config = modelConfig ?: RoutaConfigLoader.getActiveModelConfig()
            ?: throw IllegalStateException(
                "No active model config found. Please configure ~/.autodev/config.yaml"
            )

        return LLMComponents(
            executor = RoutaAgentFactory.createExecutor(config),
            model = RoutaAgentFactory.createModel(config),
            systemPrompt = buildSystemPrompt(cwd),
        )
    }
}
