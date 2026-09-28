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
    }

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        // App 外部文件目录，文件管理器可访问，无需额外权限
        val dir = context.getExternalFilesDir(null)?.resolve(FileFolders.SKILLS)
            ?: context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /**
     * 额外扫描目录：内部存储/Rikkahub/skills/
     * 如果存在且有权限，一并加载
     */
    fun getPublicSkillsDir(): File? {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Rikkahub/skills")
        return if (dir.exists() && dir.canRead()) dir else null
    }

    /**
     * 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能。
     */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> {
        return root.listFiles()
            // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir, builtin)
            }
            // 文件系统顺序不保证稳定：skill 顺序会进 use_skill 工具的 systemPrompt，
            // 乱序会让相同设置在不同进程/重启后前缀不同，打断提示词缓存
            ?.sortedBy { it.name }
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
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        // 目录不存在时 deleteRecursively 也返回 true，需提前拦截，避免误清理内置技能的启用状态
        if (!skillDir.exists()) return@withContext false
        val deleted = skillDir.deleteRecursively()
        // 删除的是覆盖内置技能的同名用户技能时，内置技能会重新生效，保留启用状态
        if (deleted && listBuiltinSkills().none { it.name == name }) {
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
        val parent = target.parentFile ?: return false
        // 先写同目录临时文件再 rename 覆盖，避免写到一半失败时损坏原文件；
        // IO 异常（如 mkdirs 失败导致 FileNotFoundException）转为返回 false，不向调用方抛出
        val tempFile = parent.resolve(".${target.name}.tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) return false
            tempFile.writeText(content)
            tempFile.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
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
        return target.delete()
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

    private fun parseSkillFile(skillFile: File, skillDir: File, builtin: Boolean = false): SkillMetadata? {
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
                builtin = builtin,
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
    /** 内置技能，来自 assets 解压，只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
