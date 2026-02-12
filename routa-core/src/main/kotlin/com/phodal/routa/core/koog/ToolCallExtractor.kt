package com.phodal.routa.core.koog

import kotlinx.serialization.json.*

/**
 * Extracts tool calls from agent LLM text responses.
 *
 * Supports multiple formats:
 *
 * ### Format 1: JSON inside XML tags (preferred)
 * ```xml
 * <tool_call>
 * {"name": "read_file", "arguments": {"path": "src/main.kt"}}
 * </tool_call>
 * ```
 *
 * ### Format 2: Pure XML (Claude-style)
 * ```xml
 * <tool_call>
 * <name>read_file</name>
 * <arguments>
 * <path>src/main.kt</path>
 * </arguments>
 * </tool_call>
 * ```
 *
 * ### Format 3: Markdown JSON blocks
 * ```json
 * {"name": "read_file", "arguments": {"path": "src/main.kt"}}
 * ```
 *
 * Inspired by Intent's `tool-call-extractor.ts`.
 */
object ToolCallExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A parsed tool call from an LLM response.
     */
    data class ToolCall(
        val name: String,
        val arguments: Map<String, String>,
    )

    /**
     * Extract all tool calls from the given response text.
     *
     * Tries all supported formats in order:
     * 1. XML `<tool_call>` tags (with JSON or pure XML content)
     * 2. Markdown JSON code blocks
     */
    fun extractToolCalls(response: String): List<ToolCall> {
        if (response.isBlank()) return emptyList()

        val toolCalls = mutableListOf<ToolCall>()

        // 1. Try XML format: <tool_call>...</tool_call>
        val xmlRegex = Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""")
        for (match in xmlRegex.findAll(response)) {
            val inner = match.groupValues[1].trim()
            val parsed = parseToolCallContent(inner)
            if (parsed != null) {
                toolCalls.add(parsed)
            }
        }

        if (toolCalls.isNotEmpty()) return toolCalls

        // 2. Try markdown code block format: ```json {...} ```
        val markdownRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        for (match in markdownRegex.findAll(response)) {
            val content = match.groupValues[1].trim()
            val parsed = parseToolCallJson(content)
            if (parsed != null && !toolCalls.any { it.name == parsed.name }) {
                toolCalls.add(parsed)
            }
        }

        return toolCalls
    }

    /**
     * Check if a response contains any tool calls.
     */
    fun hasToolCalls(response: String): Boolean {
        return response.contains("<tool_call>") ||
            extractToolCalls(response).isNotEmpty()
    }

    /**
     * Remove tool call blocks from response text for clean display.
     */
    fun removeToolCalls(response: String): String {
        var cleaned = response
        cleaned = cleaned.replace(Regex("""<tool_call>\s*[\s\S]*?\s*</tool_call>"""), "")
        return cleaned.trim()
    }

    // ── Parsing ─────────────────────────────────────────────────────────

    /**
     * Parse the content inside a `<tool_call>` block.
     *
     * Tries JSON first, then falls back to pure XML parsing.
     */
    private fun parseToolCallContent(content: String): ToolCall? {
        // Try JSON format first
        val jsonResult = parseToolCallJson(content)
        if (jsonResult != null) return jsonResult

        // Try pure XML format:
        // <name>tool_name</name>
        // <arguments>
        //   <key>value</key>
        // </arguments>
        val xmlResult = parseToolCallXml(content)
        if (xmlResult != null) return xmlResult

        return null
    }

    /**
     * Parse JSON format: `{"name": "tool_name", "arguments": {"key": "value"}}`
     */
    private fun parseToolCallJson(jsonStr: String): ToolCall? {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            if (element !is JsonObject) return null

            val name = element["name"]?.jsonPrimitive?.content ?: return null
            val argsElement = element["arguments"]
            if (argsElement == null || argsElement !is JsonObject) return null

            val arguments = mutableMapOf<String, String>()
            for ((key, value) in argsElement) {
                arguments[key] = when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }

            ToolCall(name = name, arguments = arguments)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse pure XML format (Claude-style):
     * ```xml
     * <name>tool_name</name>
     * <arguments>
     *   <key>value</key>
     * </arguments>
     * ```
     *
     * Also handles the variant without `<arguments>` wrapper:
     * ```xml
     * <name>tool_name</name>
     * <key>value</key>
     * ```
     */
    private fun parseToolCallXml(content: String): ToolCall? {
        return try {
            // Extract <name>...</name>
            val nameRegex = Regex("""<name>\s*(.*?)\s*</name>""")
            val nameMatch = nameRegex.find(content) ?: return null
            val name = nameMatch.groupValues[1].trim()
            if (name.isEmpty()) return null

            val arguments = mutableMapOf<String, String>()

            // Try to find <arguments>...</arguments> block
            val argsBlockRegex = Regex("""<arguments>\s*([\s\S]*?)\s*</arguments>""")
            val argsBlockMatch = argsBlockRegex.find(content)

            val argsContent = if (argsBlockMatch != null) {
                argsBlockMatch.groupValues[1]
            } else {
                // No <arguments> block — look for XML tags after <name> in the whole content
                content.substringAfter("</name>").trim()
            }

            // Extract all <key>value</key> pairs from the arguments content
            val paramRegex = Regex("""<(\w+)>\s*([\s\S]*?)\s*</\1>""")
            for (paramMatch in paramRegex.findAll(argsContent)) {
                val key = paramMatch.groupValues[1]
                val value = paramMatch.groupValues[2].trim()
                // Skip nested tags like <name> (already captured)
                if (key != "name" && key != "arguments") {
                    arguments[key] = value
                }
            }

            ToolCall(name = name, arguments = arguments)
        } catch (e: Exception) {
            null
        }
    }
}
