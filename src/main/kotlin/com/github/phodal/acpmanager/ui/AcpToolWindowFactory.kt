package com.github.phodal.acpmanager.ui

import com.github.phodal.acpmanager.claudecode.registerClaudeCodeRenderer
import com.github.phodal.acpmanager.ui.renderer.initializeDefaultRendererFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for the ACP Manager tool window.
 *
 * Creates the main panel that provides:
 * - Multi-agent session management
 * - Chat interface per agent
 * - Agent configuration
 */
class AcpToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        @Volatile
        private var renderersInitialized = false

        /**
         * Initialize renderer factories. Called once on first tool window creation.
         */
        @Synchronized
        private fun initializeRenderers() {
            if (renderersInitialized) return
            initializeDefaultRendererFactory()
            registerClaudeCodeRenderer()
            renderersInitialized = true
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Initialize renderers on first use
        initializeRenderers()

        val panel = AcpManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "ACP Manager", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
