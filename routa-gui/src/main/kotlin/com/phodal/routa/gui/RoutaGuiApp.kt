package com.phodal.routa.gui

import com.phodal.routa.gui.panel.GuiDispatcherPanel
import com.phodal.routa.gui.viewmodel.GuiDispatcherViewModel
import com.phodal.routa.core.viewmodel.RoutaViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Standalone Swing application for the Routa multi-agent dispatcher.
 *
 * Launches a JFrame with the full dispatcher UI, independent of IntelliJ IDEA.
 *
 * ## Usage
 * ```
 * ./gradlew :routa-gui:run
 * ```
 *
 * The application starts with the ViewModel in an uninitialized state.
 * To connect to an agent provider, initialize the RoutaViewModel
 * via the GUI ViewModel before submitting requests.
 */
fun main() {
    // Use system look-and-feel or cross-platform
    try {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
    } catch (_: Exception) {
        // fallback to default
    }

    SwingUtilities.invokeLater {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val routaViewModel = RoutaViewModel(scope)
        val guiViewModel = GuiDispatcherViewModel(routaViewModel, scope)

        val frame = JFrame("Routa — Multi-Agent Dispatcher").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            contentPane = GuiDispatcherPanel(guiViewModel, scope)
            preferredSize = Dimension(1200, 800)
            minimumSize = Dimension(800, 600)
            pack()
            setLocationRelativeTo(null)
        }

        frame.isVisible = true
    }
}
