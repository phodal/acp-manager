package com.github.phodal.acpmanager.ide

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

private val log = logger<IdeTools>()

/**
 * IDE tools implementation — provides IDEA editor/file/diagnostic capabilities
 * that agents can invoke through tool calls.
 *
 * Mirrors Claude Code's EditorTools, FileTools, DiffTools, DiagnosticTools.
 */
class IdeTools(private val project: Project) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ===== File Tools =====

    /**
     * Open a file in the editor.
     * Mirrors Claude Code's FileTools.openFile.
     */
    fun openFile(filePath: String, makeFrontmost: Boolean = true): ToolCallResult {
        val projectDir = getProjectDir() ?: return ToolCallResult.error("Project directory not found")
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        ApplicationManager.getApplication().invokeAndWait {
            val file = findVirtualFile(filePath, projectDir) ?: return@invokeAndWait
            if (file.exists()) {
                file.refresh(false, false)
                FileEditorManager.getInstance(project).openFile(file, makeFrontmost)
            }
        }
        return ToolCallResult.ok("OK")
    }

    /**
     * Open multiple files in the editor.
     * Mirrors Claude Code's FileTools.open_files.
     */
    fun openFiles(filePaths: List<String>): ToolCallResult {
        val projectDir = getProjectDir() ?: return ToolCallResult.error("Project directory not found")
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        val openedFiles = mutableListOf<String>()
        ApplicationManager.getApplication().invokeAndWait {
            for (filePath in filePaths) {
                val file = findVirtualFile(filePath, projectDir)
                if (file != null && file.exists()) {
                    FileEditorManager.getInstance(project).openFile(file, true)
                    openedFiles.add(filePath)
                }
            }
        }
        return ToolCallResult.ok(openedFiles.joinToString("\n"))
    }

    /**
     * Close a tab by file path or tab name.
     * Mirrors Claude Code's FileTools.close_tab.
     */
    fun closeTab(tabName: String): ToolCallResult {
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        var found = false
        ApplicationManager.getApplication().invokeAndWait {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editors = fileEditorManager.allEditors
            val matchingEditor = editors.firstOrNull { editor ->
                editor.file?.path == tabName ||
                        editor.file?.name == tabName ||
                        editor.file?.path?.endsWith(tabName) == true
            }
            if (matchingEditor != null) {
                fileEditorManager.closeFile(matchingEditor.file!!)
                found = true
            }
        }

        return if (found) ToolCallResult.ok("OK") else ToolCallResult.error("Tab not found: $tabName")
    }

    /**
     * Get all currently open file paths.
     * Mirrors Claude Code's FileTools.get_all_opened_file_paths.
     */
    fun getOpenFiles(): ToolCallResult {
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        var filePaths = emptyList<String>()
        ApplicationManager.getApplication().invokeAndWait {
            filePaths = FileEditorManager.getInstance(project)
                .openFiles
                .mapNotNull { it.path }
        }
        return ToolCallResult.ok(filePaths.joinToString("\n"))
    }

    // ===== Editor Tools =====

    /**
     * Reformat a file using IDE's code formatter.
     * Mirrors Claude Code's EditorTools.reformat_file.
     */
    fun reformatFile(filePath: String): ToolCallResult {
        val projectDir = getProjectDir() ?: return ToolCallResult.error("Project directory not found")
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        try {
            val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile?> {
                val file = findVirtualFile(filePath, projectDir)
                if (file == null || !file.exists()) return@runReadAction null
                PsiManager.getInstance(project).findFile(file)
            }

            if (psiFile == null) return ToolCallResult.error("File not found: $filePath")

            val reformatCompleted = CountDownLatch(1)
            val codeProcessor = ReformatCodeProcessor(psiFile, false)
            codeProcessor.setPostRunnable { reformatCompleted.countDown() }
            ApplicationManager.getApplication().invokeLater { codeProcessor.run() }
            reformatCompleted.await()

            return ToolCallResult.ok("OK")
        } catch (e: Exception) {
            log.warn("Failed to reformat file: $filePath", e)
            return ToolCallResult.error("Error reformatting file: ${e.message}")
        }
    }

    // ===== Diff Tools =====

    /**
     * Open a diff view with original file vs proposed new content.
     * Mirrors Claude Code's DiffTools.openDiff with Accept/Reject UI.
     *
     * Returns:
     * - "FILE_SAVED" + new content if accepted
     * - "DIFF_REJECTED" if rejected
     */
    suspend fun openDiff(
        oldFilePath: String,
        newFileContents: String,
        tabName: String = "Diff",
    ): ToolCallResult {
        val projectDir = getProjectDir() ?: return ToolCallResult.error("Project directory not found")
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        val result = CompletableDeferred<ToolCallResult>()

        ApplicationManager.getApplication().invokeAndWait {
            try {
                val contentFactory = DiffContentFactory.getInstance()
                val originalFile = findVirtualFile(oldFilePath, projectDir)
                val originalFileName = oldFilePath.substringAfterLast('/')

                // Determine file type
                val fileType = originalFile?.fileType
                    ?: FileTypeManager.getInstance().getFileTypeByFileName(originalFileName)

                // Create original content
                val originalContent: DocumentContent = if (originalFile != null) {
                    contentFactory.createDocument(project, originalFile)
                        ?: contentFactory.create(project, "", fileType)
                } else {
                    contentFactory.create(project, "", fileType)
                } as DocumentContent

                // Create proposed content
                val proposedFile = LightVirtualFile(originalFileName, fileType, newFileContents)
                val proposedContent: DiffContent = contentFactory.createDocument(project, proposedFile)
                    ?: contentFactory.create(project, proposedFile)

                // Build diff request
                val originalTitlePrefix = if (originalFile != null) "Original: " else "New: "
                val diffRequest = SimpleDiffRequest(
                    tabName,
                    originalContent,
                    proposedContent,
                    "$originalTitlePrefix$oldFilePath",
                    "Proposed"
                )

                // Mark original as read-only, proposed as editable
                diffRequest.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, false))
                diffRequest.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)

                var actionApplied = false

                // Accept action
                val acceptAction = object : AnAction("Accept", "Accept proposed changes", AllIcons.Actions.Checked) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (actionApplied) return
                        actionApplied = true
                        val updatedText = (proposedContent as? DocumentContent)?.document?.text
                            ?: proposedFile.content.toString()
                        val normalized = updatedText.replace("\r\n", "\n")
                        result.complete(ToolCallResult.ok("FILE_SAVED", normalized))
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }

                // Reject action
                val rejectAction = object : AnAction("Reject", "Reject proposed changes", AllIcons.Actions.Cancel) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (actionApplied) return
                        actionApplied = true
                        result.complete(ToolCallResult.ok("DIFF_REJECTED"))
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }

                // Add context actions
                val actions = mutableListOf<AnAction>(rejectAction, acceptAction)
                diffRequest.putUserData(
                    com.intellij.diff.util.DiffUserDataKeysEx.CONTEXT_ACTIONS, actions
                )

                // Show diff
                val chain = SimpleDiffRequestChain(diffRequest)
                DiffManagerEx.getInstance().showDiffBuiltin(project, chain, DiffDialogHints.DEFAULT)

            } catch (e: Exception) {
                log.warn("Error opening diff", e)
                result.complete(ToolCallResult.error("Error opening diff: ${e.message}"))
            }
        }

        // Wait for user action with a generous timeout
        return withTimeoutOrNull(600.seconds) { result.await() }
            ?: ToolCallResult.error("Diff view timed out")
    }

    // ===== Diagnostic Tools =====

    /**
     * Get diagnostics (errors/warnings) for a file.
     * Mirrors Claude Code's DiagnosticTools.getDiagnostics.
     *
     * @param uri File URI or path. If null, uses the currently active editor.
     */
    suspend fun getDiagnostics(uri: String?): ToolCallResult {
        val projectDir = getProjectDir() ?: return ToolCallResult.error("Project directory not found")
        if (!project.isOpen) return ToolCallResult.error("Project is closed")

        val result = CompletableDeferred<ToolCallResult>()

        ApplicationManager.getApplication().invokeAndWait {
            try {
                // Resolve target file
                val file: VirtualFile? = if (uri != null) {
                    val normalizedPath = uri
                        .removePrefix("file://")
                        .replace("_claude_fs_right:", "")
                    findVirtualFile(normalizedPath, projectDir)
                } else {
                    FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile
                }

                val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) }
                if (psiFile == null) {
                    result.complete(ToolCallResult.error("File not found"))
                    return@invokeAndWait
                }

                file.refresh(false, false)

                val daemonCodeAnalyzer = DaemonCodeAnalyzerEx.getInstanceEx(project)
                val connection = project.messageBus.connect()

                connection.subscribe(
                    DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
                    object : DaemonCodeAnalyzer.DaemonListener {
                        override fun daemonFinished() {
                            if (!daemonCodeAnalyzer.isErrorAnalyzingFinished(psiFile)) return

                            connection.disconnect()

                            val document = psiFile.fileDocument
                            val diagnostics = mutableListOf<DiagnosticItem>()

                            DaemonCodeAnalyzerEx.processHighlights(
                                document, project, HighlightSeverity.WEAK_WARNING,
                                0, document.textLength
                            ) { info: HighlightInfo ->
                                val description = info.description ?: return@processHighlights true

                                val lineStart = document.getLineNumber(info.startOffset)
                                val columnStart = info.startOffset - document.getLineStartOffset(lineStart)
                                val lineEnd = document.getLineNumber(info.endOffset)
                                val columnEnd = info.endOffset - document.getLineStartOffset(lineEnd)

                                diagnostics.add(
                                    DiagnosticItem(
                                        message = description,
                                        severity = DiagnosticSeverity.from(info.severity.name).name,
                                        startLine = lineStart,
                                        startColumn = columnStart,
                                        endLine = lineEnd,
                                        endColumn = columnEnd
                                    )
                                )
                                true
                            }

                            val effectiveUri = uri ?: "file://${file.path}"
                            val fileDiagnostics = FileDiagnostics(effectiveUri, diagnostics)
                            val jsonResult = json.encodeToString(FileDiagnostics.serializer(), fileDiagnostics)
                            result.complete(ToolCallResult.ok(jsonResult))
                        }
                    }
                )

                daemonCodeAnalyzer.restart(psiFile)
            } catch (e: Exception) {
                log.warn("Error getting diagnostics", e)
                result.complete(ToolCallResult.error("Error getting diagnostics: ${e.message}"))
            }
        }

        return withTimeoutOrNull(5.seconds) { result.await() }
            ?: ToolCallResult.error("Timeout getting diagnostics")
    }

    // ===== Helpers =====

    private fun getProjectDir(): Path? {
        val dir = project.guessProjectDir() ?: return null
        return dir.toNioPathOrNull()
    }

    private fun findVirtualFile(path: String, projectDir: Path): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        return ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            fs.findFileByPath(path) ?: fs.refreshAndFindFileByNioFile(projectDir.resolve(path))
        }
    }
}
