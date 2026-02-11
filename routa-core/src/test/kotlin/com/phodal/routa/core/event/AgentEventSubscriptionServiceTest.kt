package com.phodal.routa.core.event

import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.model.CompletionReport
import com.phodal.routa.core.model.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [AgentEventSubscriptionService].
 */
class AgentEventSubscriptionServiceTest {

    @Test
    fun `subscribe creates a subscription`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)

        val subId = service.subscribe(
            agentId = "agent-1",
            agentName = "Test Agent",
            eventTypes = listOf("agent:*"),
        )

        assertNotNull("Subscription ID should not be null", subId)
        assertEquals("Should have 1 subscription", 1, service.subscriptionCount())

        val sub = service.getSubscription(subId)
        assertNotNull("Subscription should be retrievable", sub)
        assertEquals("agent-1", sub!!.agentId)
        assertEquals(listOf("agent:*"), sub.eventTypes)
    }

    @Test
    fun `unsubscribe removes a subscription`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)

        val subId = service.subscribe(
            agentId = "agent-1",
            agentName = "Test Agent",
            eventTypes = listOf("*"),
        )
        assertEquals(1, service.subscriptionCount())

        val removed = service.unsubscribe(subId)
        assertTrue("Should return true for valid unsubscribe", removed)
        assertEquals("Should have 0 subscriptions", 0, service.subscriptionCount())
    }

    @Test
    fun `unsubscribe returns false for unknown ID`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)

        val removed = service.unsubscribe("nonexistent")
        assertFalse("Should return false for unknown subscription", removed)
    }

    @Test
    fun `events are routed to matching subscribers`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)
        service.startListening()

        // Subscribe agent-1 to agent events
        service.subscribe(
            agentId = "agent-1",
            agentName = "Agent 1",
            eventTypes = listOf("agent:*"),
            excludeSelf = false,
        )

        // Emit an agent event
        bus.emit(AgentEvent.AgentCreated("agent-2", "ws-1", null))
        delay(100) // Allow event to propagate

        val pending = service.drainPendingEvents("agent-1")
        assertEquals("Should have 1 pending event", 1, pending.size)
        assertEquals("agent:created", pending[0].eventType)

        service.shutdown()
    }

    @Test
    fun `self-exclusion works`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)
        service.startListening()

        // Subscribe agent-1 with excludeSelf=true
        service.subscribe(
            agentId = "agent-1",
            agentName = "Agent 1",
            eventTypes = listOf("agent:*"),
            excludeSelf = true,
        )

        // Emit an event caused by agent-1 (self)
        bus.emit(AgentEvent.AgentStatusChanged("agent-1", AgentStatus.PENDING, AgentStatus.ACTIVE))
        delay(100)

        val pending = service.drainPendingEvents("agent-1")
        assertEquals("Self-caused events should be excluded", 0, pending.size)

        // Emit an event caused by agent-2 (not self)
        bus.emit(AgentEvent.AgentCreated("agent-2", "ws-1", "agent-1"))
        delay(100)

        val pending2 = service.drainPendingEvents("agent-1")
        assertEquals("Other's events should be delivered", 1, pending2.size)

        service.shutdown()
    }

    @Test
    fun `wildcard pattern matches all events`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)
        service.startListening()

        service.subscribe(
            agentId = "agent-1",
            agentName = "Agent 1",
            eventTypes = listOf("*"),
            excludeSelf = false,
        )

        bus.emit(AgentEvent.AgentCreated("agent-2", "ws-1", null))
        bus.emit(AgentEvent.TaskDelegated("t1", "agent-2", "agent-1"))
        bus.emit(AgentEvent.MessageReceived("agent-2", "agent-1", "hello"))
        delay(100)

        val pending = service.drainPendingEvents("agent-1")
        assertEquals("Wildcard should match all 3 events", 3, pending.size)

        service.shutdown()
    }

    @Test
    fun `prefix pattern matches category events`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)
        service.startListening()

        service.subscribe(
            agentId = "agent-1",
            agentName = "Agent 1",
            eventTypes = listOf("task:*"),
            excludeSelf = false,
        )

        bus.emit(AgentEvent.AgentCreated("agent-2", "ws-1", null))  // agent event — should NOT match
        bus.emit(AgentEvent.TaskDelegated("t1", "agent-2", "agent-1"))  // task event — should match
        bus.emit(AgentEvent.TaskStatusChanged("t1", TaskStatus.PENDING, TaskStatus.IN_PROGRESS))  // task event — should match
        delay(100)

        val pending = service.drainPendingEvents("agent-1")
        assertEquals("task:* should match 2 task events only", 2, pending.size)

        service.shutdown()
    }

    @Test
    fun `one-shot subscription auto-unsubscribes after delivery`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)
        service.startListening()

        val subId = service.subscribe(
            agentId = "agent-1",
            agentName = "Agent 1",
            eventTypes = listOf("agent:completed"),
            excludeSelf = false,
            oneShot = true,
        )
        assertEquals(1, service.subscriptionCount())

        // First event — should match and auto-unsubscribe
        val report = CompletionReport(agentId = "agent-2", taskId = "t1", summary = "done")
        bus.emit(AgentEvent.AgentCompleted("agent-2", "agent-1", report))
        delay(100)

        assertEquals("One-shot subscription should be removed", 0, service.subscriptionCount())

        // Second event — should NOT be delivered
        bus.emit(AgentEvent.AgentCompleted("agent-3", "agent-1", report))
        delay(100)

        val pending = service.drainPendingEvents("agent-1")
        assertEquals("Only first event should be delivered", 1, pending.size)

        service.shutdown()
    }

    @Test
    fun `drainPendingEvents clears the queue`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)
        service.startListening()

        service.subscribe(
            agentId = "agent-1",
            agentName = "Agent 1",
            eventTypes = listOf("*"),
            excludeSelf = false,
        )

        bus.emit(AgentEvent.AgentCreated("agent-2", "ws-1", null))
        delay(100)

        val first = service.drainPendingEvents("agent-1")
        assertEquals("First drain should return 1 event", 1, first.size)

        val second = service.drainPendingEvents("agent-1")
        assertEquals("Second drain should return 0 events (already drained)", 0, second.size)

        service.shutdown()
    }

    @Test
    fun `unsubscribeAll removes all subscriptions for agent`() = runBlocking {
        val bus = EventBus()
        val service = AgentEventSubscriptionService(bus)

        service.subscribe("agent-1", "Agent 1", listOf("agent:*"))
        service.subscribe("agent-1", "Agent 1", listOf("task:*"))
        service.subscribe("agent-2", "Agent 2", listOf("*"))

        assertEquals(3, service.subscriptionCount())

        val removed = service.unsubscribeAll("agent-1")
        assertEquals("Should remove 2 subscriptions", 2, removed)
        assertEquals("Should have 1 remaining", 1, service.subscriptionCount())
    }

    @Test
    fun `EventSubscription matchesEventType works correctly`() {
        val sub = EventSubscription(
            id = "sub-1",
            agentId = "a1",
            agentName = "Agent 1",
            eventTypes = listOf("agent:*", "task:delegated"),
            createdAt = java.time.Instant.now(),
        )

        assertTrue("agent:created should match agent:*", sub.matchesEventType("agent:created"))
        assertTrue("agent:completed should match agent:*", sub.matchesEventType("agent:completed"))
        assertTrue("task:delegated should match exactly", sub.matchesEventType("task:delegated"))
        assertFalse("task:status_changed should NOT match", sub.matchesEventType("task:status_changed"))
    }

    @Test
    fun `toEventType maps correctly`() {
        assertEquals("agent:created", AgentEvent.AgentCreated("a1", "ws1", null).toEventType())
        assertEquals("agent:status_changed", AgentEvent.AgentStatusChanged("a1", AgentStatus.PENDING, AgentStatus.ACTIVE).toEventType())
        assertEquals("agent:completed", AgentEvent.AgentCompleted("a1", "a0", CompletionReport("a1", "t1", "done")).toEventType())
        assertEquals("agent:message", AgentEvent.MessageReceived("a1", "a2", "hi").toEventType())
        assertEquals("task:status_changed", AgentEvent.TaskStatusChanged("t1", TaskStatus.PENDING, TaskStatus.IN_PROGRESS).toEventType())
        assertEquals("task:delegated", AgentEvent.TaskDelegated("t1", "a1", "a0").toEventType())
    }
}
