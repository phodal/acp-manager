package com.phodal.routa.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.io.File

/**
 * LLM provider types supported by Routa, matching xiuper's config format.
 */
@Serializable
enum class LLMProviderType(val displayName: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    GOOGLE("google"),
    DEEPSEEK("deepseek"),
    OLLAMA("ollama"),
    OPENROUTER("openrouter");

    companion object {
        fun fromString(name: String): LLMProviderType? {
            return entries.find { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * Named model configuration, matching xiuper's config.yaml format.
 */
@Serializable
data class NamedModelConfig(
    val name: String = "",
    val provider: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.0,
    val maxTokens: Int = 128000,
    val baseUrl: String = "",
)

/**
 * ACP agent configuration — defines an external agent that can be spawned as a child process.
 *
 * ```yaml
 * acpAgents:
 *   codex:
 *     command: codex
 *     args: ["--full-auto"]
 *     env:
 *       OPENAI_API_KEY: "sk-..."
 *   claude:
 *     command: claude
 *     args: ["-p", "--output-format", "stream-json", "--input-format", "stream-json"]
 * activeCrafter: codex
 * ```
 */
@Serializable
data class AcpAgentConfig(
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val description: String = "",
    val name: String = "",
    val autoApprove: Boolean = false,
) {
    fun getCommandLine(): List<String> = mutableListOf(command).apply { addAll(args) }
    fun isConfigured(): Boolean = command.isNotBlank()
}

/**
 * Root configuration file, compatible with `~/.autodev/config.yaml`.
 *
 * ```yaml
 * active: default
 * configs:
 *   - name: default
 *     provider: deepseek
 *     apiKey: sk-...
 *     model: deepseek-chat
 * acpAgents:
 *   codex:
 *     command: codex
 *     args: ["--full-auto"]
 * activeCrafter: codex
 * ```
 */
@Serializable
data class RoutaConfigFile(
    val active: String = "",
    val configs: List<NamedModelConfig> = emptyList(),
    val acpAgents: Map<String, AcpAgentConfig> = emptyMap(),
    val activeCrafter: String? = null,
)

/**
 * Loads Routa configuration from `~/.autodev/config.yaml`.
 *
 * This is compatible with xiuper's configuration format, so the same
 * config file can be shared between projects.
 */
object RoutaConfigLoader {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
        )
    )

    private val configDir: File by lazy {
        File(System.getProperty("user.home"), ".autodev")
    }

    private val configFile: File by lazy {
        File(configDir, "config.yaml")
    }

    /**
     * Load config from `~/.autodev/config.yaml`.
     *
     * @return The parsed config, or a default empty config if the file doesn't exist.
     */
    fun load(): RoutaConfigFile {
        if (!configFile.exists()) {
            return RoutaConfigFile()
        }

        val content = configFile.readText()

        // Try full parse first
        try {
            return yaml.decodeFromString(RoutaConfigFile.serializer(), content)
        } catch (_: Exception) {
            // Fall through to minimal parse
        }

        // Fallback: parse only LLM config (acpAgents format may differ from xiuper)
        try {
            @Serializable
            data class MinimalConfig(
                val active: String = "",
                val configs: List<NamedModelConfig> = emptyList(),
            )
            val minimal = yaml.decodeFromString(MinimalConfig.serializer(), content)
            return RoutaConfigFile(active = minimal.active, configs = minimal.configs)
        } catch (e: Exception) {
            System.err.println("Warning: Failed to parse ~/.autodev/config.yaml: ${e.message}")
            return RoutaConfigFile()
        }
    }

    /**
     * Get the active model configuration.
     */
    fun getActiveModelConfig(): NamedModelConfig? {
        val config = load()
        if (config.active.isEmpty() || config.configs.isEmpty()) {
            return config.configs.firstOrNull()
        }
        return config.configs.find { it.name == config.active }
            ?: config.configs.firstOrNull()
    }

    /**
     * Check if a valid config exists.
     */
    fun hasConfig(): Boolean {
        val active = getActiveModelConfig() ?: return false
        return when {
            active.provider.equals("ollama", ignoreCase = true) -> active.model.isNotEmpty()
            else -> active.apiKey.isNotEmpty() && active.model.isNotEmpty()
        }
    }

    /**
     * Get the active ACP agent config for CRAFTER.
     */
    fun getActiveCrafterConfig(): Pair<String, AcpAgentConfig>? {
        val config = load()
        val key = config.activeCrafter ?: return config.acpAgents.entries.firstOrNull()?.toPair()
        val agentConfig = config.acpAgents[key] ?: return null
        return key to agentConfig
    }

    /**
     * Check if an ACP agent is configured for CRAFTER.
     */
    fun hasAcpCrafter(): Boolean {
        val config = load()
        return config.acpAgents.values.any { it.isConfigured() }
    }

    /**
     * Get all configured ACP agents.
     */
    fun getAcpAgents(): Map<String, AcpAgentConfig> {
        return load().acpAgents.filter { it.value.isConfigured() }
    }

    /**
     * Get the config file path for display/debugging.
     */
    fun getConfigPath(): String = configFile.absolutePath
}
