package com.github.phodal.acpmanager.skills

import com.github.phodal.acpmanager.ui.command.SkillCommand
import com.github.phodal.acpmanager.ui.slash.SlashCommandRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import java.nio.file.*
import java.nio.file.WatchEvent.Kind

private val log = logger<SkillDiscovery>()

/**
 * Discovers and registers Claude Skills as slash commands.
 *
 * Features:
 * - Auto-discovers personal and project skills on startup
 * - Registers each skill as a /skill-name command
 * - Respects invocation type (USER, CLAUDE, BOTH)
 * - Supports hot reload when SKILL.md files change
 * - Handles skill name conflicts (project > personal)
 */
class SkillDiscovery(
    private val projectBasePath: String,
    private val registry: SlashCommandRegistry,
    private val scope: CoroutineScope
) : Disposable {

    private val watcherJob: Job?
    private val registeredSkills = mutableMapOf<String, SkillDefinition>()

    init {
        // Start file watcher and initial discovery in background (avoid blocking EDT)
        watcherJob = scope.launch(Dispatchers.IO) {
            // Initial discovery on startup - run on IO dispatcher
            discoverAndRegisterSkills()
            
            // Start file watcher for hot reload
            watchSkillDirectories()
        }
    }

    /**
     * Discover and register all skills from personal and project sources.
     * Handles priority: project > personal (higher priority overrides lower)
     */
    private fun discoverAndRegisterSkills() {
        try {
            log.info("Starting skill discovery...")
            
            // Load personal skills first (lower priority)
            val personalSkills = SkillLoader.loadPersonalSkills()
            log.info("Found ${personalSkills.size} personal skills")
            
            // Load project skills (higher priority, will override personal)
            val projectSkills = SkillLoader.loadProjectSkills(projectBasePath)
            log.info("Found ${projectSkills.size} project skills")
            
            // Merge skills: project > personal
            val allSkills = mutableMapOf<String, SkillDefinition>()
            
            // Add personal skills first
            personalSkills.forEach { skill ->
                allSkills[skill.name] = skill
            }
            
            // Override with project skills
            projectSkills.forEach { skill ->
                allSkills[skill.name] = skill
            }
            
            // Unregister skills that no longer exist
            val removedSkills = registeredSkills.keys - allSkills.keys
            removedSkills.forEach { name ->
                unregisterSkill(name)
            }
            
            // Register all current skills
            allSkills.forEach { (_, skill) ->
                registerSkill(skill)
            }
            
            registeredSkills.clear()
            registeredSkills.putAll(allSkills)
            
            log.info("Skill discovery complete: ${allSkills.size} skills registered")
        } catch (e: Exception) {
            log.warn("Failed to discover skills: ${e.message}", e)
        }
    }

    /**
     * Register a single skill as a slash command.
     * Only registers skills that are user-invocable (USER or BOTH).
     * CLAUDE-only skills are not registered in the command registry.
     */
    private fun registerSkill(skill: SkillDefinition) {
        try {
            // Only register skills that are visible in user completion
            if (!SkillCommand.isVisibleInCompletion(skill)) {
                log.debug("Skipping CLAUDE-only skill: ${skill.name}")
                return
            }

            val command = SkillCommand.fromSkill(skill)
            registry.register(command)
            log.debug("Registered skill command: /${skill.name}")
        } catch (e: Exception) {
            log.warn("Failed to register skill ${skill.name}: ${e.message}", e)
        }
    }

    /**
     * Unregister a skill command.
     */
    private fun unregisterSkill(skillName: String) {
        try {
            registry.unregister(skillName)
            log.debug("Unregistered skill command: /$skillName")
        } catch (e: Exception) {
            log.warn("Failed to unregister skill $skillName: ${e.message}", e)
        }
    }

    /**
     * Watch skill directories for changes and reload on modification.
     */
    private suspend fun watchSkillDirectories() {
        try {
            val watchService = FileSystems.getDefault().newWatchService()
            
            // Helper to register skill subdirectories for modification events
            fun registerSkillSubdirectories(skillsRoot: Path) {
                if (!Files.exists(skillsRoot) || !Files.isDirectory(skillsRoot)) {
                    return
                }
                try {
                    Files.list(skillsRoot).use { stream ->
                        stream.filter { Files.isDirectory(it) }
                            .forEach { subdir ->
                                subdir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
                                log.debug("Watching skill subdirectory: $subdir")
                            }
                    }
                } catch (e: Exception) {
                    log.warn("Failed to register subdirectories under $skillsRoot: ${e.message}", e)
                }
            }
            
            // Watch personal skills directory
            val personalPath = Paths.get(System.getProperty("user.home"), ".claude", "skills")
            if (Files.exists(personalPath) && Files.isDirectory(personalPath)) {
                personalPath.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                )
                log.debug("Watching personal skills directory: $personalPath")
                registerSkillSubdirectories(personalPath)
            }
            
            // Watch project skills directory
            val projectPath = Paths.get(projectBasePath, ".claude", "skills")
            if (Files.exists(projectPath) && Files.isDirectory(projectPath)) {
                projectPath.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                )
                log.debug("Watching project skills directory: $projectPath")
                registerSkillSubdirectories(projectPath)
            }
            
            // Poll for changes
            while (scope.isActive) {
                val key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                val watchedDir = key.watchable() as? Path
                
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue
                    }
                    
                    @Suppress("UNCHECKED_CAST")
                    val contextPath = (event as WatchEvent<Path>).context()
                    val fullPath = if (watchedDir != null) watchedDir.resolve(contextPath) else contextPath
                    
                    when (kind) {
                        StandardWatchEventKinds.ENTRY_CREATE -> {
                            log.debug("Detected created path: $fullPath")
                            // If a new skill directory is created, start watching it
                            if (Files.exists(fullPath) && Files.isDirectory(fullPath)) {
                                try {
                                    fullPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
                                    log.debug("Watching newly created subdirectory: $fullPath")
                                } catch (e: Exception) {
                                    log.warn("Failed to watch $fullPath: ${e.message}", e)
                                }
                            }
                            // Reload to pick up new skills
                            delay(500)
                            discoverAndRegisterSkills()
                        }
                        
                        StandardWatchEventKinds.ENTRY_DELETE -> {
                            log.debug("Detected deleted path: $fullPath")
                            // Reload to remove deleted skills
                            delay(500)
                            discoverAndRegisterSkills()
                        }
                        
                        StandardWatchEventKinds.ENTRY_MODIFY -> {
                            log.debug("Detected modification: $fullPath")
                            // Only reload when SKILL.md is modified
                            if (fullPath.fileName.toString().equals("SKILL.md", ignoreCase = true)) {
                                delay(500)
                                discoverAndRegisterSkills()
                            }
                        }
                    }
                }
                
                if (!key.reset()) {
                    log.warn("Watch key no longer valid for: $watchedDir")
                }
            }
            
            watchService.close()
        } catch (e: Exception) {
            if (e !is CancellationException) {
                log.warn("Error watching skill directories: ${e.message}", e)
            }
        }
    }

    /**
     * Get all registered skills.
     */
    fun getRegisteredSkills(): Map<String, SkillDefinition> {
        return registeredSkills.toMap()
    }

    /**
     * Get a skill by name.
     */
    fun getSkill(name: String): SkillDefinition? {
        return registeredSkills[name]
    }

    override fun dispose() {
        watcherJob?.cancel()
    }
}

