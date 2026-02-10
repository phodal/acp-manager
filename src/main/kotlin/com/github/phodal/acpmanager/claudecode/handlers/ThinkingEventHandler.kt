package com.github.phodal.acpmanager.claudecode.handlers

import com.github.phodal.acpmanager.claudecode.context.RenderContext
import com.github.phodal.acpmanager.claudecode.panels.CollapsibleThinkingPanel
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import kotlin.reflect.KClass

/**
 * Handler for thinking-related events (ThinkingStart, ThinkingChunk, ThinkingEnd, ThinkingSignature).
 */
class ThinkingEventHandler : MultiEventHandler() {

    override val supportedEvents: Set<KClass<out RenderEvent>> = setOf(
        RenderEvent.ThinkingStart::class,
        RenderEvent.ThinkingChunk::class,
        RenderEvent.ThinkingEnd::class,
        RenderEvent.ThinkingSignature::class
    )

    override fun handle(event: RenderEvent, context: RenderContext) {
        when (event) {
            is RenderEvent.ThinkingStart -> handleStart(context)
            is RenderEvent.ThinkingChunk -> handleChunk(event, context)
            is RenderEvent.ThinkingEnd -> handleEnd(event, context)
            is RenderEvent.ThinkingSignature -> handleSignature(event, context)
            else -> {}
        }
    }

    private fun handleStart(context: RenderContext) {
        context.thinkingBuffer.clear()
        context.currentThinkingSignature = null

        val panel = CollapsibleThinkingPanel(context.colors.thinkingFg)
        context.currentThinkingPanel = panel
        context.addPanel(panel.component)
        context.scrollToBottom()
    }

    private fun handleChunk(event: RenderEvent.ThinkingChunk, context: RenderContext) {
        context.thinkingBuffer.append(event.content)
        (context.currentThinkingPanel as? CollapsibleThinkingPanel)?.updateContent(
            context.thinkingBuffer.toString()
        )
    }

    private fun handleEnd(event: RenderEvent.ThinkingEnd, context: RenderContext) {
        (context.currentThinkingPanel as? CollapsibleThinkingPanel)?.finalize(
            event.fullContent,
            context.currentThinkingSignature
        )
        context.currentThinkingPanel = null
        context.thinkingBuffer.clear()
        context.currentThinkingSignature = null
        context.scrollToBottom()
    }

    private fun handleSignature(event: RenderEvent.ThinkingSignature, context: RenderContext) {
        context.currentThinkingSignature = event.signature
    }
}

