package com.phodal.routa.core.acp

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
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

/**
 * Platform-independent ACP session operations.
 *
 * Implements the file system and terminal capabilities that ACP agents
 * need to read/write files and run commands.
 *
 * Extracted from the IntelliJ plugin, with no IDE dependencies.
 */
class RoutaAcpSessionOps(
    private val onSessionUpdate: (SessionUpdate) -> Unit,
    private val cwd: String = System.getProperty("user.dir") ?: ".",
) : ClientSessionOperations {

    private data class TerminalSession(
        val id: String,
        val process: Process,
        val outputBuffer: StringBuilder = StringBuilder(),
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
        return RoutaAcpClient.defaultPermissionResponse(permissions)
    }

    // -- File System Operations --

    override suspend fun fsReadTextFile(
        path: String, line: UInt?, limit: UInt?, _meta: JsonElement?,
    ): ReadTextFileResponse = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        if (!resolved.toFile().exists()) throw IllegalArgumentException("File not found: $resolved")

        val allLines = Files.readAllLines(resolved)
        val startLine = (line?.toInt() ?: 1).coerceAtLeast(1) - 1
        val lineLimit = limit?.toInt() ?: (allLines.size - startLine)
        val content = allLines.drop(startLine).take(lineLimit).joinToString("\n")

        ReadTextFileResponse(content = content, _meta = JsonNull)
    }

    override suspend fun fsWriteTextFile(
        path: String, content: String, _meta: JsonElement?,
    ): WriteTextFileResponse = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        resolved.parent?.let { parent ->
            if (!Files.exists(parent)) Files.createDirectories(parent)
        }
        Files.writeString(resolved, content)
        WriteTextFileResponse(_meta = JsonNull)
    }

    // -- Terminal Operations --

    override suspend fun terminalCreate(
        command: String, args: List<String>, cwd: String?, env: List<EnvVariable>,
        outputByteLimit: ULong?, _meta: JsonElement?,
    ): CreateTerminalResponse = withContext(Dispatchers.IO) {
        val terminalId = "term-${terminalIdCounter.incrementAndGet()}"
        val effectiveCwd = cwd ?: this@RoutaAcpSessionOps.cwd
        val fullCommand = if (args.isEmpty()) command else "$command ${args.joinToString(" ")}"
        val shell = if (File("/bin/bash").exists()) "/bin/bash" else "/bin/sh"
        val cmdList = listOf(shell, "-c", fullCommand)

        val pb = ProcessBuilder(cmdList).apply {
            directory(File(effectiveCwd))
            redirectErrorStream(true)
        }
        env.forEach { envVar -> pb.environment()[envVar.name] = envVar.value }
        val process = pb.start()

        val session = TerminalSession(id = terminalId, process = process)
        terminals[terminalId] = session

        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var bytesRead: Int
                    while (reader.read(buffer).also { bytesRead = it } != -1) {
                        synchronized(session.outputBuffer) {
                            session.outputBuffer.append(String(buffer, 0, bytesRead))
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "routa-terminal-$terminalId").apply { isDaemon = true }.start()

        CreateTerminalResponse(terminalId = terminalId, _meta = JsonNull)
    }

    override suspend fun terminalOutput(
        terminalId: String, _meta: JsonElement?,
    ): TerminalOutputResponse {
        val session = terminals[terminalId]
            ?: throw IllegalArgumentException("Unknown terminal: $terminalId")
        val output: String
        synchronized(session.outputBuffer) { output = session.outputBuffer.toString() }
        val exitStatus = if (!session.process.isAlive) {
            TerminalExitStatus(exitCode = session.process.exitValue().toUInt(), signal = null, _meta = JsonNull)
        } else null
        return TerminalOutputResponse(output = output, truncated = false, exitStatus = exitStatus, _meta = JsonNull)
    }

    override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?): ReleaseTerminalResponse {
        val session = terminals.remove(terminalId)
        session?.process?.takeIf { it.isAlive }?.destroyForcibly()
        return ReleaseTerminalResponse(_meta = JsonNull)
    }

    override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?): WaitForTerminalExitResponse =
        withContext(Dispatchers.IO) {
            val session = terminals[terminalId]
                ?: throw IllegalArgumentException("Unknown terminal: $terminalId")
            session.process.waitFor(5, TimeUnit.MINUTES)
            WaitForTerminalExitResponse(
                exitCode = if (!session.process.isAlive) session.process.exitValue().toUInt() else null,
                signal = null, _meta = JsonNull
            )
        }

    override suspend fun terminalKill(terminalId: String, _meta: JsonElement?): KillTerminalCommandResponse {
        terminals[terminalId]?.process?.takeIf { it.isAlive }?.destroyForcibly()
        return KillTerminalCommandResponse(_meta = JsonNull)
    }

    private fun resolvePath(path: String): Path {
        val p = Path.of(path)
        return if (p.isAbsolute) p else Path.of(cwd, path)
    }

    fun releaseAll() {
        terminals.values.forEach { s -> s.process.takeIf { it.isAlive }?.destroyForcibly() }
        terminals.clear()
    }
}
