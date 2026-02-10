package com.github.phodal.acpmanager.ui.completion

import com.github.phodal.acpmanager.ui.mention.MentionItem
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList

private val log = logger<MentionCompletionPopup>()

/**
 * Popup for @ mention autocomplete.
 * Uses JBPopupFactory's built-in list popup with keyboard/mouse selection.
 */
class MentionCompletionPopup(
    private val items: List<MentionItem>,
    private val onSelect: (MentionItem) -> Unit,
    private val onClose: () -> Unit
) {
    private var popup: JBPopup? = null

    /**
     * Show the popup at the given component location.
     */
    fun show(component: Component, x: Int, y: Int) {
        val step = object : BaseListPopupStep<MentionItem>("", items) {
            override fun getTextFor(value: MentionItem): String = value.displayText

            override fun onChosen(selectedValue: MentionItem?, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != null && finalChoice) {
                    onSelect(selectedValue)
                }
                return PopupStep.FINAL_CHOICE
            }
        }

        popup = JBPopupFactory.getInstance()
            .createListPopup(step)
            .apply {
                // Don't steal focus from input area to allow continued typing
                setRequestFocus(false)
                showInScreenCoordinates(component, java.awt.Point(x, y))
            }
    }

    /**
     * Close the popup.
     */
    fun close() {
        popup?.cancel()
        popup = null
        onClose()
    }
}
