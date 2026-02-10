package com.phodal.routa.core

import com.phodal.routa.core.coordinator.AgentExecutionContext
import com.phodal.routa.core.coordinator.RoutaCoordinator
import com.phodal.routa.core.event.EventBus
import com.phodal.routa.core.store.*
import kotlinx.coroutines.CoroutineScope

/**
 * Factory for creating a fully wired Routa multi-agent system.
 *
 * Provides default in-memory implementations that work out of the box.
 * For production use, replace stores with persistent implementations.
 *
 * ## Usage
 * ```kotlin
 * val routa = RoutaFactory.createInMemory()
 * val coordinator = routa.coordinator
 *
 * // Initialize a coordination session
 * val routaAgentId = coordinator.initialize("my-workspace")
 *
 * // ... feed user input to the Routa agent via ACP/Koog ...
 * // ... parse @@@task blocks from Routa's output ...
 *
 * coordinator.registerTasks(routaOutput)
 * coordinator.executeNextWave()
 * ```
 */
object RoutaFactory {

    /**
     * Create a Routa system with in-memory stores.
     *
     * Suitable for testing and single-process scenarios.
     */
    fun createInMemory(scope: CoroutineScope? = null): RoutaSystem {
        val eventBus = EventBus()
        val context = AgentExecutionContext(
            agentStore = InMemoryAgentStore(),
            conversationStore = InMemoryConversationStore(),
            taskStore = InMemoryTaskStore(),
            eventBus = eventBus,
        )
        val coordinator = if (scope != null) {
            RoutaCoordinator(context, scope)
        } else {
            RoutaCoordinator(context)
        }
        return RoutaSystem(context, coordinator)
    }

    /**
     * Create a Routa system with custom stores.
     *
     * Use this for production deployments with file-based or database storage.
     */
    fun create(
        agentStore: AgentStore,
        conversationStore: ConversationStore,
        taskStore: TaskStore,
        scope: CoroutineScope? = null,
    ): RoutaSystem {
        val eventBus = EventBus()
        val context = AgentExecutionContext(
            agentStore = agentStore,
            conversationStore = conversationStore,
            taskStore = taskStore,
            eventBus = eventBus,
        )
        val coordinator = if (scope != null) {
            RoutaCoordinator(context, scope)
        } else {
            RoutaCoordinator(context)
        }
        return RoutaSystem(context, coordinator)
    }
}

/**
 * A fully wired Routa multi-agent system.
 */
data class RoutaSystem(
    /** The execution context containing all stores and tools. */
    val context: AgentExecutionContext,

    /** The main coordinator that orchestrates the Routa→Crafter→Gate workflow. */
    val coordinator: RoutaCoordinator,
) {
    /** Shortcut to the agent tools. */
    val tools get() = context.agentTools

    /** Shortcut to the event bus. */
    val eventBus get() = context.eventBus
}
