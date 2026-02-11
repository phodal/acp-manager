package com.phodal.routa.core.acp

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of ACP agent processes.
 *
 * Extracted from the IntelliJ plugin to be platform-independent.
 * Provides process reuse, graceful shutdown, and health monitoring.
 */
class AcpProcessManager {

    private val processes = ConcurrentHashMap<String, ManagedProcess>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { shutdownAll() })
    }

    /**
     * Get or create an agent process.
     * If a healthy process already exists, it is reused.
     */
    fun getOrCreateProcess(
        agentKey: String,
        command: List<String>,
        cwd: String,
        env: Map<String, String> = emptyMap(),
    ): ManagedProcess {
        return processes.compute(agentKey) { _, existing ->
            when {
                existing != null && existing.isAlive() -> existing
                existing != null -> {
                    existing.destroyQuietly()
                    spawnProcess(agentKey, command, cwd, env)
                }
                else -> spawnProcess(agentKey, command, cwd, env)
            }
        }!!
    }

    fun terminateProcess(agentKey: String) {
        val managed = processes.remove(agentKey) ?: return
        managed.destroy()
    }

    fun shutdownAll() {
        val keys = processes.keys.toList()
        for (key in keys) {
            terminateProcess(key)
        }
    }

    fun isRunning(agentKey: String): Boolean {
        return processes[agentKey]?.isAlive() == true
    }

    private fun spawnProcess(
        agentKey: String,
        command: List<String>,
        cwd: String,
        env: Map<String, String>,
    ): ManagedProcess {
        val pb = ProcessBuilder(command).apply {
            directory(File(cwd))
            redirectErrorStream(false)
        }
        env.forEach { (k, v) -> pb.environment()[k] = v }

        val process = pb.start()
        return ManagedProcess(agentKey = agentKey, process = process, command = command)
    }

    companion object {
        @Volatile
        private var instance: AcpProcessManager? = null

        fun getInstance(): AcpProcessManager {
            return instance ?: synchronized(this) {
                instance ?: AcpProcessManager().also { instance = it }
            }
        }
    }
}
