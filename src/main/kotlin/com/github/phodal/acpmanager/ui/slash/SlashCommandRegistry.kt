package com.github.phodal.acpmanager.ui.slash

import com.intellij.openapi.diagnostic.logger

private val log = logger<SlashCommandRegistry>()

/**
 * Registry for managing slash commands.
 * Maintains a collection of available commands and provides lookup/filtering.
 */
class SlashCommandRegistry {
    private val commands = java.util.concurrent.ConcurrentHashMap<String, SlashCommand>()

    /**
     * Register a command.
     */
    fun register(command: SlashCommand) {
        commands[command.name] = command
        log.debug("Registered command: /${command.name}")
    }

    /**
     * Register multiple commands.
     */
    fun registerAll(commandList: List<SlashCommand>) {
        commandList.forEach { register(it) }
    }

    /**
     * Unregister a command by name.
     */
    fun unregister(name: String) {
        commands.remove(name)
        log.debug("Unregistered command: /$name")
    }

    /**
     * Get a command by name.
     */
    fun getCommand(name: String): SlashCommand? {
        return commands[name]
    }

    /**
     * Get all commands.
     */
    fun getAllCommands(): List<SlashCommand> {
        return commands.values.toList()
    }

    /**
     * Get commands matching a query (by name or description).
     */
    fun getCommandsByQuery(query: String): List<SlashCommand> {
        if (query.isBlank()) {
            return getAllCommands()
        }

        val lowerQuery = query.lowercase()
        return commands.values.filter { cmd ->
            cmd.name.lowercase().contains(lowerQuery) ||
            cmd.description.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Execute a command by name.
     */
    fun executeCommand(name: String): Boolean {
        val command = getCommand(name) ?: return false
        try {
            command.execute()
            return true
        } catch (e: Exception) {
            log.warn("Error executing command /$name: ${e.message}", e)
            return false
        }
    }

    /**
     * Check if a command exists.
     */
    fun hasCommand(name: String): Boolean {
        return commands.containsKey(name)
    }

    /**
     * Get the count of registered commands.
     */
    fun getCommandCount(): Int {
        return commands.size
    }

    companion object {
        @Volatile
        private var instance: SlashCommandRegistry? = null

        /**
         * Get the singleton instance (thread-safe).
         */
        fun getInstance(): SlashCommandRegistry {
            return instance ?: synchronized(this) {
                instance ?: SlashCommandRegistry().also { instance = it }
            }
        }

        /**
         * Reset the singleton instance (for testing).
         */
        fun reset() {
            instance = null
        }
    }
}

