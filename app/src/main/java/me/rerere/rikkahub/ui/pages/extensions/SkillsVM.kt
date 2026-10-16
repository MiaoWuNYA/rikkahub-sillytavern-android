package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillRegistry
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.LinkedHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SkillsVM(
    private val skillManager: SkillManager,
) : ViewModel(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()
    private val _downloadStatus = MutableStateFlow<String?>(null)
    val downloadStatus = _downloadStatus.asStateFlow()
    val settingsFlow = settingsStore.settingsFlow

    fun setDownloadStatus(status: String?) {
        _downloadStatus.value = status
    }

    fun updateGithubToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { it.copy(githubToken = token) }
        }
    }

    fun refreshSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun togglePin(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val skill = skillManager.listSkills().find { it.name == name } ?: return@launch
            val current = skillManager.readSkillContent(name) ?: return@launch
            val updated = current.replace(
                "pinned: ${!skill.pinned}",
                "pinned: ${skill.pinned}"
            ).let { if (skill.pinned) it else it.replace("pinned: true", "pinned: false") }
            // Toggle pinned in frontmatter
            if ("pinned:" !in current) {
                // Add pinned field after version or description
                val withPin = current.replaceFirst("version:", "pinned: true\nversion:")
                skillManager.saveSkill(name, withPin)
            } else {
                skillManager.saveSkill(name, updated)
            }
            _skills.value = skillManager.listSkills()
        }
    }

    /**
     * 从 ZIP 文件导入 skill 目录
     */
    fun importFromZip(uri: android.net.Uri, context: android.content.Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: run { withContext(Dispatchers.Main) { onResult(false, "无法读取文件") }; return@launch }
                val zipBytes = inputStream.readBytes()
                inputStream.close()

                // 解压并规范化路径
                val entries = extractZipEntries(zipBytes)

                // 找所有 SKILL.md
                val skillMdPaths = entries.keys
                    .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
                    .sorted()
                if (skillMdPaths.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult(false, "ZIP 中未找到 SKILL.md") }
                    return@launch
                }

                // 每个 SKILL.md 对应一个技能，按目录分组
                val skillBasePaths = skillMdPaths.map { it.substringBeforeLast('/', "") }
                val importedNames = mutableListOf<String>()

                for (skillMdPath in skillMdPaths) {
                    val skillContent = String(entries[skillMdPath]!!)
                    val frontmatter = SkillFrontmatterParser.parse(skillContent)
                    val name = frontmatter["name"]?.trim()
                    if (name.isNullOrBlank()) {
                        withContext(Dispatchers.Main) { onResult(false, "$skillMdPath 缺少 name 字段") }
                        return@launch
                    }

                    // 收集属于当前技能的文件（排除嵌套在其他技能目录下的）
                    val basePath = skillMdPath.substringBeforeLast('/', "")
                    val skillFiles = LinkedHashMap<String, ByteArray>()
                    for ((path, content) in entries) {
                        if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                        val relativePath = relativeToSkillBase(path, basePath) ?: continue
                        skillFiles[relativePath] = content
                    }

                    val saved = skillManager.saveSkillFileBytesAtomically(name, skillFiles)
                    if (!saved) {
                        withContext(Dispatchers.Main) { onResult(false, "保存失败：$name") }
                        return@launch
                    }
                    importedNames += name
                }

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.distinct().joinToString(", "))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "导入失败") }
            }
        }
    }

    private fun extractZipEntries(zipBytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        val zipIn = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes))
        while (true) {
            val entry = zipIn.nextEntry ?: break
            try {
                if (!entry.isDirectory) {
                    val normalized = normalizeZipEntryPath(entry.name)
                    if (normalized != null) {
                        entries[normalized] = zipIn.readBytes()
                    }
                }
            } finally {
                zipIn.closeEntry()
            }
        }
        zipIn.close()
        return entries
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != "__MACOSX" }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    /**
     * 从注册表条目下载并安装 skill
     */
    fun installFromRegistry(entry: SkillRegistry.RegistryEntry, onResult: (Boolean, String) -> Unit) {
        importSkillFromGitHub(entry.githubUrl, onResult)
    }

    /**
     * 从本地文件导入 (ZIP 或单个 SKILL.md)
     */
    fun importFromLocalFile(uri: android.net.Uri, context: android.content.Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = uri.lastPathSegment?.lowercase() ?: ""
                // Route .zip to zip importer
                if (fileName.endsWith(".zip")) {
                    importFromZip(uri, context, onResult)
                    return@launch
                }
                if (!fileName.endsWith(".md")) {
                    withContext(Dispatchers.Main) { onResult(false, "不支持的格式，请选择 .zip 或 .md 文件") }
                    return@launch
                }
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无法读取文件") }
                        return@launch
                    }
                val frontmatter = SkillFrontmatterParser.parse(content)
                val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无效的Skill文件，缺少name或description字段") }
                        return@launch
                    }
                val desc = frontmatter["description"]?.takeIf { it.isNotBlank() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无效的Skill文件，缺少name或description字段") }
                        return@launch
                    }
                val saved = skillManager.saveSkill(name, content)
                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) {
                    onResult(saved != null, if (saved != null) name else "保存失败")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "导入失败") }
            }
        }
    }

    /**
     * 从文件夹导入 (OpenDocumentTree)
     * 解析真实路径 → 只读 SKILL.md 取名字 → copyRecursively 全量复制
     */
    fun importFromFolder(uri: android.net.Uri, context: android.content.Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 解析真实目录路径
                var realDir: java.io.File? = null

                // 尝试1: TreeDocumentId
                runCatching {
                    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                    android.util.Log.d("SkillsVM", "TreeDocumentId: $docId")
                    parsePathFromDocumentId(docId)
                }.onSuccess { realDir = it }

                // 尝试2: URI path
                if (realDir == null) {
                    uri.path?.let { p ->
                        val parts = p.split("/")
                        val docIdx = parts.indexOfLast { it == "document" }
                        if (docIdx >= 0 && docIdx + 1 < parts.size) {
                            realDir = parsePathFromDocumentId(parts[docIdx + 1])
                        }
                    }
                }

                // 尝试3: getDocumentId
                if (realDir == null) {
                    runCatching {
                        val docId = android.provider.DocumentsContract.getDocumentId(uri)
                        parsePathFromDocumentId(docId)
                    }.onSuccess { realDir = it }
                }

                if (realDir == null || !realDir.isDirectory) {
                    android.util.Log.e("SkillsVM", "无法解析目录路径, URI=$uri")
                    withContext(Dispatchers.Main) { onResult(false, "无法读取文件夹，请确认已授权访问权限") }
                    return@launch
                }

                // 找到 SKILL.md（可能在子目录中），只读它来拿 skill 名字
                val skillMd = realDir.walkTopDown().firstOrNull { it.name == "SKILL.md" }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "文件夹中未找到 SKILL.md") }
                        return@launch
                    }

                val frontmatter = SkillFrontmatterParser.parse(skillMd.readText())
                val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "SKILL.md 缺少 name 字段") }
                        return@launch
                    }
                val desc = frontmatter["description"]?.takeIf { it.isNotBlank() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "SKILL.md 缺少 description 字段") }
                        return@launch
                    }

                // SKILL.md 所在的目录就是 skill 根目录
                val sourceDir = skillMd.parentFile!!

                // 复制到 skills 目录
                val skillsDir = skillManager.getSkillsDir()
                val targetDir = skillsDir.resolve(name)
                if (targetDir.exists()) targetDir.deleteRecursively()

                sourceDir.copyRecursively(targetDir, overwrite = true)
                android.util.Log.d("SkillsVM", "复制完成: $sourceDir → $targetDir")

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: Exception) {
                android.util.Log.e("SkillsVM", "importFromFolder 异常: ${e.message}", e)
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "导入失败") }
            }
        }
    }

    /** 从 DocumentsContract document ID 解析为真实 File */
    private fun parsePathFromDocumentId(docId: String): java.io.File? {
        // docId 格式: "primary:Download/myskill" 或 "home:Documents/myskill"
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val storage = when (parts[0]) {
            "primary" -> android.os.Environment.getExternalStorageDirectory()
            "home" -> android.os.Environment.getExternalStorageDirectory()
            else -> {
                // 尝试 SD 卡等
                java.io.File("/storage/${parts[0]}")
                    .takeIf { it.exists() }
                    ?: return null
            }
        }
        val decodedPath = parts[1].replace("%2F", "/").replace("%3A", ":")
        return java.io.File(storage, decodedPath)
    }

    /**
     * 第一步: 扫描仓库中所有可用的 skill (Git Trees API, 1-2次请求)
     */
    fun scanSkillsFromGitHub(
        repoUrl: String,
        onResult: (Boolean, List<GitHubSkillInfo>, String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "无效的链接，支持格式：github.com/owner/repo 或 github.com/owner/repo/tree/branch/路径") }
                    return@launch
                }

                // 解析默认分支
                val branch = resolveBranch(info.owner, info.repo, info.branch)
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, emptyList(), "无法获取仓库默认分支") }
                        return@launch
                    }

                // Git Trees API - 一次请求拿全部文件列表
                val treeJson = downloadText(
                    "https://api.github.com/repos/${info.owner}/${info.repo}/git/trees/$branch?recursive=1"
                ) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "读取仓库目录失败") }
                    return@launch
                }

                val treeJsonObj = org.json.JSONObject(treeJson)
                val tree = treeJsonObj.getJSONArray("tree")
                val truncated = treeJsonObj.optBoolean("truncated", false)

                if (truncated) {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "仓库文件过多，暂不支持大仓库扫描") }
                    return@launch
                }

                // 找出所有 SKILL.md 的路径
                val skillMdPaths = mutableListOf<String>()
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    val path = item.optString("path", "")
                    if (path.endsWith("SKILL.md") || path.endsWith("/SKILL.md")) {
                        skillMdPaths.add(path)
                    }
                }

                // 过滤: 如果 URL 指定了子路径，只保留该路径下的
                val filteredPaths = if (info.path.isBlank()) {
                    skillMdPaths
                } else {
                    skillMdPaths.filter { it.startsWith("${info.path}/") }
                }

                if (filteredPaths.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "未找到 SKILL.md") }
                    return@launch
                }

                // 本地已安装列表和安装源信息
                val installedNames = skillManager.listSkills().map { it.name }.toSet()
                val sourceRepoUrl = repoUrl.trimEnd('/').substringBefore("/tree/")

                // 并发下载每个 SKILL.md 获取 name/description
                val results = coroutineScope {
                    filteredPaths.map { mdPath ->
                        async(Dispatchers.IO) {
                            val dirPath = mdPath.removeSuffix("SKILL.md").trimEnd('/')
                            val dlUrl = "https://raw.githubusercontent.com/${info.owner}/${info.repo}/$branch/$mdPath"
                            val content = downloadText(dlUrl) ?: return@async null
                            val fm = SkillFrontmatterParser.parse(content)
                            val name = fm["name"] ?: dirPath.split("/").last().ifBlank { "unknown" }
                            val desc = fm["description"] ?: ""
                            val locallyInstalled = name in installedNames
                            val installedSource = if (locallyInstalled) getSkillSource(name) else null
                            val fromSameRepo = installedSource?.repoUrl == sourceRepoUrl
                            // 目录哈希对比，检测是否有更新
                            val newDirHash = computeDirHash(tree, dirPath)
                            val hasUpdate = fromSameRepo && installedSource != null &&
                                    installedSource.skillShas[name] != newDirHash
                            val isNew = !locallyInstalled
                            GitHubSkillInfo(
                                name = name, description = desc,
                                dirPath = dirPath, mdPath = mdPath,
                                blobSha = newDirHash,
                                hasUpdate = hasUpdate, isNew = isNew,
                            )
                        }
                    }.awaitAll()
                }
                val skills = results.filterNotNull()

                withContext(Dispatchers.Main) {
                    onResult(true, skills, "${info.owner}/${info.repo}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, emptyList(), e.message ?: "扫描失败")
                }
            }
        }
    }

    /**
     * 第二步: 下载指定 skill 目录的全部文件
     */
    fun downloadSkillFromGitHub(
        repoUrl: String,
        skill: GitHubSkillInfo,
        onResult: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无效的链接，支持格式：github.com/owner/repo 或 github.com/owner/repo/tree/branch/路径") }
                    return@launch
                }

                val branch = resolveBranch(info.owner, info.repo, info.branch)
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无法获取仓库默认分支") }
                        return@launch
                    }

                // Git Trees API - 一次拿全部文件路径
                val treeJson = downloadText(
                    "https://api.github.com/repos/${info.owner}/${info.repo}/git/trees/$branch?recursive=1"
                ) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "读取仓库目录失败") }
                    return@launch
                }
                val tree = org.json.JSONObject(treeJson).getJSONArray("tree")
                val dirPath = skill.dirPath.trimEnd('/')
                val prefix = if (dirPath.isBlank()) "" else "$dirPath/"

                val files = mutableListOf<Pair<String, String>>()
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    val path = item.optString("path", "")
                    if (item.optString("type") == "blob" && path.startsWith(prefix)) {
                        val relativePath = path.removePrefix(prefix)
                        val downloadUrl = "https://raw.githubusercontent.com/${info.owner}/${info.repo}/$branch/$path"
                        files.add(relativePath to downloadUrl)
                    }
                }
                if (files.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult(false, "目录中未找到文件") }
                    return@launch
                }

                val fileContents = LinkedHashMap<String, String>()
                val sem = Semaphore(5)
                val results = coroutineScope {
                    files.map { (path, url) ->
                        async(Dispatchers.IO) {
                            sem.withPermit { path to downloadText(url) }
                        }
                    }.awaitAll()
                }
                for ((relativePath, content) in results) {
                    if (content == null) {
                        withContext(Dispatchers.Main) { onResult(false, "下载文件失败：$relativePath") }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val saved = skillManager.saveSkillFilesAtomically(skill.name, fileContents)
                if (!saved) {
                    withContext(Dispatchers.Main) { onResult(false, "保存失败") }
                    return@launch
                }

                _skills.value = skillManager.listSkills()
                // 自动启用到当前助手
                val currentSettings = settingsStore.settingsFlow.value
                if (currentSettings.init) { /* dummy, 跳过 */ }
                else {
                    val updated = currentSettings.copy(
                        assistants = currentSettings.assistants.map { a ->
                            if (a.id == currentSettings.assistantId)
                                a.copy(enabledSkills = a.enabledSkills + skill.name)
                            else a
                        }
                    )
                    settingsStore.update(updated)
                }
                // 保存安装源信息（目录哈希），方便后续检查更新
                saveSkillSourceSha(skill.name,
                    repoUrl = repoUrl.trimEnd('/').substringBefore("/tree/"),
                    branch = branch,
                    dirHash = computeDirHash(tree, skill.dirPath)
                )
                withContext(Dispatchers.Main) { onResult(true, skill.name) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "下载失败") }
            }
        }
    }

    // region Source Tracking & Update

    data class SkillSourceInfo(
        val repoUrl: String,                         // https://github.com/owner/repo
        val branch: String,                          // "main"
        val skillShas: Map<String, String> = emptyMap(),  // {skillName: blobSha}
    )

    /** 读取 skill 的安装源信息 */
    fun getSkillSource(skillName: String): SkillSourceInfo? {
        val sourceFile = skillManager.getSkillDir(skillName)?.resolve(".rikkahub_source.json")
            ?: return null
        if (!sourceFile.exists()) return null
        return try {
            val json = org.json.JSONObject(sourceFile.readText())
            val shasJson = json.optJSONObject("skillShas")
            val skillShas = if (shasJson != null) {
                val map = mutableMapOf<String, String>()
                shasJson.keys().forEach { key -> map[key] = shasJson.optString(key, "") }
                map
            } else {
                // 兼容旧格式: 只有 commitSha
                val oldSha = json.optString("commitSha", "")
                if (oldSha.isNotBlank()) mapOf(skillName to oldSha) else emptyMap()
            }
            SkillSourceInfo(
                repoUrl = json.optString("repoUrl", ""),
                branch = json.optString("branch", ""),
                skillShas = skillShas,
            ).takeIf { it.repoUrl.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /** 计算 skill 目录的文件哈希（所有文件路径+sha → hash） */
    private fun computeDirHash(tree: org.json.JSONArray, dirPath: String): String {
        val prefix = dirPath.trimEnd('/').let { if (it.isBlank()) "" else "$it/" }
        val items = mutableListOf<String>()
        for (i in 0 until tree.length()) {
            val item = tree.getJSONObject(i)
            val path = item.optString("path", "")
            val sha = item.optString("sha", "")
            if (item.optString("type") == "blob" && path.startsWith(prefix) && sha.isNotBlank()) {
                items.add("${path.removePrefix(prefix)}:$sha")
            }
        }
        items.sort()
        return Integer.toHexString(items.joinToString("|").hashCode())
    }

    /** 更新 skill 的源哈希（安装/更新后调用，比对整个目录） */
    private fun saveSkillSourceSha(skillName: String, repoUrl: String, branch: String, dirHash: String) {
        val dir = skillManager.getSkillDir(skillName) ?: return
        val sourceFile = dir.resolve(".rikkahub_source.json")
        val json = if (sourceFile.exists()) {
            try { org.json.JSONObject(sourceFile.readText()) } catch (_: Exception) { org.json.JSONObject() }
        } else {
            org.json.JSONObject()
        }
        json.put("repoUrl", repoUrl)
        json.put("branch", branch)
        json.remove("commitSha") // 清理旧字段
        val skillShas = json.optJSONObject("skillShas") ?: org.json.JSONObject()
        skillShas.put(skillName, dirHash)
        json.put("skillShas", skillShas)
        sourceFile.writeText(json.toString(2))
    }

    /** 获取仓库的 Git Trees */
    private suspend fun fetchRepoTree(owner: String, repo: String, branch: String): org.json.JSONArray? {
        val treeJson = downloadText(
            "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
        ) ?: return null
        val treeJsonObj = org.json.JSONObject(treeJson)
        val tree = treeJsonObj.getJSONArray("tree")
        val truncated = treeJsonObj.optBoolean("truncated", false)
        return if (truncated) null else tree
    }

    /**
     * 检查单个 skill 的更新
     * onResult: (hasUpdate: Boolean, message: String)
     */
    fun checkForUpdate(skillName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = getSkillSource(skillName) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "未找到安装源信息") }
                    return@launch
                }
                val info = parseGitHubUrl(source.repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无法解析仓库链接") }
                    return@launch
                }
                val branch = resolveBranch(info.owner, info.repo, source.branch)
                    ?: run { withContext(Dispatchers.Main) { onResult(false, "无法获取仓库分支") }; return@launch }
                val tree = fetchRepoTree(info.owner, info.repo, branch) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无法读取仓库文件列表") }; return@launch
                }
                
                // 对比每个已安装 skill 的目录哈希
                var hasUpdate = false
                for ((name, oldDirHash) in source.skillShas) {
                    // 找该 skill 在树中的 SKILL.md
                    for (i in 0 until tree.length()) {
                        val item = tree.getJSONObject(i)
                        val path = item.optString("path", "")
                        if (path.endsWith("SKILL.md")) {
                            val dlUrl = "https://raw.githubusercontent.com/${info.owner}/${info.repo}/$branch/$path"
                            val content = downloadText(dlUrl) ?: continue
                            val fm = SkillFrontmatterParser.parse(content)
                            if (fm["name"] == name) {
                                val dirPath = path.removeSuffix("SKILL.md").trimEnd('/')
                                val newDirHash = computeDirHash(tree, dirPath)
                                if (oldDirHash != newDirHash) {
                                    hasUpdate = true
                                }
                                break
                            }
                        }
                    }
                    if (hasUpdate) break
                }
                if (hasUpdate) {
                    withContext(Dispatchers.Main) { onResult(true, "发现新版本") }
                } else {
                    withContext(Dispatchers.Main) { onResult(false, "已是最新版本") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "检查更新失败") }
            }
        }
    }

    /**
     * 扫描源仓库并标记哪些 skill 有更新
     * onResult: (List<GitHubSkillInfo>, repoUrl: String) — GitHubSkillInfo 中 hasUpdate 标记
     */
    fun scanForUpdates(
        skillName: String,
        onResult: (Boolean, List<GitHubSkillInfo>, String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = getSkillSource(skillName) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "未找到安装源信息") }
                    return@launch
                }
                val info = parseGitHubUrl(source.repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "无效仓库链接") }
                    return@launch
                }
                val branch = resolveBranch(info.owner, info.repo, source.branch)
                    ?: run { withContext(Dispatchers.Main) { onResult(false, emptyList(), "无法获取分支") }; return@launch }
                val tree = fetchRepoTree(info.owner, info.repo, branch) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, emptyList(), "读取仓库失败") }; return@launch
                }

                val results = mutableListOf<GitHubSkillInfo>()
                // 收集同仓库所有已安装 skill 的 SHA，用于检测有更新的 skill
                val allSkillShas = source.skillShas.toMutableMap()
                for (installed in skillManager.listSkills()) {
                    if (installed.name == skillName) continue
                    val otherSource = getSkillSource(installed.name)
                    if (otherSource?.repoUrl == source.repoUrl) {
                        allSkillShas.putAll(otherSource.skillShas)
                    }
                }

                // 本地所有已安装 skill 名
                val installedNames = skillManager.listSkills().map { it.name }.toSet()

                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    val path = item.optString("path", "")
                    val blobSha = item.optString("sha", "")
                    if (path.endsWith("SKILL.md")) {
                        val dlUrl = "https://raw.githubusercontent.com/${info.owner}/${info.repo}/$branch/$path"
                        val content = downloadText(dlUrl) ?: continue
                        val fm = SkillFrontmatterParser.parse(content)
                        val name = fm["name"] ?: path.split("/").dropLast(1).lastOrNull() ?: "unknown"
                        val desc = fm["description"] ?: ""
                        val dirPath = path.removeSuffix("SKILL.md").trimEnd('/')
                        val oldDirHash = allSkillShas[name]
                        val locallyInstalled = name in installedNames
                        val newDirHash = computeDirHash(tree, dirPath)
                        val hasUpdate = oldDirHash != null && oldDirHash != newDirHash
                        // 只有本地真没装过的才算新增，不依赖哈希记录
                        val isNew = !locallyInstalled
                        results.add(GitHubSkillInfo(
                            name = name, description = desc,
                            dirPath = dirPath, mdPath = path, blobSha = newDirHash,
                            hasUpdate = hasUpdate, isNew = isNew,
                        ))
                    }
                }
                withContext(Dispatchers.Main) {
                    onResult(true, results, "${info.owner}/${info.repo}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, emptyList(), e.message ?: "扫描失败") }
            }
        }
    }

    /**
     * 更新单个 skill 到最新版本
     */
    fun updateSkillToLatest(skillName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = getSkillSource(skillName) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "未找到安装源信息") }
                    return@launch
                }
                val info = parseGitHubUrl(source.repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无效仓库链接") }
                    return@launch
                }
                val branch = resolveBranch(info.owner, info.repo, source.branch)
                    ?: run { withContext(Dispatchers.Main) { onResult(false, "无法获取分支") }; return@launch }
                val tree = fetchRepoTree(info.owner, info.repo, branch) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "读取仓库失败") }; return@launch
                }

                // 找 SKILL.md 获取目录路径和新的 blob sha
                var dirPath: String? = null
                var newBlobSha: String? = null
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    val path = item.optString("path", "")
                    if (path.endsWith("SKILL.md")) {
                        val dlUrl = "https://raw.githubusercontent.com/${info.owner}/${info.repo}/$branch/$path"
                        val content = downloadText(dlUrl) ?: continue
                        val fm = SkillFrontmatterParser.parse(content)
                        if (fm["name"] == skillName) {
                            dirPath = path.removeSuffix("SKILL.md").trimEnd('/')
                            newBlobSha = item.optString("sha")
                            break
                        }
                    }
                }
                if (dirPath == null) {
                    withContext(Dispatchers.Main) { onResult(false, "仓库中未找到此 skill") }
                    return@launch
                }

                val fileContents = downloadSkillFiles(info.owner, info.repo, branch, dirPath, tree)
                    ?: run { withContext(Dispatchers.Main) { onResult(false, "下载失败") }; return@launch }

                if (!skillManager.saveSkillFilesAtomically(skillName, fileContents)) {
                    withContext(Dispatchers.Main) { onResult(false, "保存失败") }; return@launch
                }

                if (newBlobSha != null) {
                    saveSkillSourceSha(skillName, source.repoUrl, branch,
                        computeDirHash(tree, dirPath))
                }

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(true, skillName) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "更新失败") }
            }
        }
    }

    /** 兼容旧 API */
    fun updateSkillFromGitHub(skillName: String, onResult: (Boolean, String) -> Unit) {
        updateSkillToLatest(skillName, onResult)
    }

    /** 下载整个 skill 目录的文件 */
    private suspend fun downloadSkillFiles(
        owner: String, repo: String, branch: String, dirPath: String,
        tree: org.json.JSONArray,
    ): LinkedHashMap<String, String>? {
        val prefix = dirPath.trimEnd('/').let { if (it.isBlank()) "" else "$it/" }

        val files = mutableListOf<Pair<String, String>>()
        for (i in 0 until tree.length()) {
            val item = tree.getJSONObject(i)
            val path = item.optString("path", "")
            if (item.optString("type") == "blob" && path.startsWith(prefix)) {
                val relativePath = path.removePrefix(prefix)
                val downloadUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
                files.add(relativePath to downloadUrl)
            }
        }
        if (files.isEmpty()) return null

        val fileContents = LinkedHashMap<String, String>()
        val sem = Semaphore(5)
        val results = coroutineScope {
            files.map { (path, url) ->
                async(Dispatchers.IO) {
                    sem.withPermit { path to downloadText(url) }
                }
            }.awaitAll()
        }
        for ((relativePath, content) in results) {
            if (content == null) return null
            fileContents[relativePath] = content
        }
        return fileContents
    }

    // endregion

    /** 兼容旧接口: 粘贴的链接正好指向一个 skill 目录时直接下载 */
    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无效的链接，支持格式：github.com/owner/repo 或 github.com/owner/repo/tree/branch/路径") }
                    return@launch
                }
                val branch = resolveBranch(info.owner, info.repo, info.branch)
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无法获取仓库默认分支") }
                        return@launch
                    }

                val files = mutableListOf<Pair<String, String>>()
                val listed = listFilesRecursively(info.owner, info.repo, branch, info.path, info.path, files)
                if (!listed) {
                    withContext(Dispatchers.Main) { onResult(false, "读取 GitHub 目录失败") }
                    return@launch
                }

                val skillMdEntry = files.find { it.first == "SKILL.md" } ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "目录中未找到 SKILL.md") }
                    return@launch
                }

                val skillMdContent = downloadText(skillMdEntry.second) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "下载 SKILL.md 失败，请检查链接或网络") }
                    return@launch
                }

                val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
                val name = frontmatter["name"]
                if (name.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onResult(false, "SKILL.md 格式错误：缺少 name 字段") }
                    return@launch
                }

                val fileContents = LinkedHashMap<String, String>()
                val sem = Semaphore(5)
                val results = coroutineScope {
                    files.map { (path, url) ->
                        async(Dispatchers.IO) {
                            sem.withPermit { path to downloadText(url) }
                        }
                    }.awaitAll()
                }
                for ((relativePath, content) in results) {
                    if (content == null) {
                        withContext(Dispatchers.Main) { onResult(false, "下载文件失败：$relativePath") }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val saved = skillManager.saveSkillFilesAtomically(name, fileContents)
                if (!saved) {
                    withContext(Dispatchers.Main) { onResult(false, "保存失败") }
                    return@launch
                }

                _skills.value = skillManager.listSkills()
                // 自动启用到当前助手
                val currentSettings = settingsStore.settingsFlow.value
                if (!currentSettings.init) {
                    val updated = currentSettings.copy(
                        assistants = currentSettings.assistants.map { a ->
                            if (a.id == currentSettings.assistantId)
                                a.copy(enabledSkills = a.enabledSkills + name)
                            else a
                        }
                    )
                    settingsStore.update(updated)
                }
                // 保存安装源信息（目录哈希）
                val tree2 = fetchRepoTree(info.owner, info.repo, branch)
                if (tree2 != null) {
                    saveSkillSourceSha(name,
                        repoUrl = repoUrl.trimEnd('/').substringBefore("/tree/"),
                        branch = branch,
                        dirHash = computeDirHash(tree2, info.path)
                    )
                }
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String?,  // null = 用户没指定，让 API 自行判断默认分支
        val path: String,
    )

    data class GitHubSkillInfo(
        val name: String,
        val description: String,
        val dirPath: String,  // 目录路径，如 "plugins/skill-creator/skills/skill-creator"
        val mdPath: String,   // SKILL.md 完整路径
        val blobSha: String = "",  // SKILL.md 的 blob SHA，用于更新比对
        val hasUpdate: Boolean = false,  // 该 skill 是否有更新
        val isNew: Boolean = false,      // 仓库有但本地未安装
    )
    /** 从 GitHub URL 解析 owner/repo/branch/path */
    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/').removeSuffix("/SKILL.md")
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        // https://github.com/owner/repo/blob/branch/sub/path
        // https://raw.githubusercontent.com/owner/repo/branch/path
        var u = trimmed
        // raw → 提取 owner/repo/branch/path
        val rawRegex = Regex("""https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)(/.*)?""")
        val rawMatch = rawRegex.matchEntire(u)
        if (rawMatch != null) {
            val owner = rawMatch.groupValues[1]
            val repo = rawMatch.groupValues[2].removeSuffix(".git")
            val branch = rawMatch.groupValues[3]
            val subPath = rawMatch.groupValues[4].trimStart('/').trimEnd('/')
            return GitHubRepoInfo(owner, repo, branch, subPath)
        }
        // blob → 转为 tree
        u = u.replace("/blob/", "/tree/")
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+?)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(u) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        val branch = match.groupValues[3].takeIf { it.isNotBlank() }
        val subPath = match.groupValues[4].trimStart('/').trimEnd('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    /** 解析分支: 用户指定了就用，没指定调 API 查默认分支 */
    private fun resolveBranch(owner: String, repo: String, userBranch: String?): String? {
        if (userBranch != null) return userBranch
        val repoJson = downloadText("https://api.github.com/repos/$owner/$repo") ?: return null
        return org.json.JSONObject(repoJson).optString("default_branch", null)
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String?,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val refParam = if (branch != null) "?ref=$branch" else ""
        val apiPath = dirPath.ifBlank { "" }
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$apiPath$refParam"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private fun downloadText(url: String): String? {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Rikkahub/1.0")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val token = settingsStore.settingsFlow.value.githubToken
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "token $token")
            }
            val code = connection.responseCode
            if (code == 200) {
                val text = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                return text
            }
            connection.disconnect()
            return null
        } catch (e: Exception) {
            return null
        }
    }
}