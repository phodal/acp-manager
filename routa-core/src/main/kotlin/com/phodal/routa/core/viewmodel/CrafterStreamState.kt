package com.phodal.routa.core.viewmodel

import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.StreamChunk

/**
 * Data class representing a CRAFTER agent's streaming state.
 *
 * Platform-agnostic representation used by [RoutaViewModel] to track
 * each CRAFTER's progress during orchestration. Both CLI and IDE UIs
 * can observe this state via [RoutaViewModel.crafterStates].
 */
data class CrafterStreamState(
    /** The CRAFTER agent's ID. */
    val agentId: String,

    /** The task ID assigned to this CRAFTER. */
    val taskId: String,

    /** Human-readable task title. */
    val taskTitle: String = "",

    /** Current agent status. */
    val status: AgentStatus = AgentStatus.PENDING,

    /** Accumulated stream chunks from this CRAFTER. */
    val chunks: List<StreamChunk> = emptyList(),

    /** Accumulated text output from this CRAFTER. */
    val outputText: String = "",
)
