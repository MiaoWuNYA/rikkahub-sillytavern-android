package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
        private const val SKILL_CACHE_TTL_MS = 3000L
    }

    fun getSkillsDir(): File {
        // App 外部文件目录，文件管理器可访问，无需额外权限
        val dir = context.getExternalFilesDir(null)?.resolve(FileFolders.SKILLS)
            ?: context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 额外扫描目录：内部存储/Rikkahub/skills/
     * 如果存在且有权限，一并加载
     */
    fun getPublicSkillsDir(): File? {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Rikkahub/skills")
        return if (dir.exists() && dir.canRead()) dir else null
    }

    // 磁盘扫描缓存：一次发送里 createSkillTools 和 SkillAutoTriggerTransformer 会各扫一遍，
    // 外部存储上目录遍历 + SKILL.md frontmatter 解析可能耗时数十到数百毫秒
    @Volatile
    private var skillsCache: List<SkillMetadata>? = null
    private var skillsCacheAt: Long = 0L

    fun listSkills(): List<SkillMetadata> {
        val now = System.currentTimeMillis()
        skillsCache?.let { cached ->
            if (now - skillsCacheAt < SKILL_CACHE_TTL_MS) return cached
        }
        val skills = scanSkills()
        synchronized(this) {
            skillsCache = skills
            skillsCacheAt = now
        }
        return skills
    }

    /** 保存/删除/改名后立即失效，避免 UI 看到旧列表 */
    fun invalidateSkillsCache() {
        synchronized(this) {
            skillsCache = null
            skillsCacheAt = 0L
        }
    }

    private fun scanSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        val meta = parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
        invalidateSkillsCache()
        return meta
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            invalidateSkillsCache()
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        invalidateSkillsCache()
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            invalidateSkillsCache()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFileBytesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        val deleted = target.delete()
        if (deleted) invalidateSkillsCache()
        return deleted
    }

    /** 删除 skill 内的目录（递归删除） */
    fun deleteSkillDir(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        if (!target.isDirectory) return false
        return target.deleteRecursively()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
                ?: parsePluginManifest(skillDir)?.name?.takeIf { it.isNotBlank() }
                ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
                ?: parsePluginManifest(skillDir)?.description?.takeIf { it.isNotBlank() }
                ?: return null
            // plugin.json 补充元数据
            val plugin = parsePluginManifest(skillDir)
            val commands = listCommands(skillDir)
            // 自动发现子目录文件
            val linked = discoverLinkedFiles(skillDir)
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = SkillFrontmatterParser.parseAllowedTools(frontmatter["allowed-tools"])
                    ?: plugin?.allowedTools ?: emptyList(),
                userInvocable = frontmatter["user-invocable"]?.toBooleanStrictOrNull() ?: false,
                disableModelInvocation = frontmatter["disable-model-invocation"]?.toBooleanStrictOrNull() ?: false,
                triggers = frontmatter["triggers"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toList()
                    ?: plugin?.triggers ?: emptyList(),
                category = frontmatter["category"] ?: plugin?.category,
                injectPosition = frontmatter["inject_position"] ?: plugin?.injectPosition,
                version = frontmatter["version"] ?: plugin?.version,
                author = frontmatter["author"] ?: plugin?.author?.name,
                linkedFiles = linked,
                skillDir = skillDir,
                commands = commands,
                mcpServers = (plugin?.mcpServers ?: emptyList()) + parseMcpJson(skillDir),
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }

    /**
     * 扫描 skill 目录下的 references/ templates/ scripts/ assets/ 子目录，
     * 返回 linked_files 映射。
     */
    private fun discoverLinkedFiles(skillDir: File): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        val subdirs = listOf("references", "templates", "scripts", "assets", "examples", "hooks", "agents", "evals")
        for (sub in subdirs) {
            val dir = skillDir.resolve(sub)
            if (!dir.isDirectory) continue
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(dir).path }  // 相对于子目录，用于 use_skill 拼接
                .toList()
            if (files.isNotEmpty()) {
                result[sub] = files
            }
        }
        return result
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val userInvocable: Boolean = false,          // 用户可主动调用（/skill name）
    val disableModelInvocation: Boolean = false,  // 纯脚本不调模型
    val triggers: List<String> = emptyList(),          // 自动触发关键词
    val category: String? = null,                      // 分类标签
    val injectPosition: String? = null,                // before_system / after_system / in_chat
    val version: String? = null,                       // 版本号
    val author: String? = null,                        // 作者
    val pinned: Boolean = false,                       // 固定保护，不可删除
    val remoteUrl: String? = null,                     // 远程更新源 URL
    val linkedFiles: Map<String, List<String>> = emptyMap(),
    val commands: List<CommandFile> = emptyList(),
    val mcpServers: List<PluginMcpServer> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
