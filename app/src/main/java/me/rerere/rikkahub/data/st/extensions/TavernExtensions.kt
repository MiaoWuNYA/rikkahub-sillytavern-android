package me.rerere.rikkahub.data.st.extensions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * SillyTavern 第三方扩展（third-party extensions）：安装 / 发现 / 路径解析。
 *
 * 设计依据（"先搜后做"结论，2026-09-22）：
 * - TauriTavern `docs/CurrentState/ThirdPartyExtensions.md`：兼容性的关键是
 *   "把 `/scripts/extensions/third-party/<folder>/<path>` 做成 WebView 可原生加载的真实端点"，
 *   而不是在 runtime 里解释扩展代码；
 * - TauriTavern `client_asset_paths.rs` 的路径安全规则（拒 `..` / `.` / 反斜杠 / 控制字符等）；
 * - 目录布局：所有扩展落在 `filesDir/tavern-extensions/third-party/<folder>/`。
 *
 * 资源 URL 契约（由 TavernRuntimeManager.interceptAsset 伺服）：
 *   /scripts/extensions/third-party/<folder>/<path> → 上述目录中的真实文件
 */

/** 第三方扩展根目录（相对 filesDir）。 */
const val TAVERN_EXTENSIONS_DIR = "tavern-extensions/third-party"

/** 安装包（zip）大小上限。 */
private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024

/** 解压总量上限（防 zip 炸弹）。 */
private const val MAX_EXTRACT_BYTES = 256L * 1024 * 1024

/** 第三方扩展的静态信息（manifest.json 投影 + 目录名）。 */
data class TavernExtensionInfo(
    val folder: String,
    val displayName: String,
    val version: String,
    val author: String,
    val homePage: String,
    val js: String,
    val css: String,
    val loadingOrder: Int,
    val requires: List<String>,
    val hasManifest: Boolean,
    val optional: List<String> = emptyList(),
    val minimumClientVersion: String = "",
    val generateInterceptor: String = "",
    val hooks: Map<String, String> = emptyMap(),
) {
    val requiresNode: Boolean
        get() = requires.any {
            it.equals("node", ignoreCase = true) ||
                it.equals("server", ignoreCase = true) ||
                it.equals("extras", ignoreCase = true)
        } || normalizeExtensionFolderAlias(folder).contains("littlewhitebox")
}

/** 第三方扩展根目录。 */
fun extensionsBaseDir(context: Context): File = File(context.filesDir, TAVERN_EXTENSIONS_DIR)

// ============================================================
// 发现 / 列表
// ============================================================

/** 扫描已安装扩展（按加载顺序排序）。 */
fun listExtensions(context: Context): List<TavernExtensionInfo> =
    listExtensions(extensionsBaseDir(context))

/** 扫描已安装扩展（纯 JVM 可测版本：传入扩展根目录）。 */
fun listExtensions(baseDir: File): List<TavernExtensionInfo> {
    val dirs = baseDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        .orEmpty()
    return dirs.map { dir ->
        val manifest = File(dir, "manifest.json")
        val text = if (manifest.isFile) runCatching { manifest.readText() }.getOrDefault("") else ""
        parseManifest(dir.name, text) ?: TavernExtensionInfo(
            folder = dir.name,
            displayName = dir.name,
            version = "",
            author = "",
            homePage = "",
            js = "",
            css = "",
            loadingOrder = 100,
            requires = emptyList(),
            hasManifest = false,
        )
    }.sortedWith(compareBy({ it.loadingOrder }, { it.folder.lowercase() }))
}

/**
 * 解析 manifest.json（ST 契约：根必须是 JSON object）。
 *
 * 字段对齐 ST 1.18：`display_name` / `version` / `author` / `homePage` /
 * `js` / `css` / `loading_order` / `requires`。
 * 解析失败返回 null（调用方决定降级策略）。
 */
fun parseManifest(folder: String, text: String): TavernExtensionInfo? {
    if (text.isBlank()) return null
    val root = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null

    fun str(key: String): String = (root[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    val requires = (root["requires"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    val optional = (root["optional"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    val hooks = (root["hooks"] as? JsonObject)?.entries
        ?.mapNotNull { (k, v) ->
            val fn = (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            k to fn
        }
        ?.toMap()
        .orEmpty()

    return TavernExtensionInfo(
        folder = folder,
        displayName = str("display_name").ifBlank { folder },
        version = str("version"),
        author = str("author"),
        homePage = str("homePage").ifBlank { str("homepage") },
        js = str("js"),
        css = str("css"),
        loadingOrder = (root["loading_order"] as? JsonPrimitive)?.intOrNull ?: 100,
        requires = requires,
        hasManifest = true,
        optional = optional,
        minimumClientVersion = str("minimum_client_version").ifBlank { str("minimumClientVersion") },
        generateInterceptor = str("generate_interceptor").ifBlank { str("generateInterceptor") },
        hooks = hooks,
    )
}

// ============================================================
// 路径安全（对齐 TauriTavern client_asset_paths.rs 规则）
// ============================================================

/** 单个路径段是否安全（拒 `.` / `..` / 分隔符 / 控制字符 / Windows 保留字符）。 */
fun isSafeExtensionSegment(segment: String): Boolean =
    segment.isNotEmpty() &&
        segment != "." &&
        segment != ".." &&
        segment.none {
            it == '\\' || it == '/' || it == ':' || it == '*' || it == '?' ||
                it == '"' || it == '<' || it == '>' || it == '|' ||
                it.code < 0x20 || it.code == 0x7F
        }

/** 目录名净化：非法字符转 `_`；去首尾空白与点；空结果返回 null。 */
fun sanitizeFolderName(raw: String): String? {
    val cleaned = raw.trim().map { c ->
        if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|'
        ) '_' else c
    }.joinToString("")
    val name = cleaned.trim().trim('.')
    return name.takeIf { it.isNotEmpty() }
}

private val EXTENSION_FOLDER_SUFFIX_REGEX =
    Regex("(?i)-(?:main|master|dev|release|v?\\d+(?:\\.\\d+)*(?:-[a-z0-9._-]+)?)$")

/**
 * 归一化扩展目录别名：剥离 GitHub 发行版 / 分支压缩包常见的 `-main`、`-master`、`-1.16`、`-v2.2.0` 等后缀，
 * 使扩展硬编码的 `/scripts/extensions/third-party/ST-Prompt-Template/...` 能命中解压后的 `ST-Prompt-Template-1.16` 目录。
 */
fun normalizeExtensionFolderAlias(folder: String): String =
    folder.trim().replace(EXTENSION_FOLDER_SUFFIX_REGEX, "").lowercase()

/**
 * 把 (folder, relativePath) 解析为扩展目录中的真实文件。
 * 若精确目录不存在，则按 [normalizeExtensionFolderAlias] 匹配同别名已安装目录。
 * 任何不安全输入或越界路径返回 null；目标不存在或不是文件返回 null。
 */
fun resolveExtensionFile(baseDir: File, folder: String, relativePath: String): File? {
    if (!isSafeExtensionSegment(folder)) return null
    val segs = relativePath.split('/').filter { it.isNotEmpty() }
    if (segs.isEmpty() || segs.any { !isSafeExtensionSegment(it) }) return null
    val relJoined = segs.joinToString("/")
    return try {
        val canonicalBase = baseDir.canonicalFile
        val directDir = File(baseDir, folder)
        val candidateDir = if (directDir.isDirectory) {
            directDir
        } else {
            val wantedAlias = normalizeExtensionFolderAlias(folder)
            baseDir.listFiles()
                ?.firstOrNull {
                    it.isDirectory &&
                        !it.name.startsWith(".") &&
                        isSafeExtensionSegment(it.name) &&
                        normalizeExtensionFolderAlias(it.name) == wantedAlias
                }
                ?: directDir
        }
        val target = File(candidateDir, relJoined).canonicalFile
        if (!target.path.startsWith(canonicalBase.path + File.separator)) return null
        target.takeIf { it.isFile }
    } catch (_: Exception) {
        null
    }
}

// ============================================================
// 运行时装载列表（runtime.js 消费）
// ============================================================

/**
 * 构建 runtime 注入用的扩展列表 JSON：
 * `[{folder, displayName, version, js, css}]`
 *
 * 过滤规则：无 manifest / 无 js / 被禁用 的扩展跳过；保持 [listExtensions] 的加载顺序。
 */
fun buildRuntimeExtensionsJson(
    extensions: List<TavernExtensionInfo>,
    disabled: Set<String>,
): String {
    val active = extensions.filter {
        it.hasManifest && it.js.isNotBlank() && it.folder !in disabled
    }
    return buildJsonArray {
        active.forEach { e ->
            add(
                buildJsonObject {
                    put("folder", e.folder)
                    put("displayName", e.displayName)
                    put("version", e.version)
                    put("author", e.author)
                    put("homePage", e.homePage)
                    put("js", e.js)
                    put("css", e.css)
                    put("loadingOrder", e.loadingOrder)
                    put("requires", buildJsonArray { e.requires.forEach { add(JsonPrimitive(it)) } })
                    put("optional", buildJsonArray { e.optional.forEach { add(JsonPrimitive(it)) } })
                    put("minimumClientVersion", e.minimumClientVersion)
                    put("generateInterceptor", e.generateInterceptor)
                    put("hooks", buildJsonObject { e.hooks.forEach { (k, v) -> put(k, v) } })
                }
            )
        }
    }.toString()
}

// ============================================================
// 安装（zip / URL）
// ============================================================

private fun openZip(file: File): ZipInputStream = ZipInputStream(file.inputStream().buffered())

/**
 * 从 zip 文件安装扩展（纯 JVM 可测：传入扩展根目录 [destBase]）。
 *
 * 规则：
 * - zip 中找"层级最浅的 manifest.json"，其所在目录视为扩展根（prefix）；
 * - 目录名优先级：sourceName > prefix 末段 > zip 文件名；
 * - 解压扩展根下所有文件；跳过 `.git` / `__MACOSX` / 含 `..` 的路径（zip-slip 防护）；
 * - 更新语义：同名目录先清空再写入。
 */
/**
 * 从 zip 文件安装一个或多个扩展（支持像 `all-needs.zip` 这样包含多个顶层扩展目录的合集压缩包，也兼容单扩展压缩包）。
 */
fun installAllFromZipFile(
    zipFile: File,
    destBase: File,
    sourceName: String? = null,
): Result<List<TavernExtensionInfo>> = runCatching {
    val entries = mutableListOf<String>()
    openZip(zipFile).use { zis ->
        while (true) {
            val e = zis.nextEntry ?: break
            entries += e.name
        }
    }
    val allManifests = entries
        .filter { (it == "manifest.json" || it.endsWith("/manifest.json")) && !it.startsWith("__MACOSX/") }
    require(allManifests.isNotEmpty()) { "安装包中未找到 manifest.json" }

    val minDepth = allManifests.minOf { it.count { c -> c == '/' } }
    val rootManifests = allManifests.filter { it.count { c -> c == '/' } == minDepth }
    val isMultiBundle = rootManifests.size > 1

    destBase.mkdirs()
    val installedList = mutableListOf<TavernExtensionInfo>()

    for (manifestPath in rootManifests) {
        val prefix = manifestPath.substringBeforeLast('/', "")
        val folder = sanitizeFolderName(
            if (isMultiBundle) {
                prefix.substringAfterLast('/').takeIf { it.isNotBlank() } ?: zipFile.nameWithoutExtension
            } else {
                sourceName?.takeIf { it.isNotBlank() }
                    ?: prefix.substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: zipFile.nameWithoutExtension
            }
        ) ?: error("无法推导扩展目录名")

        val destDir = File(destBase, folder)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        val destCanonical = destDir.canonicalFile
        var totalOut = 0L
        openZip(zipFile).use { zis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name.startsWith("__MACOSX/") || name.startsWith(".git/") || name.contains("/.git/")) continue
                val rel = when {
                    prefix.isEmpty() -> name
                    name.startsWith("$prefix/") -> name.removePrefix("$prefix/")
                    else -> continue
                }
                if (rel.isBlank() || rel.contains("..")) continue
                val outFile = File(destDir, rel).canonicalFile
                if (!outFile.path.startsWith(destCanonical.path + File.separator)) continue
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { out ->
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        totalOut += n
                        if (totalOut > MAX_EXTRACT_BYTES) error("解压内容超过上限")
                        out.write(buf, 0, n)
                    }
                }
            }
        }

        val manifestText = File(destDir, "manifest.json")
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrDefault("") }
            .orEmpty()
        val info = parseManifest(folder, manifestText) ?: error("manifest.json 解析失败 ($folder)")
        installedList += info
    }
    installedList
}

fun installFromZipFile(
    zipFile: File,
    destBase: File,
    sourceName: String? = null,
): Result<TavernExtensionInfo> =
    installAllFromZipFile(zipFile, destBase, sourceName).mapCatching { it.first() }

/** 从 SAF Uri 安装单个或多个扩展（应用内导入入口，支持合集包一次性安装全部扩展）。 */
suspend fun installAllFromZipUri(context: Context, uri: Uri): Result<List<TavernExtensionInfo>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val base = extensionsBaseDir(context).apply { mkdirs() }
            val tmp = File(context.cacheDir, "ext-install-${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> copyWithLimit(input, out, MAX_ARCHIVE_BYTES) }
                } ?: error("无法读取所选文件")
                val displayName = queryDisplayName(context, uri)?.removeSuffix(".zip")
                installAllFromZipFile(tmp, base, displayName).getOrThrow()
            } finally {
                tmp.delete()
            }
        }
    }

/** 从 SAF Uri 安装扩展（应用内导入入口）。 */
suspend fun installFromZipUri(context: Context, uri: Uri): Result<TavernExtensionInfo> =
    installAllFromZipUri(context, uri).mapCatching { it.first() }

/**
 * 从 http(s) URL 安装扩展（对齐 ST `installExtension(url, branch)` + TauriTavern git ref 解析语义）：
 * - 仓库主页（GitHub / GitLab / Codeberg 等）→ archive zip 候选逐个尝试，未指定分支时自动探测 main/master
 *   （真实案例：st-memory-enhancement 默认分支是 master，只试 main 必然翻车）；
 * - manifest.json 直链（含 raw.githubusercontent / gitlab /-/raw）→ 下载 manifest 及其资源树，
 *   并递归抓取 JS 相对导入（对 st-memory-enhancement 这类源码型扩展必需）；
 * - zip 直链 / archive 端点 / codeload → 直接下载解压（原流程）。
 */
suspend fun installFromUrl(context: Context, url: String, branch: String = ""): Result<TavernExtensionInfo> =
    withContext(Dispatchers.IO) {
        runCatching {
            val plan = planUrlInstall(url, branch)
                ?: error("无法识别的链接。支持：仓库地址（GitHub/GitLab/Codeberg 等）、manifest.json 直链、zip 直链")
            val base = extensionsBaseDir(context).apply { mkdirs() }
            when (plan.kind) {
                StUrlKind.MANIFEST -> {
                    val manifestUrl = plan.manifestUrl!!
                    val manifestText = httpGetText(manifestUrl)
                    val folder = sanitizeFolderName(
                        plan.folderName?.takeIf { it.isNotBlank() }
                            ?: "st-extension"
                    ) ?: error("无法推导扩展目录名")
                    val destDir = File(base, folder)
                    if (destDir.exists()) destDir.deleteRecursively()
                    destDir.mkdirs()
                    installFromManifestTree(manifestUrl, manifestText, destDir)
                    parseManifest(folder, manifestText) ?: error("manifest.json 解析失败")
                }

                else -> {
                    val errors = mutableListOf<String>()
                    for (zipUrl in plan.zipUrls) {
                        val tmp = File(
                            context.cacheDir,
                            "ext-install-url-${System.currentTimeMillis()}-${errors.size}.zip"
                        )
                        try {
                            downloadToFile(zipUrl, tmp, zipCheck = true)
                            return@runCatching installFromZipFile(tmp, base, plan.folderName).getOrThrow()
                        } catch (e: Exception) {
                            errors += "$zipUrl → ${e.message ?: "未知错误"}"
                        } finally {
                            tmp.delete()
                        }
                    }
                    error(
                        "安装失败（已尝试 ${plan.zipUrls.size} 个下载地址）：\n" +
                            errors.take(5).joinToString("\n")
                    )
                }
            }
        }
    }

/** 删除扩展目录。 */
suspend fun deleteExtension(context: Context, folder: String): Boolean =
    withContext(Dispatchers.IO) {
        if (!isSafeExtensionSegment(folder)) return@withContext false
        val dir = File(extensionsBaseDir(context), folder)
        !dir.exists() || dir.deleteRecursively()
    }

// ============================================================
// 内部工具
// ============================================================

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

private fun copyWithLimit(input: InputStream, output: OutputStream, limit: Long) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) error("文件超过 ${limit / 1024 / 1024}MB 上限")
        output.write(buf, 0, n)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
} catch (_: Exception) {
    null
}

/** 从 URL 推导扩展目录名：GitHub 仓库名优先，否则取末段（去 .zip/.git）。 */
fun deriveNameFromUrl(url: String): String? {
    val noQuery = url.substringBefore('#').substringBefore('?').trimEnd('/')
    Regex("""https?://github\.com/[^/]+/([^/]+?)(?:\.git)?(?:/.*)?$""").find(noQuery)?.let { m ->
        val name = m.groupValues[1].removeSuffix(".git")
        return name.takeIf { it.isNotBlank() && it != "archive" }
    }
    val seg = noQuery.substringAfterLast('/')
    return seg.removeSuffix(".zip").removeSuffix(".git").takeIf { it.isNotBlank() }
}

// ============================================================
// URL 归一化（对齐 ST installExtension(url, branch) + TauriTavern ref 解析语义）
// ============================================================

/** URL 安装计划类型：MANIFEST=manifest.json 直链；REPO=仓库主页；ZIP=zip 直链。 */
enum class StUrlKind { MANIFEST, REPO, ZIP }

/** URL 安装计划（纯函数产物，便于单测）。 */
data class StUrlPlan(
    val kind: StUrlKind,
    /** 建议的扩展目录名（仓库名）。 */
    val folderName: String?,
    /** MANIFEST 模式：manifest.json 绝对 URL。 */
    val manifestUrl: String?,
    /** REPO/ZIP 模式：候选下载地址（按优先级排列）。 */
    val zipUrls: List<String>,
)

/** 仓库三元组（host + owner 路径 + 仓库名）。 */
internal data class RepoRef(val host: String, val ownerPath: String, val repo: String)

/** 解析仓库主页 URL（自动截断 tree/blob/raw 等子路径；gitlab 支持多级 group）。 */
internal fun parseRepoUrl(clean: String): RepoRef? {
    val host = clean.substringAfter("://").substringBefore('/')
    val segs = clean.substringAfter("://").substringAfter('/')
        .split('/').filter { it.isNotBlank() }
    if (segs.size < 2) return null
    val stopWords = setOf("-", "tree", "blob", "src", "raw", "archive", "releases", "issues", "pull", "tags")
    val cut = segs.indexOfFirst { it.lowercase() in stopWords }
    val core = (if (cut >= 0) segs.subList(0, cut) else segs).map { it.removeSuffix(".git") }
    if (core.size < 2) return null
    return if (host.contains("gitlab", ignoreCase = true) && core.size >= 3) {
        RepoRef(host, core.dropLast(1).joinToString("/"), core.last())
    } else {
        RepoRef(host, core[0], core[1])
    }
}

/** 生成仓库 archive zip 候选（TauriTavern 用 git smart HTTP，此处用等效的 archive 端点）。 */
internal fun repoZipCandidates(ref: RepoRef, branch: String, allowTag: Boolean): List<String> {
    val owner = ref.ownerPath
    val repo = ref.repo
    return buildList {
        when {
            ref.host.equals("github.com", ignoreCase = true) -> {
                add("https://github.com/$owner/$repo/archive/refs/heads/$branch.zip")
                add("https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch")
                if (allowTag) add("https://github.com/$owner/$repo/archive/refs/tags/$branch.zip")
            }

            ref.host.contains("gitlab", ignoreCase = true) -> {
                add("https://${ref.host}/$owner/$repo/-/archive/$branch/$repo-$branch.zip")
            }

            else -> {
                // codeberg / gitea / forgejo 系
                add("https://${ref.host}/$owner/$repo/archive/$branch.zip")
                add("https://${ref.host}/$owner/$repo/archive/refs/heads/$branch.zip")
                if (allowTag) add("https://${ref.host}/$owner/$repo/archive/refs/tags/$branch.zip")
            }
        }
    }
}

/**
 * 把用户输入归一化为安装计划：
 * 1. manifest.json 结尾 → MANIFEST（raw.githubusercontent / gitlab raw 等直链）；
 * 2. .zip 结尾或 archive/codeload 端点 → ZIP；
 * 3. 其余按仓库主页解析 → REPO（分支候选：显式 branch，否则 main 到 master 自动探测）。
 */
fun planUrlInstall(rawUrl: String, branch: String = ""): StUrlPlan? {
    val clean = rawUrl.trim().substringBefore('#').substringBefore('?').trimEnd('/')
    if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
    if (!clean.contains("://")) return null
    val host = clean.substringAfter("://").substringBefore('/')
    val segs = clean.substringAfter("://").substringAfter('/')
        .split('/').filter { it.isNotBlank() }
    if (segs.isEmpty()) return null
    // 1) manifest.json 直链
    if (segs.last().equals("manifest.json", ignoreCase = true)) {
        return StUrlPlan(StUrlKind.MANIFEST, deriveRepoNameFromManifestUrl(clean), clean, emptyList())
    }
    // 2) zip 直链 / archive 端点
    if (clean.endsWith(".zip", ignoreCase = true) ||
        host.startsWith("codeload.") ||
        "/archive/" in clean || "/-/archive/" in clean
    ) {
        return StUrlPlan(StUrlKind.ZIP, deriveNameFromUrl(clean), null, listOf(clean))
    }
    // 3) 仓库主页
    val repo = parseRepoUrl(clean) ?: return null
    val explicit = branch.trim().takeIf { it.isNotBlank() }
    val refs = explicit?.let { listOf(it) } ?: listOf("main", "master")
    val zips = refs.flatMap { repoZipCandidates(repo, it, allowTag = explicit != null) }
    return StUrlPlan(StUrlKind.REPO, repo.repo, null, zips)
}

/** 从 manifest.json 直链推导仓库名（目录名优先）。 */
internal fun deriveRepoNameFromManifestUrl(url: String): String? {
    val host = url.substringAfter("://").substringBefore('/')
    val segs = url.substringAfter("://").substringAfter('/')
        .split('/').filter { it.isNotBlank() }
    return when {
        host.contains("raw.githubusercontent", ignoreCase = true) -> segs.getOrNull(1)
        "-" in segs -> segs.getOrNull(segs.indexOf("-") - 1)
        "raw" in segs -> segs.getOrNull(segs.indexOf("raw") - 1)
        segs.size >= 2 -> segs[segs.size - 2].takeIf { it.lowercase() !in setOf("main", "master", "dev") }
            ?: segs.getOrNull(segs.size - 3)
        else -> segs.getOrNull(0)
    }?.removeSuffix(".git")?.takeIf { it.isNotBlank() }
}

/** manifest 中需要下载的相对资源（js / css / i18n 值）；绝对 URL 跳过。 */
internal fun collectManifestResources(manifestText: String): List<String> {
    val root = Json.parseToJsonElement(manifestText) as? JsonObject ?: return emptyList()
    val out = mutableListOf<String>()
    fun add(p: String?) {
        val s = p?.trim()?.trimStart('/') ?: return
        if (s.isBlank() || "://" in s) return
        out += s
    }
    add((root["js"] as? JsonPrimitive)?.contentOrNull)
    add((root["css"] as? JsonPrimitive)?.contentOrNull)
    (root["i18n"] as? JsonObject)?.values?.forEach { add((it as? JsonPrimitive)?.contentOrNull) }
    return out.distinct()
}

/**
 * 提取 JS 源码中的相对导入（静态 import/export from 与动态 import）。
 * 仅抓以 ./ 或 ../ 开头且带常见后缀的字符串，宽松匹配（多下载无害，有数量与总量上限）。
 */
internal fun extractRelativeImports(js: String): List<String> {
    val regex = Regex("""["'](\.{1,2}/[^"'\\:]+?\.(?:js|mjs|css|json|html))["']""")
    return regex.findAll(js).map { it.groupValues[1] }.distinct().toList()
}

/** 归一化 (基目录, 相对路径) → 扁平相对路径；越级跳出根返回 null。 */
internal fun normalizeRelPath(baseDir: String, rel: String): String? {
    val segs = buildList {
        addAll(baseDir.split('/').filter { it.isNotBlank() && it != "." })
        addAll(rel.split('/').filter { it.isNotBlank() && it != "." })
    }
    val out = mutableListOf<String>()
    for (s in segs) {
        if (s == "..") {
            if (out.isEmpty()) return null
            out.removeAt(out.size - 1)
        } else {
            out += s
        }
    }
    return out.joinToString("/")
}

/** manifest 资源树下载的文件数上限。 */
private const val MAX_MANIFEST_FILES = 300

/**
 * 下载 manifest 及其资源树（BFS）：
 * - 首层来自 manifest 的 js / css / i18n 声明；
 * - 每个 .js / .mjs 下载后抓取相对导入并递归（对 st-memory-enhancement 这类源码型扩展必需）；
 * - 逐文件写入 destDir，总量与文件数受限。
 */
internal fun installFromManifestTree(baseUrl: String, manifestText: String, destDir: File) {
    File(destDir, "manifest.json").writeText(manifestText)
    val visited = mutableSetOf<String>()

    data class Item(val rel: String, val abs: String)

    val queue = ArrayDeque<Item>()
    fun enqueue(relRaw: String, fromRel: String) {
        val baseDir = fromRel.substringBeforeLast('/', "")
        val rel = normalizeRelPath(baseDir, relRaw) ?: return
        if (rel in visited) return
        val absBase = baseUrl.substringBeforeLast('/') + "/" + if (baseDir.isEmpty()) "" else "$baseDir/"
        val abs = java.net.URI(absBase).resolve(relRaw).toString()
        queue.addLast(Item(rel, abs))
    }
    collectManifestResources(manifestText).forEach { enqueue(it, "") }
    var count = 0
    var total = 0L
    while (queue.isNotEmpty() && count < MAX_MANIFEST_FILES) {
        val item = queue.removeFirst()
        if (!visited.add(item.rel)) continue
        val outFile = File(destDir, item.rel).canonicalFile
        if (!outFile.path.startsWith(destDir.canonicalFile.path + File.separator)) continue
        outFile.parentFile?.mkdirs()
        downloadToFile(item.abs, outFile, zipCheck = false)
        total += outFile.length()
        if (total > MAX_EXTRACT_BYTES) error("解压内容超过上限")
        count++
        if (item.rel.endsWith(".js") || item.rel.endsWith(".mjs")) {
            val js = runCatching { outFile.readText() }.getOrDefault("")
            extractRelativeImports(js).forEach { enqueue(it, item.rel) }
        }
    }
}

private fun httpGetText(url: String): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "rikkaST-tavern-ext")
        .build()
    httpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
        return resp.body.string()
    }
}

private fun downloadToFile(url: String, target: File, zipCheck: Boolean) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "rikkaST-tavern-ext")
        .build()
    httpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body.byteStream().use { input ->
            target.outputStream().use { out -> copyWithLimit(input, out, MAX_ARCHIVE_BYTES) }
        }
    }
    if (zipCheck) {
        val head = target.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
        if (head.size < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            error("非 zip 内容（该地址可能不是仓库/zip 直链）")
        }
    }
}
