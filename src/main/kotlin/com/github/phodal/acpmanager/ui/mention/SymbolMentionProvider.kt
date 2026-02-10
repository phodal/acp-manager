package com.github.phodal.acpmanager.ui.mention

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

private val log = logger<SymbolMentionProvider>()

/**
 * Provides symbol suggestions (classes, methods) from the current file.
 *
 * Features:
 * - Extracts classes and methods from current file using PSI
 * - Shows symbol type (class/method) with appropriate icons
 * - Shows containing class for methods
 * - Inserts with line number reference for easy navigation
 * - Supports fuzzy matching on symbol names
 */
class SymbolMentionProvider(private val project: Project) : MentionProvider {

    override fun getMentionType(): MentionType = MentionType.SYMBOL

    override fun getMentions(query: String): List<MentionItem> {
        val psiFile = getCurrentPsiFile() ?: return emptyList()

        val symbols = extractSymbols(psiFile)
        return symbols
            .filter { matchesQuery(it.name, query) }
            .map { symbol -> createMentionItem(symbol) }
            .sortedWith(compareBy(
                { !it.displayText.equals(query, ignoreCase = true) },
                { it.displayText.length }
            ))
    }

    private fun getCurrentPsiFile(): PsiFile? {
        // Try to get from FileEditorManager first (production)
        val virtualFile = FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile
        if (virtualFile != null) {
            return PsiManager.getInstance(project).findFile(virtualFile)
        }
        return null
    }

    private fun extractSymbols(psiFile: PsiFile): List<Symbol> {
        val symbols = mutableListOf<Symbol>()

        // Extract classes
        PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java).forEach { psiClass ->
            if (!psiClass.isInterface && !psiClass.isEnum) {
                symbols.add(
                    Symbol(
                        name = psiClass.name ?: "Anonymous",
                        kind = "class",
                        lineNumber = getLineNumber(psiClass),
                        icon = AllIcons.Nodes.Class,
                        containingClass = null
                    )
                )
            }
        }

        // Extract methods
        PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java).forEach { method ->
            val containingClass = method.containingClass?.name

            symbols.add(
                Symbol(
                    name = method.name,
                    kind = "method",
                    lineNumber = getLineNumber(method),
                    icon = AllIcons.Nodes.Method,
                    containingClass = containingClass
                )
            )
        }

        return symbols.sortedBy { it.lineNumber }
    }

    private fun createMentionItem(symbol: Symbol): MentionItem {
        val displayText = if (symbol.containingClass != null) {
            "${symbol.containingClass}.${symbol.name}"
        } else {
            symbol.name
        }

        return MentionItem(
            type = MentionType.SYMBOL,
            displayText = displayText,
            insertText = "${symbol.name}:${symbol.lineNumber}",
            icon = symbol.icon,
            tailText = "Line ${symbol.lineNumber} • ${symbol.kind}",
            metadata = mapOf(
                "lineNumber" to symbol.lineNumber,
                "kind" to symbol.kind,
                "containingClass" to (symbol.containingClass ?: "")
            )
        )
    }

    private fun getLineNumber(element: PsiElement): Int {
        return try {
            val document = element.containingFile?.viewProvider?.document ?: return 0
            val offset = element.textOffset
            document.getLineNumber(offset) + 1
        } catch (e: Exception) {
            log.debug("Error getting line number: ${e.message}")
            0
        }
    }

    private fun matchesQuery(symbolName: String, query: String): Boolean {
        if (query.isEmpty()) return true
        return symbolName.contains(query, ignoreCase = true)
    }

    private data class Symbol(
        val name: String,
        val kind: String,
        val lineNumber: Int,
        val icon: Icon,
        val containingClass: String?
    )
}

