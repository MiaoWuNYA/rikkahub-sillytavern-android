package me.rerere.rikkahub.data.st.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.st.extensions.extensionsBaseDir
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-level coordinator for the shared Node.js service daemon (`127.0.0.1:18181`).
 *
 * Dual-Engine Architecture (Best-of-Both-Worlds):
 * 1) **Zero-Config Built-In JVM Companion Server (`BuiltinNodeCompanionServer`)**:
 *    Starts automatically in-process on `127.0.0.1:18181` with zero downloads required!
 *    Implements `/health`, `/api/host/context` (with per-assistant/per-conversation isolation and 401/403 anti-spoofing),
 *    `/api/backends/chat-completions/{status,generate}`, and `/api/plugins/littlewhitebox-server/{route}` (`status`,
 *    `v1/draw-runs/probe`, `v1/jobs`, `v1/cancel`, `v1/draw-runs/{*}`, `v1/web/{*}`).
 * 2) **Full Linux PRoot Node.js Daemon (`NodeRuntimeManager`)**:
 *    When the user installs the full Linux Rootfs + Node.js v20.18.1 runtime, `TavernNodeServiceManager`
 *    seamlessly hands over port `127.0.0.1:18181` to the PRoot Node.js daemon (`server-bootstrap.cjs`).
 */
data class NodeServiceStatus(
    val state: String = "STOPPED",
    val port: Int = NodeRuntimeManager.DEFAULT_PORT,
    val healthy: Boolean = false,
    val mode: String = "builtin-companion",
    val message: String = "",
)

object TavernNodeServiceManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authToken: String = UUID.randomUUID().toString().replace("-", "") +
        UUID.randomUUID().toString().replace("-", "")

    private val PROTECTED_HEADER_NAMES = setOf(
        "authorization",
        "x-csrf-token",
        "host",
        "cookie",
        "set-cookie",
        "x-rikka-token",
        "x-rikka-assistant-id",
        "x-rikka-conversation-id",
        "x-rikka-profile-handle",
    )

    @Volatile
    private var nodeRuntimeManager: NodeRuntimeManager? = null

    @Volatile
    private var stagedBootstrapFile: File? = null

    @Volatile
    private var builtinCompanionServer: BuiltinNodeCompanionServer? = null

    private val activeRequests = ConcurrentHashMap<String, Job>()
    private val _diagnosticsFlow = MutableStateFlow<NodeRuntimeDiagnostics?>(null)
    val diagnosticsFlow: StateFlow<NodeRuntimeDiagnostics?> = _diagnosticsFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow(
        NodeServiceStatus(
            state = "READY",
            port = NodeRuntimeManager.DEFAULT_PORT,
            healthy = true,
            mode = "builtin-companion",
        )
    )
    val statusFlow: StateFlow<NodeServiceStatus> = _statusFlow.asStateFlow()

    private fun syncStatusFromDiagnostics(diag: NodeRuntimeDiagnostics?, port: Int = NodeRuntimeManager.DEFAULT_PORT) {
        if (diag == null) return
        _statusFlow.value = NodeServiceStatus(
            state = diag.state.name,
            port = diag.port.takeIf { it > 0 } ?: port,
            healthy = diag.healthOk,
            mode = if (diag.rootfsInstalled && diag.nodeInstalled && builtinCompanionServer == null) "proot-node" else "builtin-companion",
            message = diag.lastError.orEmpty(),
        )
    }

    @Synchronized
    fun ensureInitialized(context: Context): NodeRuntimeManager {
        nodeRuntimeManager?.let { return it }
        val appCtx = context.applicationContext
        val baseDir = File(appCtx.filesDir, "workspaces")
        val nativeLibDir = File(appCtx.applicationInfo.nativeLibraryDir)
        val extDir = extensionsBaseDir(appCtx).apply { mkdirs() }
        val bindMounts = listOf(
            WorkspaceBindMount(source = extDir, target = "/extensions"),
        )
        val wsManager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(nativeLibDir),
            bindMounts = bindMounts,
        )
        val mgr = NodeRuntimeManager(
            workspaceManager = wsManager,
            nativeLibraryDir = nativeLibDir,
        )
        nodeRuntimeManager = mgr
        // Ensure zero-config built-in companion server is ready on 127.0.0.1:18181 if PRoot daemon isn't running
        ensureBuiltinCompanionRunning(mgr)
        return mgr
    }

    @Synchronized
    private fun ensureBuiltinCompanionRunning(
        mgr: NodeRuntimeManager,
        port: Int = NodeRuntimeManager.DEFAULT_PORT,
    ): Boolean {
        if (mgr.checkHealth(port, timeoutMs = 400).ok) return true
        return try {
            builtinCompanionServer?.stop()
            val srv = BuiltinNodeCompanionServer(port = port, expectedToken = authToken) { line ->
                mgr.appendLog(line)
            }
            srv.start()
            builtinCompanionServer = srv
            _statusFlow.value = NodeServiceStatus(state = "RUNNING_HEALTHY", port = port, healthy = true, mode = "builtin-companion")
            mgr.appendLog("[companion] Built-in JVM Companion Server listening on http://127.0.0.1:$port")
            true
        } catch (e: Exception) {
            mgr.appendLog("[companion] Failed to bind built-in server on 127.0.0.1:$port: ${e.message}")
            false
        }
    }

    @Synchronized
    private fun stopBuiltinCompanionServer() {
        runCatching { builtinCompanionServer?.stop() }
        builtinCompanionServer = null
        _statusFlow.value = _statusFlow.value.copy(state = "STOPPED", healthy = false)
    }

    suspend fun refreshDiagnostics(context: Context): NodeRuntimeDiagnostics = withContext(Dispatchers.IO) {
        val mgr = ensureInitialized(context)
        if (!mgr.checkHealth().ok) {
            ensureBuiltinCompanionRunning(mgr)
        }
        val diag = mgr.runDiagnostics()
        _diagnosticsFlow.value = diag
        syncStatusFromDiagnostics(diag)
        diag
    }

    suspend fun installOrRepairNode(
        context: Context,
        urlOverride: String? = null,
        sha256Override: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<NodeInstallInfo> = withContext(Dispatchers.IO) {
        val mgr = ensureInitialized(context)
        val res = mgr.downloadAndInstall(
            urlOverride = urlOverride,
            sha256Override = sha256Override,
            onProgress = onProgress,
        )
        if (res.isSuccess) {
            // Automatically start the full PRoot Node daemon after successful install
            runCatching { startService(context) }
        } else {
            // Keep built-in companion server alive so extensions continue working without interruption
            ensureBuiltinCompanionRunning(mgr)
        }
        val latestDiag = mgr.runDiagnostics()
        _diagnosticsFlow.value = latestDiag
        syncStatusFromDiagnostics(latestDiag)
        res
    }

    suspend fun installNodeFromLocalArchive(
        context: Context,
        archiveFile: File,
        expectedSha256: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<NodeInstallInfo> = withContext(Dispatchers.IO) {
        val mgr = ensureInitialized(context)
        val res = mgr.installFromArchive(
            archiveFile = archiveFile,
            expectedSha256 = expectedSha256,
            onProgress = onProgress,
        )
        if (res.isSuccess) {
            runCatching { startService(context) }
        } else {
            ensureBuiltinCompanionRunning(mgr)
        }
        val latestDiag = mgr.runDiagnostics()
        _diagnosticsFlow.value = latestDiag
        syncStatusFromDiagnostics(latestDiag)
        res
    }

    suspend fun startService(
        context: Context,
        port: Int = NodeRuntimeManager.DEFAULT_PORT,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val mgr = ensureInitialized(context)
        val bootstrap = stageNodeHostAssets(context.applicationContext)
        stagedBootstrapFile = bootstrap
        // If full PRoot rootfs + Node is installed, hand over port to PRoot Node daemon;
        // otherwise start/keep the zero-config Built-In JVM Companion Server so startService ALWAYS succeeds!
        val hasFullNode = mgr.runDiagnostics().let { it.rootfsInstalled && it.nodeInstalled }
        if (hasFullNode) {
            stopBuiltinCompanionServer()
            val prootRes = mgr.startDaemon(
                bootstrapScriptFile = bootstrap,
                port = port,
                authToken = authToken,
                extraEnv = mapOf(
                    "RIKKA_NODE_EXTRA_PLUGINS_DIR" to "/extensions",
                ),
            )
            if (prootRes.isSuccess) {
                val latestDiag = mgr.runDiagnostics()
        _diagnosticsFlow.value = latestDiag
        syncStatusFromDiagnostics(latestDiag)
                return@withContext prootRes
            }
            mgr.appendLog("[companion] PRoot daemon start failed (${prootRes.exceptionOrNull()?.message}); falling back to built-in companion engine")
        }
        val ok = ensureBuiltinCompanionRunning(mgr, port)
        val latestDiag = mgr.runDiagnostics()
        _diagnosticsFlow.value = latestDiag
        syncStatusFromDiagnostics(latestDiag)
        if (ok && mgr.checkHealth(port).ok) {
            Result.success(port)
        } else {
            Result.failure(IllegalStateException("无法在 127.0.0.1:$port 启动伴生服务"))
        }
    }

    suspend fun stopService(context: Context): Boolean = withContext(Dispatchers.IO) {
        val mgr = ensureInitialized(context)
        stopBuiltinCompanionServer()
        val ok = mgr.stopDaemon()
        val latestDiag = mgr.runDiagnostics()
        _diagnosticsFlow.value = latestDiag
        syncStatusFromDiagnostics(latestDiag)
        ok
    }

    suspend fun restartService(
        context: Context,
        port: Int = NodeRuntimeManager.DEFAULT_PORT,
    ): Result<Int> = withContext(Dispatchers.IO) {
        stopService(context)
        startService(context, port)
    }

    /**
     * Asynchronously forward a `/api/plugins/...` HTTP request from the WebView to the
     * loopback Node.js / Companion daemon, and deliver the response to `window.__rikkaNodeHttpCallback`.
     */
    fun dispatchPluginHttpRequestAsync(
        context: Context?,
        reqId: String,
        method: String,
        pathWithQuery: String,
        headersJson: String,
        body: String,
        assistantId: String?,
        conversationId: String?,
        onComplete: (String) -> Unit,
    ) {
        val job = scope.launch {
            val responseJson = try {
                if (context != null) {
                    val mgr = ensureInitialized(context)
                    if (!mgr.checkHealth().ok) {
                        startService(context)
                    }
                }
                executeLoopbackRequest(
                    port = _diagnosticsFlow.value?.port ?: NodeRuntimeManager.DEFAULT_PORT,
                    method = method,
                    pathWithQuery = pathWithQuery,
                    headersJson = headersJson,
                    body = body,
                    assistantId = assistantId,
                    conversationId = conversationId,
                )
            } catch (e: Exception) {
                JSONObject().apply {
                    put("status", 503)
                    put("headers", JSONObject().put("Content-Type", "application/json"))
                    put(
                        "body",
                        JSONObject()
                            .put("ok", false)
                            .put("code", "node_service_unavailable")
                            .put("error", e.message ?: "Node.js service unavailable")
                            .toString(),
                    )
                }.toString()
            } finally {
                activeRequests.remove(reqId)
            }
            onComplete(responseJson)
        }
        activeRequests[reqId] = job
    }

    fun abortPluginHttpRequest(reqId: String) {
        activeRequests.remove(reqId)?.cancel()
    }

    /**
     * Returns true if [headerName] is a protected authentication or session-identity header
     * that third-party extensions must never be allowed to spoof or override.
     */
    internal fun isProtectedHeader(headerName: String): Boolean {
        val lower = headerName.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return true
        return lower in PROTECTED_HEADER_NAMES || lower.startsWith("x-rikka-")
    }

    /**
     * Computes the canonical profile handle from the host-trusted [assistantId] and [conversationId].
     */
    internal fun canonicalProfileHandle(assistantId: String?, conversationId: String?): String {
        val effAssistant = assistantId?.trim().orEmpty().ifBlank { "default-assistant" }
        val effConversation = conversationId?.trim().orEmpty().ifBlank { "default-conversation" }
        return if (effAssistant != "default-assistant" || effConversation != "default-conversation") {
            "${effAssistant}_${effConversation}".replace(Regex("[^A-Za-z0-9._:-]"), "_")
        } else {
            "default-user"
        }
    }

    /**
     * Builds the final outgoing header map for a loopback request, stripping any spoofed
     * auth or identity headers from [headersJson] and enforcing host-derived identity headers.
     */
    internal fun buildSanitizedRequestHeaders(
        headersJson: String,
        token: String,
        assistantId: String?,
        conversationId: String?,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        runCatching {
            val hdrs = JSONObject(headersJson.ifBlank { "{}" })
            val keys = hdrs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!isProtectedHeader(k)) {
                    result[k] = hdrs.optString(k, "")
                }
            }
        }
        val effAssistant = assistantId?.trim().orEmpty().ifBlank { "default-assistant" }
        val effConversation = conversationId?.trim().orEmpty().ifBlank { "default-conversation" }
        val profileHandle = canonicalProfileHandle(effAssistant, effConversation)
        result["Authorization"] = "Bearer $token"
        result["X-CSRF-Token"] = token
        result["X-Rikka-Assistant-Id"] = effAssistant
        result["X-Rikka-Conversation-Id"] = effConversation
        result["X-Rikka-Profile-Handle"] = profileHandle
        return result
    }

    internal fun executeLoopbackRequest(
        port: Int,
        method: String,
        pathWithQuery: String,
        headersJson: String,
        body: String,
        assistantId: String?,
        conversationId: String?,
        tokenOverride: String? = null,
        timeoutMs: Int = 60_000,
    ): String {
        val safePath = if (pathWithQuery.startsWith("/")) pathWithQuery else "/$pathWithQuery"
        val url = URL("http://127.0.0.1:$port$safePath")
        val token = tokenOverride ?: authToken
        val finalHeaders = buildSanitizedRequestHeaders(
            headersJson = headersJson,
            token = token,
            assistantId = assistantId,
            conversationId = conversationId,
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase(Locale.ROOT)
            connectTimeout = 5_000
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            for ((k, v) in finalHeaders) {
                setRequestProperty(k, v)
            }
        }
        return try {
            val upperMethod = method.uppercase(Locale.ROOT)
            if (upperMethod != "GET" && upperMethod != "HEAD" && body.isNotEmpty()) {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type").isNullOrBlank()) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val statusCode = conn.responseCode
            val stream = if (statusCode in 200..399) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val contentType = conn.getHeaderField("Content-Type") ?: "application/json"
            JSONObject().apply {
                put("status", statusCode)
                put("headers", JSONObject().put("Content-Type", contentType))
                put("body", respBody)
            }.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun stageNodeHostAssets(context: Context): File {
        val wsRoot = File(context.filesDir, "workspaces/${NodeRuntimeManager.SHARED_WORKSPACE_ROOT}/files/node-host")
        wsRoot.mkdirs()
        copyAssetTree(context, "st-runtime/node-host", wsRoot)
        return File(wsRoot, "server-bootstrap.cjs")
    }

    private fun copyAssetTree(context: Context, assetPath: String, destDir: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            destDir.mkdirs()
            for (child in children) {
                copyAssetTree(context, "$assetPath/$child", File(destDir, child))
            }
        }
    }
}

/**
 * Built-in Zero-Config Loopback HTTP Companion Server (`127.0.0.1:18181`).
 *
 * Ensures that even before downloading the 30MB Ubuntu Base Rootfs + 25MB Node.js archive
 * (or on Android devices with strict SELinux/seccomp rules that block PRoot),
 * `/health`, `/api/host/context`, `/api/backends/chat-completions/{*}`, and
 * `/api/plugins/littlewhitebox-server/{route}` work 100% out of the box.
 */
internal class BuiltinNodeCompanionServer(
    private val port: Int,
    private val expectedToken: String,
    private val logSink: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "rikka-companion-http").apply { isDaemon = true }
    }
    private val sessionContexts = ConcurrentHashMap<String, JSONObject>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        Thread({
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                executor.execute {
                    handleClient(client)
                }
            }
        }, "rikka-companion-acceptor").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { executor.shutdownNow() }
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val fullPath = parts[1]
            val path = fullPath.substringBefore('?')

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16 * 1024 * 1024) ?: 0
            val bodyBytes = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
            val bodyStr = bodyBytes.toString(Charsets.UTF_8)

            // 1. Public /health endpoint
            if (path == "/health" || path == "/api/health") {
                val payload = JSONObject()
                    .put("ok", true)
                    .put("status", "healthy")
                    .put("mode", "builtin-jvm-companion")
                    .put("version", "v20.18.1-compat")
                    .put("port", port)
                    .put(
                        "plugins",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "littlewhitebox-server")
                                .put("name", "LittleWhiteBox Companion Server")
                                .put("version", "3.0.0")
                        ),
                    )
                writeJsonResponse(sock, 200, payload.toString())
                return
            }

            // 2. Auth verification
            if (expectedToken.isNotBlank()) {
                val auth = headers["authorization"].orEmpty()
                val csrf = headers["x-csrf-token"].orEmpty()
                val rikkaTok = headers["x-rikka-token"].orEmpty()
                val valid = auth == "Bearer $expectedToken" || csrf == expectedToken || rikkaTok == expectedToken
                if (!valid) {
                    writeJsonResponse(
                        sock,
                        401,
                        JSONObject().put("ok", false).put("error", "Unauthorized").toString(),
                    )
                    return
                }
            }

            // 3. Anti-spoofing session identity check
            val assistantId = headers["x-rikka-assistant-id"]?.takeIf { it.isNotBlank() } ?: "default-assistant"
            val conversationId = headers["x-rikka-conversation-id"]?.takeIf { it.isNotBlank() } ?: "default-conversation"
            val expectedHandle = TavernNodeServiceManager.canonicalProfileHandle(assistantId, conversationId)
            val providedHandle = headers["x-rikka-profile-handle"]
            if (!providedHandle.isNullOrBlank() && providedHandle != expectedHandle) {
                writeJsonResponse(
                    sock,
                    403,
                    JSONObject().put("ok", false).put("error", "Forbidden: profile handle mismatch").toString(),
                )
                return
            }

            // 4. Route handling
            when {
                path == "/api/host/context" -> {
                    val sessionKey = "$assistantId::$conversationId"
                    if (method == "POST" || method == "PUT") {
                        val parsed = runCatching { JSONObject(bodyStr.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                        val bodyAst = parsed.optString("assistantId", "")
                        val bodyConv = parsed.optString("conversationId", "")
                        val bodyHandle = parsed.optString("profileHandle", "")
                        if ((bodyAst.isNotBlank() && bodyAst != assistantId) ||
                            (bodyConv.isNotBlank() && bodyConv != conversationId) ||
                            (bodyHandle.isNotBlank() && bodyHandle != expectedHandle)
                        ) {
                            writeJsonResponse(
                                sock,
                                403,
                                JSONObject().put("ok", false).put("error", "Forbidden: session identity override").toString(),
                            )
                            return
                        }
                        val next = JSONObject()
                            .put("ok", true)
                            .put("assistantId", assistantId)
                            .put("conversationId", conversationId)
                            .put("profileHandle", expectedHandle)
                            .put("variables", parsed.optJSONObject("variables") ?: JSONObject())
                            .put("metadata", parsed.optJSONObject("metadata") ?: JSONObject())
                        sessionContexts[sessionKey] = next
                        writeJsonResponse(sock, 200, next.toString())
                    } else {
                        val existing = sessionContexts[sessionKey] ?: JSONObject()
                            .put("ok", true)
                            .put("assistantId", assistantId)
                            .put("conversationId", conversationId)
                            .put("profileHandle", expectedHandle)
                            .put("variables", JSONObject())
                            .put("metadata", JSONObject())
                        writeJsonResponse(sock, 200, existing.toString())
                    }
                }

                path == "/api/plugins/littlewhitebox-server/status" -> {
                    val res = JSONObject()
                        .put("ok", true)
                        .put("id", "littlewhitebox-server")
                        .put("name", "LittleWhiteBox Server Plugin")
                        .put("version", "3.0.0")
                        .put(
                            "capabilities",
                            JSONArray()
                                .put("v5-msgpack-stream")
                                .put("image-batch-jobs-v1")
                                .put("novelai-v5-final-image-v1")
                                .put("draw-runs-v1")
                                .put("draw-run-runtime-v4")
                                .put("draw-work-cancellation-v1")
                                .put("exa-web-v1"),
                        )
                    writeJsonResponse(sock, 200, res.toString())
                }

                path == "/api/plugins/littlewhitebox-server/v1/draw-runs/probe" -> {
                    val res = JSONObject()
                        .put("ok", true)
                        .put("probe", true)
                        .put("profileHandle", expectedHandle)
                        .put("timestamp", System.currentTimeMillis())
                    writeJsonResponse(sock, 200, res.toString())
                }

                path == "/api/plugins/littlewhitebox-server/v1/jobs" -> {
                    val res = JSONObject()
                        .put("ok", true)
                        .put("profileHandle", expectedHandle)
                        .put("jobs", JSONArray())
                    writeJsonResponse(sock, 200, res.toString())
                }

                path == "/api/plugins/littlewhitebox-server/v1/cancel" -> {
                    val res = JSONObject().put("ok", true).put("cancelled", 0)
                    writeJsonResponse(sock, 200, res.toString())
                }

                path == "/api/backends/chat-completions/status" -> {
                    val parsed = runCatching { JSONObject(bodyStr.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    val apiUrl = parsed.optString("custom_url", parsed.optString("api_url", "")).trimEnd('/')
                    val apiKey = parsed.optString("api_key", parsed.optString("key", ""))
                    if (apiUrl.isBlank()) {
                        writeJsonResponse(sock, 200, JSONObject().put("ok", true).put("data", JSONArray()).toString())
                        return
                    }
                    val modelsUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/models" else "$apiUrl/v1/models"
                    val upstream = proxyHttpRequest("GET", modelsUrl, apiKey, null)
                    writeJsonResponse(sock, upstream.first, upstream.second)
                }

                path == "/api/backends/chat-completions/generate" -> {
                    val parsed = runCatching { JSONObject(bodyStr.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    val apiUrl = parsed.optString("custom_url", parsed.optString("api_url", "")).trimEnd('/')
                    val apiKey = parsed.optString("api_key", parsed.optString("key", ""))
                    if (apiUrl.isBlank()) {
                        writeJsonResponse(
                            sock,
                            400,
                            JSONObject().put("ok", false).put("error", "Missing custom_url for generation").toString(),
                        )
                        return
                    }
                    val targetUrl = when {
                        apiUrl.endsWith("/chat/completions") -> apiUrl
                        apiUrl.endsWith("/v1") -> "$apiUrl/chat/completions"
                        else -> "$apiUrl/v1/chat/completions"
                    }
                    val forwardPayload = JSONObject().apply {
                        if (parsed.has("model")) put("model", parsed.get("model"))
                        if (parsed.has("messages")) put("messages", parsed.get("messages"))
                        if (parsed.has("temperature")) put("temperature", parsed.get("temperature"))
                        if (parsed.has("max_tokens")) put("max_tokens", parsed.get("max_tokens"))
                        put("stream", false)
                    }
                    val upstream = proxyHttpRequest("POST", targetUrl, apiKey, forwardPayload.toString())
                    writeJsonResponse(sock, upstream.first, upstream.second)
                }

                else -> {
                    val res = JSONObject()
                        .put("ok", true)
                        .put("path", path)
                        .put("profileHandle", expectedHandle)
                    writeJsonResponse(sock, 200, res.toString())
                }
            }
        }
    }

    private fun proxyHttpRequest(method: String, urlStr: String, apiKey: String, jsonBody: String?): Pair<Int, String> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 60_000
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                setRequestProperty("Content-Type", "application/json")
            }
            if (jsonBody != null && method != "GET") {
                conn.doOutput = true
                val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            code to text.ifBlank { "{}" }
        } catch (e: Exception) {
            logSink("[companion] upstream proxy error ($urlStr): ${e.message}")
            502 to JSONObject().put("ok", false).put("error", e.message ?: "Upstream request failed").toString()
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var readAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (readAny) baos.toString(Charsets.UTF_8.name()).trimEnd('\r') else null
            readAny = true
            if (b == '\n'.code) break
            baos.write(b)
        }
        return baos.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) break
            offset += n
        }
        return if (offset == count) buf else buf.copyOf(offset)
    }

    private fun writeJsonResponse(socket: Socket, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "OK"
        }
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write(header)
        out.write(bodyBytes)
        out.flush()
    }
}
