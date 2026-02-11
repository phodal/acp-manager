package com.github.phodal.acpmanager.claudecode.handlers

import com.github.phodal.acpmanager.claudecode.context.RenderContext
import com.github.phodal.acpmanager.claudecode.panels.CompactStreamingPanel
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import kotlin.reflect.KClass

/**
 * Handler for message-related events in CRAFTER panels.
 * 
 * Unlike [MessageEventHandler], this handler does NOT show the "Assistant" header
 * to save space in the compact CRAFTER view.
 */
class CrafterMessageEventHandler : MultiEventHandler() {

    override val supportedEvents: Set<KClass<out RenderEvent>> = setOf(
        RenderEvent.UserMessage::class,
        RenderEvent.MessageStart::class,
        RenderEvent.MessageChunk::class,
        RenderEvent.MessageEnd::class
    )

    override fun handle(event: RenderEvent, context: RenderContext) {
        when (event) {
            is RenderEvent.UserMessage -> { /* Skip user messages in CRAFTER view */ }
            is RenderEvent.MessageStart -> handleStart(context)
            is RenderEvent.MessageChunk -> handleChunk(event, context)
            is RenderEvent.MessageEnd -> handleEnd(event, context)
            else -> {}
        }
    }

    private fun handleStart(context: RenderContext) {
        context.messageBuffer.clear()

        // Use compact panel without header
        val panel = CompactStreamingPanel(context.colors.messageFg)
        context.currentMessagePanel = panel
        context.addPanel(panel.component)
        context.scrollToBottom()
    }

    private fun handleChunk(event: RenderEvent.MessageChunk, context: RenderContext) {
        context.messageBuffer.append(event.content)
        (context.currentMessagePanel as? CompactStreamingPanel)?.updateContent(
            context.messageBuffer.toString()
        )
        context.scrollToBottom()
    }

    private fun handleEnd(event: RenderEvent.MessageEnd, context: RenderContext) {
        (context.currentMessagePanel as? CompactStreamingPanel)?.finalize(event.fullContent)
        context.currentMessagePanel = null
        context.messageBuffer.clear()
        context.scrollToBottom()
    }
}

