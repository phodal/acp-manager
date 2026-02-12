package com.phodal.routa.gui.panel

import com.phodal.routa.gui.viewmodel.GuiDispatcherViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Main dispatcher panel composing the full GUI layout.
 *
 * ```
 * ┌─────────────────────────────────┬──────────────────────┐
 * │  [Content Area]                 │  [Agent Sidebar]     │
 * │  Title + streaming output       │  ROUTA  ● ACTIVE     │
 * │                                 │  Task-1 ○ PENDING    │
 * │                                 │  Task-2 ○ PENDING    │
 * │                                 │  GATE   ○ PENDING    │
 * ├─────────────────────────────────┤                      │
 * │  [Input] [Send] [Stop]          │                      │
 * ├─────────────────────────────────┴──────────────────────┤
 * │  Status: Ready                                         │
 * └────────────────────────────────────────────────────────┘
 * ```
 *
 * @param viewModel The [GuiDispatcherViewModel] driving the UI state.
 * @param scope Coroutine scope for flow collection.
 */
class GuiDispatcherPanel(
    private val viewModel: GuiDispatcherViewModel,
    private val scope: CoroutineScope,
) : JPanel() {

    private val contentPanel = GuiContentPanel()
    private val sidebarPanel = GuiAgentSidebarPanel { agentId ->
        viewModel.selectAgent(agentId)
    }
    private val inputPanel = GuiInputPanel(
        onSubmit = { text -> viewModel.submitRequest(text) },
        onStop = { viewModel.stopExecution() },
    )
    private val statusLabel = JLabel("Ready").apply {
        foreground = Color(0x6C, 0x71, 0x86)
        font = font.deriveFont(11f)
        border = EmptyBorder(4, 12, 4, 12)
    }

    init {
        layout = BorderLayout()
        background = Color(0x1E, 0x1E, 0x2E)

        // Left panel: content + input
        val leftPanel = JPanel(BorderLayout()).apply {
            background = Color(0x1E, 0x1E, 0x2E)
            add(contentPanel, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

        // Split pane: left (content) + right (sidebar)
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, sidebarPanel).apply {
            resizeWeight = 0.65
            dividerSize = 2
            border = BorderFactory.createEmptyBorder()
            isContinuousLayout = true
        }

        // Status bar
        val statusBar = JPanel(BorderLayout()).apply {
            background = Color(0x18, 0x18, 0x25)
            add(statusLabel, BorderLayout.WEST)
        }

        add(splitPane, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        observeState()
    }

    private fun observeState() {
        // Observe agent list → update sidebar
        scope.launch(Dispatchers.Main) {
            viewModel.agents.collectLatest { agents ->
                sidebarPanel.updateAgents(agents)
            }
        }

        // Observe selected agent → update sidebar highlight + content
        scope.launch(Dispatchers.Main) {
            viewModel.selectedAgentId.collectLatest { agentId ->
                sidebarPanel.setSelected(agentId)
                val agent = viewModel.agents.value.find { it.id == agentId }
                val title = agent?.displayName ?: agentId
                contentPanel.updateContent(title, viewModel.agentOutputs.value[agentId])
            }
        }

        // Observe agent outputs → update content for selected agent
        scope.launch(Dispatchers.Main) {
            viewModel.agentOutputs.collectLatest { outputs ->
                val selectedId = viewModel.selectedAgentId.value
                val agent = viewModel.agents.value.find { it.id == selectedId }
                val title = agent?.displayName ?: selectedId
                contentPanel.updateContent(title, outputs[selectedId])
            }
        }

        // Observe status text → update status bar
        scope.launch(Dispatchers.Main) {
            viewModel.statusText.collectLatest { text ->
                statusLabel.text = text
            }
        }

        // Observe running state → toggle input buttons
        scope.launch(Dispatchers.Main) {
            viewModel.routaViewModel.isRunning.collectLatest { running ->
                inputPanel.setRunning(running)
            }
        }

        // Observe errors → show dialog
        scope.launch(Dispatchers.Main) {
            viewModel.errorMessage.collect { msg ->
                JOptionPane.showMessageDialog(
                    this@GuiDispatcherPanel,
                    msg,
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}
