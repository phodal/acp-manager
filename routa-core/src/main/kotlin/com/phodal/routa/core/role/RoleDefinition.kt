package com.phodal.routa.core.role

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.ModelTier

/**
 * Complete definition of an agent role, including its system prompt and behavioral rules.
 *
 * These definitions tell the LLM how to behave when operating as each role.
 * Adapted from the Intent by Augment multi-agent architecture.
 */
data class RoleDefinition(
    /** The role enum value. */
    val role: AgentRole,

    /** Display name shown to users. */
    val displayName: String,

    /** Short description of the role. */
    val description: String,

    /** Default model tier for this role. */
    val defaultModelTier: ModelTier,

    /** The full system/behavior prompt injected into the LLM. */
    val systemPrompt: String,

    /** Short reminder appended to each turn to reinforce key rules. */
    val roleReminder: String,
)
