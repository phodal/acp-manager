package com.phodal.routa.core.coordinator

import com.phodal.routa.core.event.EventBus
import com.phodal.routa.core.store.AgentStore
import com.phodal.routa.core.store.ConversationStore
import com.phodal.routa.core.store.TaskStore
import com.phodal.routa.core.tool.AgentTools

/**
 * Dependency container for the multi-agent system.
 *
 * Groups all stores, tools, and the event bus into a single context object
 * that can be passed to the coordinator and other components.
 *
 * This is the primary extension point for platform-specific implementations:
 * - IntelliJ plugin: can provide file-based stores
 * - CLI tool: can provide in-memory stores
 * - Server: can provide database-backed stores
 */
data class AgentExecutionContext(
    val agentStore: AgentStore,
    val conversationStore: ConversationStore,
    val taskStore: TaskStore,
    val eventBus: EventBus,
) {
    /** Lazily created agent tools wired to this context's stores. */
    val agentTools: AgentTools by lazy {
        AgentTools(agentStore, conversationStore, taskStore, eventBus)
    }
}
