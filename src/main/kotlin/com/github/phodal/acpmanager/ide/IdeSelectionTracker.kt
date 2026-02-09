package com.github.phodal.acpmanager.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import java.lang.ref.WeakReference

private val log = logger<IdeSelectionTracker>()

/**
 * Tracks editor selection changes and notifies listeners.
 *
 * Mirrors Claude Code's NotificationManager.registerSelectionListener —
 * monitors which file + selection is active, and fires selection_changed
 * notifications when the user changes their selection or switches tabs.
 */
class IdeSelectionTracker(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private var connection: MessageBusConnection? = null
    private var selectionListener: SelectionListener? = null
    private var onNotification: ((IdeNotification) -> Unit)? = null

    /**
     * Start tracking selection events.
     *
     * @param notificationCallback Called when a selection change is detected.
     */
    fun startTracking(notificationCallback: (IdeNotification) -> Unit) {
        this.onNotification = notificationCallback

        val listener = object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) {
                sendSelectionNotification(event.editor)
            }
        }
        this.selectionListener = listener

        // Attach to currently open editors
        val initialEditors = mutableListOf<WeakReference<TextEditor>>()

        ApplicationManager.getApplication().invokeAndWait {
            val fileEditorManager = FileEditorManagerEx.getInstanceEx(project)
            val selectedEditor = fileEditorManager.selectedTextEditor
            if (selectedEditor != null) {
                selectedEditor.selectionModel.addSelectionListener(listener)
                sendSelectionNotification(selectedEditor)
            } else {
                // If no selected editor, attach to all open editors
                for (editor in fileEditorManager.allEditors) {
                    if (editor is TextEditor) {
                        editor.editor.selectionModel.addSelectionListener(listener)
                        sendSelectionNotification(editor.editor)
                        initialEditors.add(WeakReference(editor))
                    }
                }
            }
        }

        // Subscribe to tab switch events
        val conn = project.messageBus.connect()
        this.connection = conn

        conn.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    // Add listener to new editor
                    val newEditor = event.newEditor
                    if (newEditor is TextEditor) {
                        newEditor.editor.selectionModel.addSelectionListener(listener)
                        sendSelectionNotification(newEditor.editor)
                    }

                    // Remove listener from old editor
                    val oldEditor = event.oldEditor
                    if (oldEditor is TextEditor) {
                        oldEditor.editor.selectionModel.removeSelectionListener(listener)
                    }

                    // Clean up initial editors
                    for (ref in initialEditors) {
                        val textEditor = ref.get()
                        textEditor?.editor?.selectionModel?.removeSelectionListener(listener)
                    }
                    initialEditors.clear()
                }
            }
        )

        log.info("Selection tracking started for project '${project.name}'")
    }

    /**
     * Stop tracking selection events.
     */
    fun stopTracking() {
        connection?.disconnect()
        connection = null
        selectionListener = null
        onNotification = null
        log.info("Selection tracking stopped for project '${project.name}'")
    }

    private fun sendSelectionNotification(editor: Editor) {
        try {
            val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
            val filePath = virtualFile?.path

            val selectionModel = editor.selectionModel
            val startPosition: LogicalPosition = editor.offsetToLogicalPosition(selectionModel.selectionStart)
            val endPosition: LogicalPosition = editor.offsetToLogicalPosition(selectionModel.selectionEnd)

            val notification = IdeNotification.SelectionChanged(
                filePath = filePath,
                startLine = startPosition.line,
                startColumn = startPosition.column,
                endLine = endPosition.line,
                endColumn = endPosition.column,
                selectedText = selectionModel.selectedText,
            )

            onNotification?.invoke(notification)
        } catch (e: Exception) {
            log.debug("Error sending selection notification: ${e.message}")
        }
    }
}
