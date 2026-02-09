package com.github.phodal.acpmanager.ide

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val log = logger<IdeAcpSessionOps>()

/**
 * IDE-enhanced ACP session operations.
 *
 * Extends the basic AcpClientSessionOps pattern with IDE-aware capabilities:
 * - File read/write operations that go through VFS when possible
 * - Terminal operations using IntelliJ's terminal API
 * - Tool call pass-through to IdeAcpClient for IDE-specific operations
 * - Notification forwarding for selection/diagnostics events
 *
 * This replaces AcpClientSessionOps when running within the IDE context,
 * providing agents with full IDE integration capabilities.
 */
class IdeAcpSessionOps(
    private val project: Project,
    private val ideAcpClient: IdeAcpClient,
    private val onSessionUpdate: (SessionUpdate) -> Unit,
    private val onPermissionRequest: (SessionUpdate.ToolCallUpdate, List<PermissionOption>) -> RequestPermissionResponse,
    private val cwd: String = project.basePath ?: System.getProperty("user.dir") ?: ".",
) : ClientSessionOperations {

    private data class TerminalSession(
        val id: String,
        val process: Process,
        val outputBuffer: StringBuilder = StringBuilder(),
        val outputByteLimit: Long = Long.MAX_VALUE,
    )

    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private val terminalIdCounter = AtomicInteger(0)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        onSessionUpdate(notification)
    }

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        try {
            val summary = permissions.joinToString { opt -> "${opt.kind}:${opt.name}" }
            log.info("IDE requestPermissions: tool=${toolCall.title ?: "tool"} options=[$summary]")
        } catch (_: Exception) {
        }
        return onPermissionRequest(toolCall, permissions)
    }

    // ===== File System Operations (IDE-enhanced) =====

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        log.info("IDE fs.read_text_file: $resolved (line=$line, limit=$limit)")

        if (!resolved.toFile().exists()) {
            throw IllegalArgumentException("File not found: $resolved")
        }
        if (!resolved.toFile().isFile) {
            throw IllegalArgumentException("Not a file: $resolved")
        }

        val allLines = Files.readAllLines(resolved)
        val startLine = (line?.toInt() ?: 1).coerceAtLeast(1) - 1
        val lineLimit = limit?.toInt() ?: (allLines.size - startLine)

        val content = allLines
            .drop(startLine)
            .take(lineLimit)
            .joinToString("\n")

        ReadTextFileResponse(content = content, _meta = JsonNull)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        log.info("IDE fs.write_text_file: $resolved (${content.length} chars)")

        resolved.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }

        Files.writeString(resolved, content)

        // After writing, open the file in the IDE so the user can see changes
        ideAcpClient.ideTools.openFile(resolved.toString(), makeFrontmost = false)

        WriteTextFileResponse(_meta = JsonNull)
    }

    // ===== Terminal Operations =====

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse = withContext(Dispatchers.IO) {
        val terminalId = "ide-term-${terminalIdCounter.incrementAndGet()}"
        val effectiveCwd = cwd ?: this@IdeAcpSessionOps.cwd

        val fullCommand = if (args.isEmpty()) command else "$command ${args.joinToString(" ")}"
        val osName = System.getProperty("os.name").lowercase()
        val cmdList = when {
            osName.contains("win") -> listOf("cmd", "/c", fullCommand)
            else -> {
                val shell = File("/bin/bash").takeIf { it.exists() }?.absolutePath
                    ?: File("/bin/sh").absolutePath
                listOf(shell, "-c", fullCommand)
            }
        }

        log.info("IDE terminal.create: $cmdList (cwd=$effectiveCwd, id=$terminalId)")

        val pb = ProcessBuilder(cmdList)
        pb.directory(File(effectiveCwd))
        pb.redirectErrorStream(true)
        env.forEach { envVar -> pb.environment()[envVar.name] = envVar.value }

        val process = pb.start()
        val session = TerminalSession(
            id = terminalId,
            process = process,
            outputByteLimit = outputByteLimit?.toLong() ?: Long.MAX_VALUE,
        )
        terminals[terminalId] = session

        // Background output reader
        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var bytesRead: Int
                    while (reader.read(buffer).also { bytesRead = it } != -1) {
                        val text = String(buffer, 0, bytesRead)
                        synchronized(session.outputBuffer) {
                            if (session.outputBuffer.length < session.outputByteLimit) {
                                session.outputBuffer.append(text)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "ide-acp-terminal-$terminalId").apply { isDaemon = true }.start()

        CreateTerminalResponse(terminalId = terminalId, _meta = JsonNull)
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
        val session = terminals[terminalId]
            ?: throw IllegalArgumentException("Unknown terminal: $terminalId")

        val output: String
        val truncated: Boolean
        synchronized(session.outputBuffer) {
            truncated = session.outputBuffer.length >= session.outputByteLimit
            output = session.outputBuffer.toString()
        }

        val exitStatus = if (!session.process.isAlive) {
            TerminalExitStatus(
                exitCode = session.process.exitValue().toUInt(),
                signal = null,
                _meta = JsonNull
            )
        } else {
            null
        }

        return TerminalOutputResponse(
            output = output,
            truncated = truncated,
            exitStatus = exitStatus,
            _meta = JsonNull
        )
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
        val session = terminals.remove(terminalId)
        if (session != null) {
            log.info("IDE terminal.release: $terminalId")
            if (session.process.isAlive) {
                session.process.destroyForcibly()
            }
        }
        return ReleaseTerminalResponse(_meta = JsonNull)
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse = withContext(Dispatchers.IO) {
        val session = terminals[terminalId]
            ?: throw IllegalArgumentException("Unknown terminal: $terminalId")

        log.info("IDE terminal.wait_for_exit: $terminalId")
        val exited = session.process.waitFor(5, TimeUnit.MINUTES)

        if (exited) {
            WaitForTerminalExitResponse(
                exitCode = session.process.exitValue().toUInt(),
                signal = null,
                _meta = JsonNull
            )
        } else {
            WaitForTerminalExitResponse(
                exitCode = null,
                signal = null,
                _meta = JsonNull
            )
        }
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
        val session = terminals[terminalId]
            ?: throw IllegalArgumentException("Unknown terminal: $terminalId")

        log.info("IDE terminal.kill: $terminalId")
        if (session.process.isAlive) {
            session.process.destroyForcibly()
        }
        return KillTerminalCommandResponse(_meta = JsonNull)
    }

    // ===== Helpers =====

    private fun resolvePath(path: String): Path {
        val p = Path.of(path)
        return if (p.isAbsolute) p else Path.of(cwd, path)
    }

    /**
     * Release all terminal sessions.
     */
    fun releaseAll() {
        terminals.values.forEach { session ->
            try {
                if (session.process.isAlive) {
                    session.process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }
        terminals.clear()
    }
}
