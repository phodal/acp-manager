package com.github.phodal.acpmanager.ui.mention

import com.github.phodal.acpmanager.ui.fuzzy.FuzzyMatcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
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
        // Check if Java plugin is available before attempting to extract symbols
        if (!isJavaPluginAvailable()) {
            log.debug("Java plugin not available, skipping symbol extraction")
            return emptyList()
        }

        val psiFile = getCurrentPsiFile() ?: return emptyList()

        val symbols = extractSymbols(psiFile)
        val itemsWithScores = symbols
            .mapNotNull { symbol ->
                val matchResult = FuzzyMatcher.match(symbol.name, query)
                if (matchResult.matched) {
                    createMentionItem(symbol) to matchResult.score
                } else {
                    null
                }
            }

        // Sort by score (descending), then by name length
        return itemsWithScores
            .sortedWith(compareBy({ -it.second }, { it.first.displayText.length }))
            .map { it.first }
    }

    private fun isJavaPluginAvailable(): Boolean {
        return try {
            val javaPluginId = PluginId.getId("com.intellij.java")
            PluginManagerCore.getPlugin(javaPluginId) != null
        } catch (e: Exception) {
            log.debug("Error checking Java plugin availability: ${e.message}")
            false
        }
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

        try {
            // Use reflection to safely access PsiClass and PsiMethod
            val psiClassClass = Class.forName("com.intellij.psi.PsiClass")
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")

            // Extract classes
            @Suppress("UNCHECKED_CAST")
            val classes = PsiTreeUtil.findChildrenOfType(psiFile, psiClassClass as Class<PsiElement>)
            classes.forEach { psiClass ->
                try {
                    val isInterface = psiClass.javaClass.getMethod("isInterface").invoke(psiClass) as Boolean
                    val isEnum = psiClass.javaClass.getMethod("isEnum").invoke(psiClass) as Boolean

                    if (!isInterface && !isEnum) {
                        val name = psiClass.javaClass.getMethod("getName").invoke(psiClass) as? String ?: "Anonymous"
                        symbols.add(
                            Symbol(
                                name = name,
                                kind = "class",
                                lineNumber = getLineNumber(psiClass),
                                icon = AllIcons.Nodes.Class,
                                containingClass = null
                            )
                        )
                    }
                } catch (e: Exception) {
                    log.debug("Error extracting class symbol: ${e.message}")
                }
            }

            // Extract methods
            @Suppress("UNCHECKED_CAST")
            val methods = PsiTreeUtil.findChildrenOfType(psiFile, psiMethodClass as Class<PsiElement>)
            methods.forEach { method ->
                try {
                    val name = method.javaClass.getMethod("getName").invoke(method) as String
                    val containingClassMethod = method.javaClass.getMethod("getContainingClass")
                    val containingClass = containingClassMethod.invoke(method)
                    val containingClassName = if (containingClass != null) {
                        containingClass.javaClass.getMethod("getName").invoke(containingClass) as? String
                    } else {
                        null
                    }

                    symbols.add(
                        Symbol(
                            name = name,
                            kind = "method",
                            lineNumber = getLineNumber(method),
                            icon = AllIcons.Nodes.Method,
                            containingClass = containingClassName
                        )
                    )
                } catch (e: Exception) {
                    log.debug("Error extracting method symbol: ${e.message}")
                }
            }
        } catch (e: ClassNotFoundException) {
            log.debug("Java plugin classes not available: ${e.message}")
        } catch (e: Exception) {
            log.debug("Error extracting symbols: ${e.message}")
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

    private data class Symbol(
        val name: String,
        val kind: String,
        val lineNumber: Int,
        val icon: Icon,
        val containingClass: String?
    )
}

