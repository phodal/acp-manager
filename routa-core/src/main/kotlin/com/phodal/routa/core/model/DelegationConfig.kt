package com.phodal.routa.core.model

import kotlinx.serialization.Serializable

/**
 * How the delegating agent should wait for delegated tasks.
 */
@Serializable
enum class WaitMode {
    /** Wait for ALL delegated agents in the group to complete. */
    AFTER_ALL,

    /** Resume as soon as ANY delegated agent completes. */
    AFTER_ANY;
}

/**
 * Configuration for a delegation request from Routa to a child agent.
 */
@Serializable
data class DelegationRequest(
    /** The task to delegate. */
    val taskId: String,

    /** The role of the agent to create for this task. */
    val role: AgentRole,

    /** Optional: specific model tier override. */
    val modelTier: ModelTier? = null,

    /** Optional: custom agent name. */
    val agentName: String? = null,

    /** How to wait for completion. */
    val waitMode: WaitMode = WaitMode.AFTER_ALL,
)

/**
 * Report sent by a child agent back to its parent (Routa).
 */
@Serializable
data class CompletionReport(
    /** The agent sending the report. */
    val agentId: String,

    /** The task this report is for. */
    val taskId: String,

    /** 1-3 sentence summary: what was done, verification run, risks/follow-ups. */
    val summary: String,

    /** Files that were modified. */
    val filesModified: List<String> = emptyList(),

    /** Verification commands that were run and their results. */
    val verificationResults: Map<String, String> = emptyMap(),

    /** Whether the agent considers the task successful. */
    val success: Boolean = true,
)
