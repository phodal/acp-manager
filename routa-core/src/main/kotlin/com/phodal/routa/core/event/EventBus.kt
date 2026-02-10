package com.phodal.routa.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Simple event bus for agent-to-agent communication.
 *
 * Uses Kotlin's [SharedFlow] for multi-subscriber event distribution.
 * All subscribers receive all events; filtering is done by the subscriber.
 *
 * Thread-safe and suitable for cross-coroutine communication.
 */
class EventBus {

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    /** Subscribe to all events. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * Emit an event to all subscribers.
     */
    suspend fun emit(event: AgentEvent) {
        _events.emit(event)
    }

    /**
     * Try to emit an event without suspending.
     * @return true if the event was emitted, false if the buffer is full.
     */
    fun tryEmit(event: AgentEvent): Boolean {
        return _events.tryEmit(event)
    }
}
