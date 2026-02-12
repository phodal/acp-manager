package com.phodal.routa.gui.panel

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.gui.viewmodel.AgentEntry
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Sidebar panel displaying the ordered list of agents (ROUTA → CRAFTERs → GATE).
 *
 * Each agent is shown as a card with role icon, name, and status indicator.
 * The selected agent is highlighted. Clicking a card triggers [onAgentSelected].
 */
class GuiAgentSidebarPanel(
    private val onAgentSelected: (String) -> Unit,
) : JPanel() {

    private val cardListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color(0x1E, 0x1E, 0x2E)
    }

    private var selectedId: String = "__routa__"
    private var agents: List<AgentEntry> = emptyList()

    init {
        layout = BorderLayout()
        background = Color(0x1E, 0x1E, 0x2E)
        border = EmptyBorder(8, 8, 8, 8)

        val titleLabel = JLabel("Agents").apply {
            foreground = Color(0xC6, 0xD0, 0xF5)
            font = font.deriveFont(Font.BOLD, 13f)
            border = EmptyBorder(0, 4, 8, 0)
        }

        val scrollPane = JScrollPane(cardListPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            viewport.background = Color(0x1E, 0x1E, 0x2E)
        }

        add(titleLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Update the agent list and rebuild cards.
     */
    fun updateAgents(newAgents: List<AgentEntry>) {
        agents = newAgents
        rebuildCards()
    }

    /**
     * Update the selected agent ID and refresh highlights.
     */
    fun setSelected(agentId: String) {
        selectedId = agentId
        rebuildCards()
    }

    private fun rebuildCards() {
        cardListPanel.removeAll()

        for (agent in agents) {
            val card = createAgentCard(agent, agent.id == selectedId)
            cardListPanel.add(card)
            cardListPanel.add(Box.createVerticalStrut(4))
        }

        cardListPanel.add(Box.createVerticalGlue())
        cardListPanel.revalidate()
        cardListPanel.repaint()
    }

    private fun createAgentCard(agent: AgentEntry, isSelected: Boolean): JPanel {
        val bgColor = if (isSelected) Color(0x31, 0x32, 0x44) else Color(0x24, 0x25, 0x3A)
        val borderColor = if (isSelected) Color(0x89, 0xB4, 0xFA) else bgColor

        return JPanel(BorderLayout()).apply {
            background = bgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                EmptyBorder(8, 10, 8, 10)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 48)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Role icon + name
            val nameLabel = JLabel(roleIcon(agent.role) + " " + agent.displayName).apply {
                foreground = Color(0xC6, 0xD0, 0xF5)
                font = font.deriveFont(12f)
            }

            // Status dot
            val statusDot = JLabel(statusIcon(agent.status)).apply {
                foreground = statusColor(agent.status)
                font = font.deriveFont(11f)
            }

            add(nameLabel, BorderLayout.WEST)
            add(statusDot, BorderLayout.EAST)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onAgentSelected(agent.id)
                }
            })
        }
    }

    companion object {
        fun roleIcon(role: AgentRole): String = when (role) {
            AgentRole.ROUTA -> "🧭"
            AgentRole.CRAFTER -> "🔨"
            AgentRole.GATE -> "🔍"
        }

        fun statusIcon(status: AgentStatus): String = when (status) {
            AgentStatus.PENDING -> "○"
            AgentStatus.ACTIVE -> "●"
            AgentStatus.COMPLETED -> "✓"
            AgentStatus.ERROR -> "✗"
            AgentStatus.CANCELLED -> "⊘"
        }

        fun statusColor(status: AgentStatus): Color = when (status) {
            AgentStatus.PENDING -> Color(0x6C, 0x71, 0x86)
            AgentStatus.ACTIVE -> Color(0xA6, 0xE3, 0xA1)
            AgentStatus.COMPLETED -> Color(0x89, 0xB4, 0xFA)
            AgentStatus.ERROR -> Color(0xF3, 0x8B, 0xA8)
            AgentStatus.CANCELLED -> Color(0x6C, 0x71, 0x86)
        }
    }
}
