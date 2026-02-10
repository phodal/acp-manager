package com.phodal.routa.core.coordinator

import com.phodal.routa.core.model.Task
import com.phodal.routa.core.model.TaskStatus
import java.time.Instant
import java.util.UUID

/**
 * Parses `@@@task` blocks from Routa's planning output into [Task] objects.
 *
 * The `@@@task` block format:
 * ```
 * @@@task
 * # Task Title
 *
 * ## Objective
 * Clear statement
 *
 * ## Scope
 * - file1.kt
 * - file2.kt
 *
 * ## Definition of Done
 * - Acceptance criteria 1
 * - Acceptance criteria 2
 *
 * ## Verification
 * - ./gradlew test
 * @@@
 * ```
 */
object TaskParser {

    private val TASK_BLOCK_REGEX = Regex(
        """@@@task\s*\n(.*?)@@@""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Parse all `@@@task` blocks from the given text.
     *
     * @param text The Routa output containing task blocks.
     * @param workspaceId The workspace these tasks belong to.
     * @return List of parsed tasks.
     */
    fun parse(text: String, workspaceId: String): List<Task> {
        return TASK_BLOCK_REGEX.findAll(text).map { match ->
            parseTaskBlock(match.groupValues[1].trim(), workspaceId)
        }.toList()
    }

    private fun parseTaskBlock(block: String, workspaceId: String): Task {
        val lines = block.lines()

        val title = lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()
            ?: "Untitled Task"

        val objective = extractSection(lines, "Objective")
        val scope = extractListSection(lines, "Scope")
        val acceptanceCriteria = extractListSection(lines, "Definition of Done")
        val verificationCommands = extractListSection(lines, "Verification")

        val now = Instant.now().toString()
        return Task(
            id = UUID.randomUUID().toString(),
            title = title,
            objective = objective,
            scope = scope,
            acceptanceCriteria = acceptanceCriteria,
            verificationCommands = verificationCommands,
            status = TaskStatus.PENDING,
            workspaceId = workspaceId,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Extract a text section between `## SectionName` and the next `##` or end.
     */
    private fun extractSection(lines: List<String>, sectionName: String): String {
        val startIdx = lines.indexOfFirst { it.trim().startsWith("## $sectionName") }
        if (startIdx == -1) return ""

        val contentLines = mutableListOf<String>()
        for (i in (startIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("## ")) break
            contentLines.add(line)
        }
        return contentLines.joinToString("\n").trim()
    }

    /**
     * Extract list items (lines starting with `-`) from a section.
     */
    private fun extractListSection(lines: List<String>, sectionName: String): List<String> {
        val section = extractSection(lines, sectionName)
        return section.lines()
            .filter { it.trim().startsWith("-") }
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
    }
}
