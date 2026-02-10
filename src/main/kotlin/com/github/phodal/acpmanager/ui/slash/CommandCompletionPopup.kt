package com.github.phodal.acpmanager.ui.slash

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import java.awt.Component

private val log = logger<CommandCompletionPopup>()

/**
 * Popup UI for command completion.
 * Shows a list of available commands and parameter hints.
 */
class CommandCompletionPopup(
    private val parent: Component,
    private val commands: List<SlashCommand>,
    private val onSelected: (SlashCommand) -> Unit,
) {
    private var popup: JBPopup? = null
    private var parameterHintPopup: JBPopup? = null

    /**
     * Show the popup at the specified location.
     */
    fun show(parent: Component, x: Int, y: Int) {
        try {
            val step = object : BaseListPopupStep<SlashCommand>("", commands) {
                override fun getTextFor(value: SlashCommand): String = value.name

                override fun onChosen(selectedValue: SlashCommand?, finalChoice: Boolean): PopupStep<*>? {
                    if (selectedValue != null && finalChoice) {
                        onSelected(selectedValue)
                    }
                    return PopupStep.FINAL_CHOICE
                }
            }

            popup = JBPopupFactory.getInstance()
                .createListPopup(step)
                .apply {
                    // Don't steal focus from input area to allow continued typing
                    setRequestFocus(false)
                    showInScreenCoordinates(parent, java.awt.Point(x, y))
                }
        } catch (e: Exception) {
            log.warn("Failed to show command completion popup: ${e.message}")
        }
    }

    /**
     * Show parameter hints for a command.
     */
    fun showParameterHints(command: SlashCommand, x: Int, y: Int) {
        try {
            closeParameterHints()

            if (command.parameters.isEmpty()) {
                return
            }

            val hintText = buildString {
                append("Parameters: ")
                command.parameters.forEach { param ->
                    append(param.name)
                    if (param.required) append("*") else append("?")
                    append(" ")
                }
            }

            val step = object : BaseListPopupStep<CommandParameter>("", command.parameters) {
                override fun getTextFor(value: CommandParameter): String {
                    return "${value.name}${if (value.required) "*" else "?"} - ${value.description}"
                }
            }

            parameterHintPopup = JBPopupFactory.getInstance()
                .createListPopup(step)
                .apply {
                    setRequestFocus(false)
                    showInScreenCoordinates(parent, java.awt.Point(x, y))
                }
        } catch (e: Exception) {
            log.debug("Failed to show parameter hints: ${e.message}")
        }
    }

    /**
     * Close parameter hints popup.
     */
    private fun closeParameterHints() {
        try {
            parameterHintPopup?.cancel()
            parameterHintPopup = null
        } catch (e: Exception) {
            log.debug("Error closing parameter hints: ${e.message}")
        }
    }

    /**
     * Close the popup.
     */
    fun close() {
        try {
            popup?.cancel()
            popup = null
            closeParameterHints()
        } catch (e: Exception) {
            log.debug("Error closing popup: ${e.message}")
        }
    }

}

