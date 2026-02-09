package com.github.phodal.acpmanager.ide

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

private val log = logger<IdeNotifications>()

/**
 * Manages outbound notifications from the IDE to connected agents.
 *
 * Mirrors Claude Code's NotificationManager — broadcasts IDE events
 * (selection changes, diagnostics updates, @ mentions) to all
 * registered notification listeners.
 */
class IdeNotifications(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    /**
     * Listener that receives IDE notifications.
     * Agents/sessions can register to receive these events.
     */
    fun interface NotificationListener {
        fun onNotification(notification: IdeNotification)
    }

    private val listeners = CopyOnWriteArrayList<NotificationListener>()

    /**
     * Register a listener to receive IDE notifications.
     */
    fun addListener(listener: NotificationListener) {
        listeners.add(listener)
    }

    /**
     * Remove a previously registered listener.
     */
    fun removeListener(listener: NotificationListener) {
        listeners.remove(listener)
    }

    /**
     * Broadcast a notification to all registered listeners.
     */
    fun broadcastNotification(notification: IdeNotification) {
        if (listeners.isEmpty()) return

        scope.launch(Dispatchers.Default) {
            for (listener in listeners) {
                try {
                    listener.onNotification(notification)
                } catch (e: Exception) {
                    log.warn("Error sending notification ${notification.method}: ${e.message}")
                }
            }
        }
    }

    /**
     * Send a selection_changed notification.
     */
    fun sendSelectionChanged(
        filePath: String?,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        selectedText: String?,
    ) {
        broadcastNotification(
            IdeNotification.SelectionChanged(
                filePath = filePath,
                startLine = startLine,
                startColumn = startColumn,
                endLine = endLine,
                endColumn = endColumn,
                selectedText = selectedText,
            )
        )
    }

    /**
     * Send an at_mentioned notification.
     */
    fun sendAtMentioned(filePath: String, startLine: Int? = null, endLine: Int? = null) {
        broadcastNotification(
            IdeNotification.AtMentioned(
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
            )
        )
    }

    /**
     * Send a diagnostics_changed notification.
     */
    fun sendDiagnosticsChanged(uri: String) {
        broadcastNotification(IdeNotification.DiagnosticsChanged(uri))
    }
}
