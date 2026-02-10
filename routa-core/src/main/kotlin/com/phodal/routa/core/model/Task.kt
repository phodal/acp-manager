package com.phodal.routa.core.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle status of a task.
 */
@Serializable
enum class TaskStatus {
    /** Task is defined but not yet started. */
    PENDING,

    /** Task is currently being worked on by an agent. */
    IN_PROGRESS,

    /** Task implementation is done, awaiting verification. */
    REVIEW_REQUIRED,

    /** Task has been verified and approved by a Gate agent. */
    COMPLETED,

    /** Task is verified but not approved — needs fixes. */
    NEEDS_FIX,

    /** Task is blocked by dependencies or conflicts. */
    BLOCKED,

    /** Task was cancelled. */
    CANCELLED;
}

/**
 * The verdict from a Gate (verifier) agent.
 */
@Serializable
enum class VerificationVerdict {
    /** All acceptance criteria passed. */
    APPROVED,

    /** One or more acceptance criteria failed. */
    NOT_APPROVED,

    /** Verification could not be completed (e.g., tests can't run). */
    BLOCKED;
}

/**
 * Represents a unit of work in the multi-agent system.
 *
 * Tasks are created by the Routa (coordinator) and assigned to Crafter (implementor)
 * agents. After implementation, they are verified by Gate (verifier) agents.
 *
 * Uses the `@@@task` block format from the coordinator's planning output.
 */
@Serializable
data class Task(
    /** Unique identifier. */
    val id: String,

    /** Human-readable title. */
    val title: String,

    /** Clear statement of what needs to be done. */
    val objective: String,

    /** Specific files/components to modify — what's in scope and out of scope. */
    val scope: List<String> = emptyList(),

    /** Acceptance criteria — the definition of done. */
    val acceptanceCriteria: List<String> = emptyList(),

    /** Verification commands to run after implementation. */
    val verificationCommands: List<String> = emptyList(),

    /** The agent currently assigned to this task. */
    val assignedTo: String? = null,

    /** Current lifecycle status. */
    val status: TaskStatus = TaskStatus.PENDING,

    /** IDs of tasks that must be completed before this one. */
    val dependencies: List<String> = emptyList(),

    /** The wave/parallel group this task belongs to. */
    val parallelGroup: Int? = null,

    /** The workspace this task belongs to. */
    val workspaceId: String,

    /** ISO-8601 timestamp of creation. */
    val createdAt: String,

    /** ISO-8601 timestamp of last update. */
    val updatedAt: String,

    /** Summary of what was done (filled by Crafter on completion). */
    val completionSummary: String? = null,

    /** Verification result (filled by Gate on verification). */
    val verificationVerdict: VerificationVerdict? = null,

    /** Verification report (filled by Gate on verification). */
    val verificationReport: String? = null,
)
