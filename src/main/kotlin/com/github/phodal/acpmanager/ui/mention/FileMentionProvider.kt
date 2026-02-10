package com.github.phodal.acpmanager.ui.mention

import com.github.phodal.acpmanager.ui.fuzzy.FuzzyMatcher
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

private val log = logger<FileMentionProvider>()

/**
 * Provides file mention suggestions using IntelliJ's FilenameIndex.
 *
 * Features:
 * - Suggests currently open files (highest priority)
 * - Suggests recently edited files
 * - Suggests files matching typed text
 * - Shows relative path in tail text
 * - Shows file icon
 * - Supports basic fuzzy matching
 */
class FileMentionProvider(private val project: Project) : MentionProvider {

    override fun getMentionType(): MentionType = MentionType.FILE

    override fun getMentions(query: String): List<MentionItem> {
        val mentions = mutableListOf<Pair<MentionItem, Int>>()  // Item + score
        val seen = mutableSetOf<String>()

        // 1. Add open files first (highest priority)
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        for (file in openFiles) {
            val matchResult = FuzzyMatcher.match(file.name, query)
            if (matchResult.matched) {
                val item = createMentionItem(file)
                if (seen.add(item.insertText)) {
                    // Boost score for open files
                    mentions.add(item to (matchResult.score + 100))
                }
            }
        }

        // 2. Add files from FilenameIndex (project files)
        try {
            com.intellij.openapi.application.ReadAction.compute<Unit, Exception> {
                val scope = GlobalSearchScope.projectScope(project)
                
                // FilenameIndex requires read action and doesn't support wildcards
                val allFileNames = if (query.isEmpty()) {
                    FilenameIndex.getAllFilenames(project).toList()
                } else {
                    // Get files with exact name match first, then filter with fuzzy matching
                    FilenameIndex.getAllFilenames(project).filter { 
                        FuzzyMatcher.match(it, query).matched 
                    }
                }
                
                for (fileName in allFileNames.take(50)) {  // Limit to 50 files for performance
                    val psiFiles = FilenameIndex.getFilesByName(project, fileName, scope)
                    for (psiFile in psiFiles) {
                        val virtualFile = psiFile.virtualFile
                        if (virtualFile != null) {
                            val matchResult = FuzzyMatcher.match(virtualFile.name, query)
                            if (matchResult.matched) {
                                val item = createMentionItem(virtualFile)
                                if (seen.add(item.insertText)) {
                                    mentions.add(item to matchResult.score)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Error getting files from FilenameIndex: ${e.message}")
        }

        // Sort by score (descending), then by name length
        return mentions.sortedWith(compareBy({ -it.second }, { it.first.displayText.length }))
            .map { it.first }
    }

    private fun createMentionItem(file: VirtualFile): MentionItem {
        val relativePath = getRelativePath(file)
        val icon = getFileIcon(file)
        
        return MentionItem(
            type = MentionType.FILE,
            displayText = file.name,
            insertText = file.path,
            icon = icon,
            tailText = relativePath,
            metadata = mapOf("path" to file.path, "isDirectory" to file.isDirectory)
        )
    }

    private fun getRelativePath(file: VirtualFile): String {
        return try {
            val basePath = project.basePath ?: return file.path
            val filePath = file.path
            if (filePath.startsWith(basePath)) {
                filePath.substring(basePath.length).removePrefix("/")
            } else {
                filePath
            }
        } catch (e: Exception) {
            file.path
        }
    }

    private fun getFileIcon(file: VirtualFile): javax.swing.Icon? {
        return try {
            when {
                file.isDirectory -> AllIcons.Nodes.Folder
                file.name.endsWith(".kt") -> AllIcons.FileTypes.Java  // Use Java icon for Kotlin
                file.name.endsWith(".java") -> AllIcons.FileTypes.Java
                file.name.endsWith(".xml") -> AllIcons.FileTypes.Xml
                file.name.endsWith(".json") -> AllIcons.FileTypes.Json
                file.name.endsWith(".yaml") || file.name.endsWith(".yml") -> AllIcons.FileTypes.Yaml
                file.name.endsWith(".md") -> AllIcons.FileTypes.Text  // Use Text icon for Markdown
                else -> AllIcons.FileTypes.Text
            }
        } catch (e: Exception) {
            AllIcons.FileTypes.Text
        }
    }

}

