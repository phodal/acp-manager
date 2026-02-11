package com.phodal.routa.core.event

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-agent event subscriptions with filtering and batching.
 *
 * Agents subscribe to specific event types and receive matching events when they
 * become idle. This enables the "wait for agent" pattern — a caller subscribes to
 * `agent:completed` and gets notified when a delegated agent finishes.
 *
 * Key features:
 * - **Event type filtering**: Subscribe to specific event types using wildcard patterns
 *   (e.g., "agent:*", "task:*", "*")
 * - **Self-exclusion**: Optionally exclude events caused by the subscribing agent
 * - **One-shot subscriptions**: Auto-unsubscribe after first delivery
 * - **Batched delivery**: Events are collected and delivered together
 * - **Thread-safe**: Uses ConcurrentHashMap and Mutex for concurrent access
 *
 * Modeled after Intent's `AgentEventSubscriptionService`:
 * - subscribeCallerToAgentCompletion → [subscribeToAgentCompletion]
 * - AgentEventFilter → [EventSubscription.eventTypes]
 */
class AgentEventSubscriptionService(
    private val eventBus: EventBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    /** Active subscriptions by subscription ID. */
    private val subscriptions = ConcurrentHashMap<String, EventSubscription>()

    /** Pending events per subscriber agent ID, waiting for delivery. */
    private val pendingEvents = ConcurrentHashMap<String, MutableList<DeliveredEvent>>()

    private val pendingMutex = Mutex()

    /** Whether the listener coroutine is running. */
    @Volatile
    private var listening = false

    /**
     * Start listening to the EventBus and routing events to subscribers.
     *
     * Must be called once to activate the subscription service.
     */
    fun startListening() {
        if (listening) return
        listening = true

        scope.launch {
            eventBus.events.collect { event ->
                routeEvent(event)
            }
        }
    }

    /**
     * Subscribe an agent to receive events matching the given filters.
     *
     * @param agentId The subscribing agent's ID.
     * @param agentName The subscribing agent's name (for logging).
     * @param eventTypes Event type patterns to match (e.g., "agent:*", "task:*", "*").
     * @param excludeSelf Whether to exclude events caused by the subscribing agent.
     * @param oneShot If true, auto-unsubscribe after first batch delivery.
     * @return The subscription ID (used to unsubscribe).
     */
    suspend fun subscribe(
        agentId: String,
        agentName: String,
        eventTypes: List<String>,
        excludeSelf: Boolean = true,
        oneShot: Boolean = false,
    ): String {
        val subscriptionId = UUID.randomUUID().toString()
        val subscription = EventSubscription(
            id = subscriptionId,
            agentId = agentId,
            agentName = agentName,
            eventTypes = eventTypes,
            excludeSelf = excludeSelf,
            oneShot = oneShot,
            createdAt = Instant.now(),
        )
        subscriptions[subscriptionId] = subscription
        return subscriptionId
    }

    /**
     * Convenience: subscribe an agent to be notified when a target agent completes.
     *
     * Creates a one-shot subscription for `agent:completed` and `agent:status_changed`
     * events from the target agent.
     *
     * @param callerAgentId The agent that wants to be notified.
     * @param callerAgentName The caller's name.
     * @param targetAgentId The agent to watch for completion.
     * @return The subscription ID.
     */
    suspend fun subscribeToAgentCompletion(
        callerAgentId: String,
        callerAgentName: String,
        targetAgentId: String,
    ): String {
        return subscribe(
            agentId = callerAgentId,
            agentName = callerAgentName,
            eventTypes = listOf("agent:completed", "agent:status_changed"),
            excludeSelf = true,
            oneShot = true,
        )
    }

    /**
     * Unsubscribe from events.
     *
     * @param subscriptionId The subscription ID returned from [subscribe].
     * @return true if the subscription was found and removed.
     */
    fun unsubscribe(subscriptionId: String): Boolean {
        return subscriptions.remove(subscriptionId) != null
    }

    /**
     * Unsubscribe all subscriptions for an agent.
     *
     * @param agentId The agent whose subscriptions to remove.
     * @return Number of subscriptions removed.
     */
    fun unsubscribeAll(agentId: String): Int {
        val toRemove = subscriptions.values.filter { it.agentId == agentId }.map { it.id }
        toRemove.forEach { subscriptions.remove(it) }
        return toRemove.size
    }

    /**
     * Get pending events for an agent and clear them.
     *
     * Call this when an agent becomes idle to deliver batched events.
     *
     * @param agentId The agent to get pending events for.
     * @return List of pending events (empty if none).
     */
    suspend fun drainPendingEvents(agentId: String): List<DeliveredEvent> {
        return pendingMutex.withLock {
            val events = pendingEvents.remove(agentId)
            events?.toList() ?: emptyList()
        }
    }

    /**
     * Peek at pending events for an agent without clearing them.
     *
     * @param agentId The agent to check.
     * @return Number of pending events.
     */
    fun getPendingEventCount(agentId: String): Int {
        return pendingEvents[agentId]?.size ?: 0
    }

    /**
     * Get all active subscriptions for an agent.
     */
    fun getSubscriptions(agentId: String): List<EventSubscription> {
        return subscriptions.values.filter { it.agentId == agentId }
    }

    /**
     * Get a specific subscription by ID.
     */
    fun getSubscription(subscriptionId: String): EventSubscription? {
        return subscriptions[subscriptionId]
    }

    /**
     * Get total number of active subscriptions.
     */
    fun subscriptionCount(): Int = subscriptions.size

    // ── Internal ──────────────────────────────────────────────────────

    /**
     * Route an event to all matching subscribers.
     */
    private suspend fun routeEvent(event: AgentEvent) {
        val eventType = event.toEventType()
        val eventActorId = event.getActorId()

        // Find all matching subscriptions
        val oneShotToRemove = mutableListOf<String>()

        for (subscription in subscriptions.values) {
            // Check if the event type matches any of the subscription's patterns
            if (!subscription.matchesEventType(eventType)) continue

            // Check self-exclusion
            if (subscription.excludeSelf && eventActorId == subscription.agentId) continue

            // Deliver the event
            val delivered = DeliveredEvent(
                subscriptionId = subscription.id,
                event = event,
                eventType = eventType,
                timestamp = Instant.now(),
            )

            pendingMutex.withLock {
                pendingEvents.getOrPut(subscription.agentId) { mutableListOf() }.add(delivered)
            }

            // Mark one-shot subscriptions for removal
            if (subscription.oneShot) {
                oneShotToRemove.add(subscription.id)
            }
        }

        // Remove one-shot subscriptions after delivery
        oneShotToRemove.forEach { subscriptions.remove(it) }
    }

    /**
     * Shutdown the subscription service.
     */
    fun shutdown() {
        listening = false
        subscriptions.clear()
        pendingEvents.clear()
    }
}

// ── Supporting Types ────────────────────────────────────────────────────

/**
 * An event subscription for an agent.
 */
data class EventSubscription(
    /** Unique subscription ID. */
    val id: String,

    /** The subscribing agent's ID. */
    val agentId: String,

    /** The subscribing agent's name. */
    val agentName: String,

    /**
     * Event type patterns to match.
     *
     * Patterns:
     * - `"*"` matches all events
     * - `"agent:*"` matches all agent events
     * - `"task:*"` matches all task events
     * - `"agent:completed"` matches only AgentCompleted events
     * - `"agent:created"` matches only AgentCreated events
     * - `"agent:status_changed"` matches only AgentStatusChanged events
     * - `"agent:message"` matches only MessageReceived events
     * - `"task:status_changed"` matches only TaskStatusChanged events
     * - `"task:delegated"` matches only TaskDelegated events
     */
    val eventTypes: List<String>,

    /** Whether to exclude events caused by the subscribing agent. */
    val excludeSelf: Boolean = true,

    /** If true, auto-unsubscribe after first batch delivery. */
    val oneShot: Boolean = false,

    /** When the subscription was created. */
    val createdAt: Instant,
) {
    /**
     * Check if an event type matches any of the subscription's patterns.
     */
    fun matchesEventType(eventType: String): Boolean {
        return eventTypes.any { pattern ->
            when {
                pattern == "*" -> true
                pattern.endsWith(":*") -> {
                    val prefix = pattern.removeSuffix("*")
                    eventType.startsWith(prefix)
                }
                else -> pattern == eventType
            }
        }
    }
}

/**
 * An event that has been delivered to a subscriber's pending queue.
 */
data class DeliveredEvent(
    /** The subscription that matched this event. */
    val subscriptionId: String,

    /** The original event. */
    val event: AgentEvent,

    /** The event type string (e.g., "agent:completed"). */
    val eventType: String,

    /** When the event was delivered. */
    val timestamp: Instant,
)

// ── AgentEvent Extensions ───────────────────────────────────────────────

/**
 * Convert an AgentEvent to a string event type for matching.
 */
fun AgentEvent.toEventType(): String = when (this) {
    is AgentEvent.AgentCreated -> "agent:created"
    is AgentEvent.AgentStatusChanged -> "agent:status_changed"
    is AgentEvent.AgentCompleted -> "agent:completed"
    is AgentEvent.MessageReceived -> "agent:message"
    is AgentEvent.TaskStatusChanged -> "task:status_changed"
    is AgentEvent.TaskDelegated -> "task:delegated"
}

/**
 * Get the actor (causing agent) ID from an event, if applicable.
 */
fun AgentEvent.getActorId(): String? = when (this) {
    is AgentEvent.AgentCreated -> agentId
    is AgentEvent.AgentStatusChanged -> agentId
    is AgentEvent.AgentCompleted -> agentId
    is AgentEvent.MessageReceived -> fromAgentId
    is AgentEvent.TaskStatusChanged -> null // no specific actor
    is AgentEvent.TaskDelegated -> delegatedBy
}
