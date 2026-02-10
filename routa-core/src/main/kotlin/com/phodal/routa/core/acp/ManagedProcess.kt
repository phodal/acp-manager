package com.phodal.routa.core.acp

import java.util.concurrent.TimeUnit

/**
 * Wrapper around a JVM Process with lifecycle helpers.
 * 
 * Extracted from the IntelliJ plugin to be usable without IDE dependencies.
 */
class ManagedProcess(
    val agentKey: String,
    val process: Process,
    val command: List<String>,
) {
    val pid: Long get() = process.pid()

    val inputStream get() = process.inputStream
    val outputStream get() = process.outputStream
    val errorStream get() = process.errorStream

    fun isAlive(): Boolean = process.isAlive

    /**
     * Gracefully destroy the process (SIGTERM, then force after timeout).
     */
    fun destroy(timeoutMs: Long = 5000) {
        if (!process.isAlive) return
        try {
            process.destroy()
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            process.destroyForcibly()
        }
    }

    fun destroyQuietly() {
        try {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
        }
    }
}
