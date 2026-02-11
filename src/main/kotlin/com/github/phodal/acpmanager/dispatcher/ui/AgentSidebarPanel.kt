package com.github.phodal.acpmanager.dispatcher.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Right-side agent sidebar — divided into three visual sections:
 *
 * ```
 * ┌──────────────────────────┐
 * │  ROUTA                   │  ← Section 1: always one card
 * ├─ ─ ─ CRAFTERs ─ ─ ─ ─ ─┤
 * │  (placeholder or cards)  │  ← Section 2: dynamic, colored header
 * │  CRAFTER-1               │
 * │  CRAFTER-2               │
 * ├─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤
 * │  GATE                    │  ← Section 3: always one card
 * └──────────────────────────┘
 * ```
 *
 * ROUTA is always first, GATE always last.
 * CRAFTERs are dynamically inserted in the middle section.
 * Each section has a colored header label.
 */
class AgentSidebarPanel : JPanel(BorderLayout()) {

    companion object {
        val SIDEBAR_BG = JBColor(0x0D1117, 0x0D1117)
        val CARD_BG = JBColor(0x161B22, 0x161B22)
        val CARD_SELECTED_BG = JBColor(0x1C2333, 0x1C2333)
        val CARD_HOVER_BG = JBColor(0x1A2030, 0x1A2030)
        val CARD_BORDER = JBColor(0x21262D, 0x21262D)
        val SECTION_BORDER = JBColor(0x30363D, 0x30363D)
        val TEXT_PRIMARY = JBColor(0xC9D1D9, 0xC9D1D9)
        val TEXT_SECONDARY = JBColor(0x8B949E, 0x8B949E)
        val TEXT_PLACEHOLDER = JBColor(0x484F58, 0x484F58)

        // Section accent colors
        val ROUTA_ACCENT = JBColor(0x58A6FF, 0x58A6FF)
        val CRAFTER_ACCENT = JBColor(0x10B981, 0x10B981)
        val GATE_ACCENT = JBColor(0xA78BFA, 0xA78BFA)

        // Status colors
        val STATUS_IDLE = JBColor(0x6B7280, 0x9CA3AF)
        val STATUS_ACTIVE = JBColor(0x3B82F6, 0x3B82F6)
        val STATUS_COMPLETED = JBColor(0x10B981, 0x10B981)
        val STATUS_ERROR = JBColor(0xEF4444, 0xEF4444)
        val STATUS_PLANNING = JBColor(0xF59E0B, 0xF59E0B)
        val STATUS_VERIFYING = JBColor(0xA78BFA, 0xA78BFA)
    }

    /** Callback when an agent card is selected. Receives the agent ID. */
    var onAgentSelected: (String) -> Unit = {}

    // ── Internal state ───────────────────────────────────────────────────

    /** Maps agentId → sidebar card component. */
    private val cards = mutableMapOf<String, SidebarCard>()

    /** Ordered list of CRAFTER IDs. */
    private val crafterOrder = mutableListOf<String>()

    /** Currently selected agent ID. */
    var selectedAgentId: String? = null
        private set

    // Fixed agent IDs
    private val routaId = "__routa__"
    private val gateId = "__gate__"

    // ── Section panels ───────────────────────────────────────────────────

    // ROUTA section
    private val routaCard = SidebarCard(
        agentId = routaId,
        roleLabel = "ROUTA",
        info = "Coordinator",
        accentColor = ROUTA_ACCENT,
        onClick = { selectAgent(routaId) }
    )

    // CRAFTERs section
    private val crafterListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val crafterPlaceholder = JBLabel("Waiting for ROUTA to plan tasks...").apply {
        foreground = TEXT_PLACEHOLDER
        font = font.deriveFont(Font.ITALIC, 10f)
        border = JBUI.Borders.empty(8, 16, 8, 16)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val crafterCountLabel = JBLabel("0").apply {
        foreground = TEXT_SECONDARY
        font = font.deriveFont(9f)
    }

    // GATE section
    private val gateCard = SidebarCard(
        agentId = gateId,
        roleLabel = "GATE",
        info = "Verifier",
        accentColor = GATE_ACCENT,
        onClick = { selectAgent(gateId) }
    )

    init {
        isOpaque = true
        background = SIDEBAR_BG
        border = JBUI.Borders.customLineLeft(CARD_BORDER)

        cards[routaId] = routaCard
        cards[gateId] = gateCard

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = SIDEBAR_BG
        }

        // ── ROUTA section ───────────────────────────────────────────────
        val routaSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 70)

            add(createSectionHeader("ROUTA", ROUTA_ACCENT, null), BorderLayout.NORTH)
            routaCard.alignmentX = Component.LEFT_ALIGNMENT
            routaCard.maximumSize = Dimension(Int.MAX_VALUE, 44)
            add(routaCard, BorderLayout.CENTER)
        }
        mainPanel.add(routaSection)

        // Small gap between sections
        mainPanel.add(Box.createVerticalStrut(2))

        // ── CRAFTERs section ────────────────────────────────────────────
        val crafterSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            add(createSectionHeader("CRAFTERs", CRAFTER_ACCENT, crafterCountLabel), BorderLayout.NORTH)

            val crafterContent = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(crafterPlaceholder, BorderLayout.NORTH)
                add(crafterListPanel, BorderLayout.CENTER)
            }
            add(crafterContent, BorderLayout.CENTER)
        }
        mainPanel.add(crafterSection)

        // Small gap between sections
        mainPanel.add(Box.createVerticalStrut(2))

        // ── GATE section ────────────────────────────────────────────────
        val gateSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 70)

            add(createSectionHeader("GATE", GATE_ACCENT, null), BorderLayout.NORTH)
            gateCard.alignmentX = Component.LEFT_ALIGNMENT
            gateCard.maximumSize = Dimension(Int.MAX_VALUE, 44)
            add(gateCard, BorderLayout.CENTER)
        }
        mainPanel.add(gateSection)

        mainPanel.add(Box.createVerticalGlue())

        val scrollPane = JScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            isOpaque = true
            viewport.isOpaque = true
            viewport.background = SIDEBAR_BG
        }

        add(scrollPane, BorderLayout.CENTER)
    }

    // ── Section Header Factory ───────────────────────────────────────────

    private fun createSectionHeader(
        label: String,
        accentColor: Color,
        countLabel: JBLabel?,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x111921, 0x111921)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, SECTION_BORDER),
                JBUI.Borders.empty(3, 12, 3, 12)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 24)

            val leftContent = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel("●").apply {
                    foreground = accentColor
                    font = font.deriveFont(7f)
                })
                add(JBLabel(label).apply {
                    foreground = accentColor
                    font = font.deriveFont(Font.BOLD, 9f)
                })
                if (countLabel != null) {
                    add(countLabel)
                }
            }
            add(leftContent, BorderLayout.WEST)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Get the fixed ROUTA agent ID. */
    fun getRoutaId(): String = routaId

    /** Get the fixed GATE agent ID. */
    fun getGateId(): String = gateId

    /**
     * Add a CRAFTER card to the CRAFTERs section.
     */
    fun addCrafter(taskId: String, title: String) {
        SwingUtilities.invokeLater {
            if (taskId in cards) return@invokeLater

            val card = SidebarCard(
                agentId = taskId,
                roleLabel = "CRAFTER",
                info = truncate(title, 24),
                accentColor = CRAFTER_ACCENT,
                onClick = { selectAgent(taskId) }
            )
            cards[taskId] = card
            crafterOrder.add(taskId)

            // Hide placeholder, show card
            crafterPlaceholder.isVisible = false
            card.alignmentX = Component.LEFT_ALIGNMENT
            card.maximumSize = Dimension(Int.MAX_VALUE, 48)
            crafterListPanel.add(card)

            crafterCountLabel.text = "${crafterOrder.size}"

            crafterListPanel.revalidate()
            crafterListPanel.repaint()
        }
    }

    /**
     * Update an agent card's status.
     */
    fun updateAgentStatus(agentId: String, statusText: String, statusColor: Color) {
        SwingUtilities.invokeLater {
            cards[agentId]?.updateStatus(statusText, statusColor)
        }
    }

    /**
     * Update a card's info text (e.g. task title).
     */
    fun updateAgentInfo(agentId: String, info: String) {
        SwingUtilities.invokeLater {
            cards[agentId]?.updateInfo(truncate(info, 24))
        }
    }

    /**
     * Select an agent by ID (highlights the card and fires callback).
     */
    fun selectAgent(agentId: String) {
        if (agentId !in cards) return
        selectedAgentId = agentId
        cards.values.forEach { it.setSelected(false) }
        cards[agentId]?.setSelected(true)
        onAgentSelected(agentId)
    }

    /**
     * Clear all CRAFTER cards and reset state. ROUTA and GATE cards remain.
     */
    fun clear() {
        SwingUtilities.invokeLater {
            // Remove all CRAFTER cards
            crafterOrder.clear()
            val crafterIds = cards.keys.filter { it != routaId && it != gateId }
            crafterIds.forEach { cards.remove(it) }

            crafterListPanel.removeAll()
            crafterPlaceholder.isVisible = true
            crafterCountLabel.text = "0"

            // Reset ROUTA and GATE status
            cards[routaId]?.updateStatus("IDLE", STATUS_IDLE)
            cards[gateId]?.updateStatus("IDLE", STATUS_IDLE)

            crafterListPanel.revalidate()
            crafterListPanel.repaint()

            // Select ROUTA by default
            selectAgent(routaId)
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun truncate(text: String, maxLen: Int): String {
        val clean = text.trim()
        return if (clean.length > maxLen) clean.take(maxLen - 3) + "..." else clean
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SidebarCard — individual clickable card in the agent sidebar
// ═══════════════════════════════════════════════════════════════════════════

/**
 * A single card in the agent sidebar.
 * Shows: status dot | role label | info text | status text
 */
private class SidebarCard(
    val agentId: String,
    roleLabel: String,
    info: String,
    private val accentColor: Color,
    private val onClick: () -> Unit,
) : JPanel(BorderLayout()) {

    private val statusDot = JBLabel("●").apply {
        foreground = AgentSidebarPanel.STATUS_IDLE
        font = font.deriveFont(10f)
        border = JBUI.Borders.empty(0, 0, 0, 6)
    }

    private val roleText = JBLabel(roleLabel).apply {
        foreground = AgentSidebarPanel.TEXT_PRIMARY
        font = font.deriveFont(Font.BOLD, 11f)
    }

    private val infoText = JBLabel(info).apply {
        foreground = AgentSidebarPanel.TEXT_SECONDARY
        font = font.deriveFont(10f)
    }

    private val statusLabel = JBLabel("IDLE").apply {
        foreground = AgentSidebarPanel.STATUS_IDLE
        font = font.deriveFont(Font.BOLD, 9f)
        horizontalAlignment = SwingConstants.RIGHT
    }

    private var isCardSelected = false

    init {
        isOpaque = true
        background = AgentSidebarPanel.CARD_BG
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AgentSidebarPanel.CARD_BORDER),
            JBUI.Borders.empty(6, 12, 6, 12)
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(statusDot)
            add(roleText)
            add(JBLabel("·").apply { foreground = AgentSidebarPanel.TEXT_SECONDARY })
            add(infoText)
        }

        add(leftPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.EAST)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }

            override fun mouseEntered(e: MouseEvent) {
                if (!isCardSelected) {
                    background = AgentSidebarPanel.CARD_HOVER_BG
                }
            }

            override fun mouseExited(e: MouseEvent) {
                background = if (isCardSelected) AgentSidebarPanel.CARD_SELECTED_BG
                else AgentSidebarPanel.CARD_BG
            }
        })
    }

    fun setSelected(selected: Boolean) {
        isCardSelected = selected
        background = if (selected) AgentSidebarPanel.CARD_SELECTED_BG
        else AgentSidebarPanel.CARD_BG

        border = if (selected) {
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 1, 0, accentColor),
                JBUI.Borders.empty(6, 9, 6, 12)
            )
        } else {
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AgentSidebarPanel.CARD_BORDER),
                JBUI.Borders.empty(6, 12, 6, 12)
            )
        }
    }

    fun updateStatus(text: String, color: Color) {
        statusDot.foreground = color
        statusLabel.text = text
        statusLabel.foreground = color
    }

    fun updateInfo(info: String) {
        infoText.text = info
    }
}
