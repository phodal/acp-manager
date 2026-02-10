package com.phodal.routa.core.model

import kotlinx.serialization.Serializable

/**
 * The three specialized roles in the Routa multi-agent system.
 *
 * - **Routa** (Coordinator): Plans tasks, delegates work, never edits files directly.
 * - **Crafter** (Implementor): Executes tasks, writes code, avoids conflicts.
 * - **Gate** (Verifier): Verifies completeness, checks quality, outputs reports.
 */
@Serializable
enum class AgentRole(val displayName: String, val defaultModelTier: ModelTier) {
    /** Coordinator — plans, delegates, and verifies. Never edits files directly. */
    ROUTA("Routa", ModelTier.SMART),

    /** Implementor — executes tasks, writes code, follows existing patterns. */
    CRAFTER("Crafter", ModelTier.FAST),

    /** Verifier — reviews work, verifies acceptance criteria, outputs verdict. */
    GATE("Gate", ModelTier.SMART);
}

/**
 * Model tier determines which LLM quality level an agent uses.
 */
@Serializable
enum class ModelTier {
    /** High-quality model for planning and verification (e.g., GPT-4o, Claude Sonnet). */
    SMART,

    /** Fast model for implementation tasks (e.g., GPT-4o-mini, Claude Haiku). */
    FAST;
}

/**
 * Lifecycle status of an Agent instance.
 */
@Serializable
enum class AgentStatus {
    /** Agent has been created but not yet started. */
    PENDING,

    /** Agent is actively working on a task. */
    ACTIVE,

    /** Agent has completed its work successfully. */
    COMPLETED,

    /** Agent encountered an error. */
    ERROR,

    /** Agent was cancelled before completion. */
    CANCELLED;
}

/**
 * Represents a single agent instance in the multi-agent system.
 *
 * Each agent has a specific [role], belongs to a [workspaceId],
 * and may have a [parentId] linking it to the agent that created it.
 */
@Serializable
data class Agent(
    /** Unique identifier for this agent. */
    val id: String,

    /** Human-readable name (e.g., "routa-main", "crafter-auth-module"). */
    val name: String,

    /** The role this agent plays in the system. */
    val role: AgentRole,

    /** The model tier this agent uses. */
    val modelTier: ModelTier = role.defaultModelTier,

    /** Workspace this agent belongs to. */
    val workspaceId: String,

    /** The parent agent that created this one (null for top-level agents). */
    val parentId: String? = null,

    /** Current lifecycle status. */
    val status: AgentStatus = AgentStatus.PENDING,

    /** ISO-8601 timestamp of creation. */
    val createdAt: String,

    /** ISO-8601 timestamp of last update. */
    val updatedAt: String,

    /** Optional metadata (e.g., assigned task ID, custom config). */
    val metadata: Map<String, String> = emptyMap(),
)
