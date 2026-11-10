package me.rerere.rikkahub.data.st.runtime
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceManager


import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class NodeRuntimeState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED_STOPPED,
    STARTING,
    RUNNING_HEALTHY,
    CRASHED,
    ERROR,
}

data class NodeInstallInfo(
    val nodeVersion: String,
    val npmVersion: String,
    val arch: String,
    val platform: String,
    val binaryRootfsPath: String,
)

data class NodeHealthStatus(
    val ok: Boolean,
    val statusCode: Int,
    val body: String,
    val error: String? = null,
)

data class NodeRuntimeDiagnostics(
    val state: NodeRuntimeState,
    val rootfsInstalled: Boolean,
    val nodeInstalled: Boolean,
    val nodeVersion: String?,
    val npmVersion: String?,
    val abi: String,
    val libcInfo: String,
    val daemonPid: Long?,
    val port: Int,
    val healthOk: Boolean,
    val healthPayload: String?,
    val crashCount: Int,
    val lastError: String?,
    val recentLogs: List<String>,
)

/**
 * Shared Linux Workspace Node.js Runtime Manager.
 *
 * Manages:
 * 1) Automatic Ubuntu Base Rootfs provisioning (reusing existing workspace rootfs if present, or downloading
 *    from domestic mirrors / official Ubuntu cdimage while preserving any already-extracted `/usr/local/node`);
 * 2) Official prebuilt Node.js LTS (`>= 20.11.0`, default `v20.18.1`) download with mirror fallback, SHA-256
 *    verification, safe extraction into `<linuxDir>/usr/local/node`, and execution self-test (`node --version`, `npm --version`);
 * 3) Persistent background Node.js HTTP daemon lifecycle (start without one-shot `--kill-on-exit`,
 *    stop, PID tracking, loopback `/health` polling, crash detection & bounded exponential-backoff restart);
 * 4) Log collection with sensitive token/API-key redaction and full diagnostics.
 */
class NodeRuntimeManager(
    private val workspaceManager: WorkspaceManager,
    private val nativeLibraryDir: File? = null,
    private val rootfsPatcher: RootfsPatcher = RootfsPatcher(),
    private val hostNodeExecutableOverride: String? = null,
) {
    private val stateRef = AtomicReference(NodeRuntimeState.NOT_INSTALLED)
    private val lastErrorRef = AtomicReference<String?>(null)
    private val installInfoRef = AtomicReference<NodeInstallInfo?>(null)
    private val crashCount = AtomicInteger(0)
    private val stoppingIntentionally = AtomicBoolean(false)
    private val logs = ConcurrentLinkedDeque<String>()

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var currentPort: Int = DEFAULT_PORT

    @Volatile
    private var currentAuthToken: String = ""

    val state: NodeRuntimeState
        get() = stateRef.get()

    val lastError: String?
        get() = lastErrorRef.get()

    fun getRecentLogs(limit: Int = 100): List<String> =
        logs.toList().takeLast(limit)

    fun appendLog(line: String) {
        val redacted = redactSensitiveLog(line)
        logs.addLast("${System.currentTimeMillis()} $redacted")
        while (logs.size > MAX_LOG_LINES) {
            logs.pollFirst()
        }
    }

    /**
     * Ensures that the target workspace [root] has a valid Linux rootfs (`bin/sh`).
     * - If `hostNodeExecutableOverride` is set, no rootfs is needed.
     * - If another workspace under the same baseDir already has a rootfs installed, copies it directly.
     * - Otherwise downloads Ubuntu 24.04 Base rootfs from candidate mirrors (Tsinghua / USTC / Official)
     *   and preserves any already-extracted `usr/local/node` directory.
     */
    fun ensureRootfsInstalled(
        root: String = SHARED_WORKSPACE_ROOT,
        arch: String = detectArch(),
        onProgress: (String) -> Unit = {},
    ) {
        workspaceManager.ensureWorkspace(root)
        if (!hostNodeExecutableOverride.isNullOrBlank() || workspaceManager.hasRootfs(root)) {
            return
        }

        val linuxDir = workspaceManager.linuxDir(root)
        val tempDir = workspaceManager.tempDir(root).apply { mkdirs() }
        val existingNodeDir = File(linuxDir, "usr/local/node")
        val preservedNodeDir = File(tempDir, "preserved-usr-local-node")

        // Preserve already-extracted Node.js if present from a prior attempt
        val hadExtractedNode = File(existingNodeDir, "bin/node").exists()
        if (hadExtractedNode) {
            preservedNodeDir.deleteRecursively()
            if (!existingNodeDir.renameTo(preservedNodeDir)) {
                existingNodeDir.copyRecursively(preservedNodeDir, overwrite = true)
            }
            appendLog("[rootfs] preserved existing /usr/local/node before rootfs provisioning")
        }

        try {
            // 1. Check if any sibling workspace already has a ready rootfs we can clone locally
            val workspacesBase = workspaceManager.workspaceDir(root).parentFile
            val donorLinuxDir = workspacesBase?.listFiles()
                ?.firstOrNull { dir ->
                    dir.isDirectory &&
                        dir.name != root &&
                        File(dir, "linux/bin/sh").isFile
                }
                ?.let { File(it, "linux") }

            if (donorLinuxDir != null) {
                onProgress("正在复用本地已有 Linux Rootfs…")
                appendLog("[rootfs] cloning existing rootfs from ${donorLinuxDir.parentFile?.name}")
                donorLinuxDir.copyRecursively(linuxDir, overwrite = true)
                rootfsPatcher.patch(linuxDir)
            } else {
                // 2. Download Ubuntu Base Rootfs from mirrors
                val rootfsUrls = resolveRootfsMirrorUrls(arch)
                val installer = RootfsInstaller(workspaceManager, rootfsPatcher)
                val errors = mutableListOf<String>()
                var installed = false
                for (rootfsUrl in rootfsUrls) {
                    try {
                        onProgress("正在下载并部署 Linux 基础环境 (Rootfs): $rootfsUrl")
                        appendLog("[rootfs] downloading rootfs from $rootfsUrl")
                        installer.install(root, rootfsUrl) { prog ->
                            if (prog.stage == RootfsInstallStage.EXTRACTING && prog.entriesExtracted % 500 == 0) {
                                onProgress("正在解压 Linux Rootfs (${prog.entriesExtracted} 项)…")
                            }
                        }
                        if (workspaceManager.hasRootfs(root)) {
                            appendLog("[rootfs] rootfs installed successfully from $rootfsUrl")
                            installed = true
                            break
                        }
                    } catch (e: Exception) {
                        val msg = "$rootfsUrl -> ${e.message ?: e.javaClass.simpleName}"
                        errors += msg
                        appendLog("[rootfs] mirror failed: $msg")
                    }
                }
                if (!installed && !workspaceManager.hasRootfs(root)) {
                    error("Linux Rootfs 自动安装失败（已尝试 ${rootfsUrls.size} 个镜像源）：${errors.joinToString("; ")}")
                }
            }
        } finally {
            // Restore preserved /usr/local/node if we had one
            if (hadExtractedNode && File(preservedNodeDir, "bin/node").exists()) {
                val targetNodeDir = File(linuxDir, "usr/local/node")
                targetNodeDir.parentFile?.mkdirs()
                targetNodeDir.deleteRecursively()
                if (!preservedNodeDir.renameTo(targetNodeDir)) {
                    preservedNodeDir.copyRecursively(targetNodeDir, overwrite = true)
                    preservedNodeDir.deleteRecursively()
                }
                restoreNodeExecutablePermissionsAndLinks(linuxDir, targetNodeDir)
                appendLog("[rootfs] restored preserved /usr/local/node into rootfs")
            }
        }
    }

    private fun restoreNodeExecutablePermissionsAndLinks(linuxDir: File, targetNodeDir: File) {
        File(targetNodeDir, "bin/node").setExecutable(true, false)
        File(targetNodeDir, "bin/npm").setExecutable(true, false)
        File(targetNodeDir, "bin/npx").setExecutable(true, false)
        val usrLocalBin = File(linuxDir, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx").forEach { binName ->
            val linkFile = File(usrLocalBin, binName)
            linkFile.delete()
            runCatching {
                java.nio.file.Files.createSymbolicLink(
                    linkFile.toPath(),
                    java.nio.file.Paths.get("../node/bin/$binName"),
                )
            }
        }
    }

    /**
     * Download and install official prebuilt Node.js binary archive into `<linuxDir>/usr/local/node`.
     * Automatically provisions Ubuntu Base rootfs first if missing, and tries domestic mirrors if official
     * `nodejs.org` is slow or unreachable.
     */
    fun downloadAndInstall(
        root: String = SHARED_WORKSPACE_ROOT,
        arch: String = detectArch(),
        urlOverride: String? = null,
        sha256Override: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<NodeInstallInfo> = runCatching {
        stateRef.set(NodeRuntimeState.INSTALLING)
        lastErrorRef.set(null)
        workspaceManager.ensureWorkspace(root)

        // Step 1: Ensure Linux Rootfs is installed (fixes "Linux rootfs is not installed in workspace 'shared_tavern_node'")
        ensureRootfsInstalled(root = root, arch = arch, onProgress = onProgress)

        // Step 2: If /usr/local/node/bin/node was already extracted in a previous attempt before rootfs existed,
        // probe it first to avoid redundant 25MB re-download!
        val linuxDir = workspaceManager.linuxDir(root)
        if (urlOverride.isNullOrBlank() && File(linuxDir, "usr/local/node/bin/node").isFile) {
            val existingProbe = probeInstalledNode(root)
            if (existingProbe.isSuccess) {
                val info = existingProbe.getOrThrow()
                installInfoRef.set(info)
                stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
                appendLog("[install] reused already-extracted node=${info.nodeVersion} npm=${info.npmVersion}")
                return@runCatching info
            }
        }

        val spec = resolveArchiveSpec(arch, urlOverride, sha256Override)
        val candidateUrls = if (!urlOverride.isNullOrBlank()) {
            listOf(spec.url)
        } else {
            resolveNodeMirrorUrls(spec)
        }
        val tempDir = workspaceManager.tempDir(root).apply { mkdirs() }
        val archiveFile = File(tempDir, spec.fileName)

        try {
            var lastDownloadErr: Exception? = null
            var downloaded = false
            for (candidateUrl in candidateUrls) {
                try {
                    onProgress("Downloading $candidateUrl")
                    appendLog("[install] downloading $candidateUrl")
                    downloadFile(candidateUrl, archiveFile)
                    downloaded = true
                    break
                } catch (e: Exception) {
                    lastDownloadErr = e
                    appendLog("[install] download mirror failed ($candidateUrl): ${e.message}")
                }
            }
            if (!downloaded) {
                throw (lastDownloadErr ?: IllegalStateException("Failed to download Node.js archive"))
            }
            installFromArchive(
                root = root,
                archiveFile = archiveFile,
                expectedSha256 = spec.sha256,
                arch = arch,
                onProgress = onProgress,
            ).getOrThrow()
        } finally {
            archiveFile.delete()
        }
    }.onFailure { err ->
        stateRef.set(NodeRuntimeState.ERROR)
        lastErrorRef.set(err.message ?: err.javaClass.simpleName)
        appendLog("[install] failed: ${err.message}")
    }

    /**
     * Verify SHA-256, ensure Rootfs is present, unpack `.tar.xz` / `.tar.gz` into `<linuxDir>/usr/local/node`,
     * symlink `/usr/local/bin/{node,npm,npx}`, and run execution self-test.
     */
    fun installFromArchive(
        root: String = SHARED_WORKSPACE_ROOT,
        archiveFile: File,
        expectedSha256: String? = null,
        arch: String = detectArch(),
        onProgress: (String) -> Unit = {},
    ): Result<NodeInstallInfo> = runCatching {
        require(archiveFile.isFile) { "Node.js archive does not exist: ${archiveFile.absolutePath}" }
        stateRef.set(NodeRuntimeState.INSTALLING)
        lastErrorRef.set(null)
        workspaceManager.ensureWorkspace(root)

        if (!expectedSha256.isNullOrBlank()) {
            onProgress("Verifying SHA-256 checksum")
            val actual = computeSha256(archiveFile)
            require(actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                "SHA-256 mismatch for ${archiveFile.name}: expected=$expectedSha256 actual=$actual"
            }
            appendLog("[install] SHA-256 verified: $actual")
        }

        // Ensure rootfs is present before extracting into linuxDir
        if (hostNodeExecutableOverride.isNullOrBlank() && !workspaceManager.hasRootfs(root)) {
            ensureRootfsInstalled(root = root, arch = arch, onProgress = onProgress)
        }

        val linuxDir = workspaceManager.linuxDir(root)
        val tempDir = workspaceManager.tempDir(root).apply { mkdirs() }
        val stagingDir = File(tempDir, "node-extract-staging").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            onProgress("Extracting ${archiveFile.name}")
            val installer = RootfsInstaller(workspaceManager, rootfsPatcher)
            installer.extractArchive(archiveFile, stagingDir)

            val topDirs = stagingDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            val extractedRoot = if (topDirs.size == 1 && File(topDirs[0], "bin/node").exists()) {
                topDirs[0]
            } else {
                stagingDir
            }
            require(File(extractedRoot, "bin/node").exists()) {
                "Extracted archive does not contain bin/node"
            }

            val targetNodeDir = File(linuxDir, "usr/local/node")
            targetNodeDir.parentFile?.mkdirs()
            targetNodeDir.deleteRecursively()
            if (!extractedRoot.renameTo(targetNodeDir)) {
                extractedRoot.copyRecursively(targetNodeDir, overwrite = true)
            }
            restoreNodeExecutablePermissionsAndLinks(linuxDir, targetNodeDir)
        } finally {
            stagingDir.deleteRecursively()
        }

        onProgress("Running node & npm execution self-test")
        val info = probeInstalledNode(root).getOrThrow()
        installInfoRef.set(info)
        stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
        appendLog("[install] verified node=${info.nodeVersion} npm=${info.npmVersion} arch=${info.arch}")
        info
    }.onFailure { err ->
        stateRef.set(NodeRuntimeState.ERROR)
        lastErrorRef.set(err.message ?: err.javaClass.simpleName)
        appendLog("[install] archive install failed: ${err.message}")
    }

    /**
     * Execute real `node --version`, `npm --version`, and JS evaluation inside the runtime
     * to verify that binary ABI, dynamic linker, and libstdc++/libgcc are functional.
     */
    fun probeInstalledNode(root: String = SHARED_WORKSPACE_ROOT): Result<NodeInstallInfo> = runCatching {
        workspaceManager.ensureWorkspace(root)
        val hostNode = hostNodeExecutableOverride
        if (!hostNode.isNullOrBlank()) {
            val probeProc = ProcessBuilder(
                hostNode,
                "-e",
                "console.log(JSON.stringify({node:process.version,arch:process.arch,platform:process.platform}))",
            ).redirectErrorStream(true).start()
            val out = probeProc.inputStream.bufferedReader().readText().trim()
            val code = probeProc.waitFor()
            require(code == 0) { "Host node probe exited with $code: $out" }
            val npmProc = ProcessBuilder("npm", "--version").redirectErrorStream(true).start()
            val npmOut = npmProc.inputStream.bufferedReader().readText().trim()
            npmProc.waitFor()
            val nodeVer = Regex("\"node\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1) ?: out
            val arch = Regex("\"arch\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1) ?: "unknown"
            val platform = Regex("\"platform\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1) ?: "linux"
            require(isNodeVersionSupported(nodeVer)) {
                "Unsupported Node.js version $nodeVer (requires >= 20.11.0 or >= 18.20.0)"
            }
            val info = NodeInstallInfo(
                nodeVersion = nodeVer,
                npmVersion = npmOut.ifBlank { "bundled" },
                arch = arch,
                platform = platform,
                binaryRootfsPath = hostNode,
            )
            installInfoRef.set(info)
            if (stateRef.get() == NodeRuntimeState.NOT_INSTALLED) {
                stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
            }
            return@runCatching info
        }

        require(workspaceManager.hasRootfs(root)) {
            "Linux rootfs is not installed in workspace '$root'"
        }
        val linuxDir = workspaceManager.linuxDir(root)
        val candidatePaths = listOf(
            "/usr/local/node/bin/node",
            "/usr/local/bin/node",
            "/usr/bin/node",
        )
        val existingPath = candidatePaths.firstOrNull { rel ->
            File(linuxDir, rel.trimStart('/')).exists()
        } ?: error("Node.js binary not found in rootfs (${candidatePaths.joinToString()})")

        val cmd = """
            export PATH="/usr/local/node/bin:/usr/local/bin:/usr/bin:/bin"
            NODE_JSON="$($existingPath -e 'console.log(JSON.stringify({node:process.version,arch:process.arch,platform:process.platform}))')"
            NPM_VER="$(npm --version 2>/dev/null || echo 'unavailable')"
            printf '%s\nNPM=%s\n' "${'$'}NODE_JSON" "${'$'}NPM_VER"
        """.trimIndent()

        val res = workspaceManager.executeCommand(
            root = root,
            command = cmd,
            timeoutMillis = 15_000L,
            shellCompatibilityMode = true,
        )
        require(res.exitCode == 0) {
            "Node.js execution self-test failed (exit=${res.exitCode}): ${res.stderr.ifBlank { res.stdout }}"
        }
        val lines = res.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val jsonLine = lines.firstOrNull { it.startsWith("{") }
            ?: error("Unexpected node probe output: ${res.stdout}")
        val npmLine = lines.firstOrNull { it.startsWith("NPM=") }?.removePrefix("NPM=").orEmpty()
        val nodeVer = Regex("\"node\"\\s*:\\s*\"([^\"]+)\"").find(jsonLine)?.groupValues?.get(1).orEmpty()
        val arch = Regex("\"arch\"\\s*:\\s*\"([^\"]+)\"").find(jsonLine)?.groupValues?.get(1).orEmpty()
        val platform = Regex("\"platform\"\\s*:\\s*\"([^\"]+)\"").find(jsonLine)?.groupValues?.get(1).orEmpty()
        require(isNodeVersionSupported(nodeVer)) {
            "Node.js version $nodeVer does not satisfy runtime requirement (SillyTavern 1.19.0 engines.node >= 18.0.0; rikkaST requires >= 18.20.0 or >= 20.11.0 LTS)"
        }
        val info = NodeInstallInfo(
            nodeVersion = nodeVer,
            npmVersion = npmLine,
            arch = arch,
            platform = platform,
            binaryRootfsPath = existingPath,
        )
        installInfoRef.set(info)
        if (stateRef.get() == NodeRuntimeState.NOT_INSTALLED) {
            stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
        }
        info
    }

    /**
     * Start the long-lived Node.js service daemon bound to `127.0.0.1:$port`.
     * Unlike one-shot `ProotShellRunner.execute`, this method does NOT pass `--kill-on-exit`
     * to a short-lived shell wrapper and instead supervises the persistent `Process` directly.
     */
    @Synchronized
    fun startDaemon(
        root: String = SHARED_WORKSPACE_ROOT,
        bootstrapScriptFile: File,
        port: Int = DEFAULT_PORT,
        authToken: String,
        extraEnv: Map<String, String> = emptyMap(),
        startupTimeoutMs: Long = 12_000L,
        autoRestartOnCrash: Boolean = true,
    ): Result<Int> = runCatching {
        val currentProc = activeProcess
        if (currentProc != null && currentProc.isAlive) {
            val h = checkHealth(currentPort)
            if (h.ok) {
                stateRef.set(NodeRuntimeState.RUNNING_HEALTHY)
                return@runCatching currentPort
            }
            stopDaemon()
        }

        stoppingIntentionally.set(false)
        stateRef.set(NodeRuntimeState.STARTING)
        lastErrorRef.set(null)
        currentPort = port
        currentAuthToken = authToken

        val process = launchDaemonProcess(
            root = root,
            bootstrapScriptFile = bootstrapScriptFile,
            port = port,
            authToken = authToken,
            extraEnv = extraEnv,
        )
        activeProcess = process
        writePidFile(root, process.pidCompat())

        attachOutputReaders(
            process = process,
            root = root,
            bootstrapScriptFile = bootstrapScriptFile,
            port = port,
            authToken = authToken,
            extraEnv = extraEnv,
            autoRestartOnCrash = autoRestartOnCrash,
        )

        val deadline = System.currentTimeMillis() + startupTimeoutMs
        var lastHealth = NodeHealthStatus(ok = false, statusCode = 0, body = "")
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val exit = process.exitValue()
                error("Node.js daemon exited prematurely during startup with code $exit")
            }
            lastHealth = checkHealth(port)
            if (lastHealth.ok) {
                stateRef.set(NodeRuntimeState.RUNNING_HEALTHY)
                appendLog("[daemon] healthy on 127.0.0.1:$port (pid=${process.pidCompat()})")
                return@runCatching port
            }
            Thread.sleep(120L)
        }

        stopDaemon()
        error("Node.js daemon health check timed out after ${startupTimeoutMs}ms: ${lastHealth.error ?: lastHealth.body}")
    }.onFailure { err ->
        stateRef.set(NodeRuntimeState.ERROR)
        lastErrorRef.set(err.message ?: err.javaClass.simpleName)
        appendLog("[daemon] start failed: ${err.message}")
    }

    private fun launchDaemonProcess(
        root: String,
        bootstrapScriptFile: File,
        port: Int,
        authToken: String,
        extraEnv: Map<String, String>,
    ): Process {
        workspaceManager.ensureWorkspace(root)
        val filesDir = workspaceManager.filesDir(root)
        val linuxDir = workspaceManager.linuxDir(root)
        val tempDir = workspaceManager.tempDir(root).apply { mkdirs() }

        val hostNode = hostNodeExecutableOverride
        if (!hostNode.isNullOrBlank()) {
            require(bootstrapScriptFile.isFile) {
                "Bootstrap script not found: ${bootstrapScriptFile.absolutePath}"
            }
            val pb = ProcessBuilder(hostNode, bootstrapScriptFile.absolutePath)
                .directory(filesDir)
                .redirectErrorStream(false)
            pb.environment()["RIKKA_NODE_PORT"] = port.toString()
            pb.environment()["RIKKA_NODE_TOKEN"] = authToken
            pb.environment().putAll(extraEnv)
            return pb.start()
        }

        require(workspaceManager.hasRootfs(root)) { "Rootfs is not installed in workspace '$root'" }
        val libDir = nativeLibraryDir ?: error("nativeLibraryDir is required for PRoot execution")
        val proot = File(libDir, "libproot_exec.so")
        val loader = File(libDir, "libproot_loader.so")
        require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
        require(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

        rootfsPatcher.patch(linuxDir)

        // Stage bootstrap script and plugins inside workspace filesDir so it is visible at /workspace/node-host/
        val hostStageDir = File(filesDir, "node-host").apply { mkdirs() }
        val stagedBootstrap = File(hostStageDir, bootstrapScriptFile.name)
        if (bootstrapScriptFile.canonicalFile != stagedBootstrap.canonicalFile) {
            bootstrapScriptFile.parentFile?.copyRecursively(hostStageDir, overwrite = true)
        }

        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "-r",
            linuxDir.absolutePath,
            "-w",
            WorkspaceManager.ROOTFS_WORKSPACE_DIR,
            "-b",
            "${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}",
        )
        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { kPath ->
            if (File(kPath).exists()) {
                command += "-b"
                command += kPath
            }
        }
        val envArgs = mutableListOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "NODE_ENV=production",
            "RIKKA_NODE_PORT=$port",
            "RIKKA_NODE_TOKEN=$authToken",
        )
        extraEnv.forEach { (k, v) -> envArgs += "$k=$v" }
        command += envArgs
        command += listOf(
            "/usr/local/node/bin/node",
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/node-host/${bootstrapScriptFile.name}",
        )

        return ProcessBuilder(command)
            .directory(filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
            }
            .start()
    }

    private fun attachOutputReaders(
        process: Process,
        root: String,
        bootstrapScriptFile: File,
        port: Int,
        authToken: String,
        extraEnv: Map<String, String>,
        autoRestartOnCrash: Boolean,
    ) {
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { seq ->
                    seq.forEach { line -> appendLog("[stdout] $line") }
                }
            }
        }, "rikka-node-stdout").apply { isDaemon = true }.start()

        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { seq ->
                    seq.forEach { line -> appendLog("[stderr] $line") }
                }
            }
        }, "rikka-node-stderr").apply { isDaemon = true }.start()

        Thread({
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            if (activeProcess === process) {
                activeProcess = null
                clearPidFile(root)
                if (!stoppingIntentionally.get()) {
                    val crashes = crashCount.incrementAndGet()
                    stateRef.set(NodeRuntimeState.CRASHED)
                    lastErrorRef.set("Daemon exited unexpectedly with code $exitCode (crash #$crashes)")
                    appendLog("[daemon] crashed with exitCode=$exitCode (count=$crashes)")
                    if (autoRestartOnCrash && crashes <= MAX_CRASH_RESTARTS) {
                        val backoffMs = 500L * crashes
                        appendLog("[daemon] scheduling auto-restart in ${backoffMs}ms (attempt $crashes/$MAX_CRASH_RESTARTS)")
                        Thread.sleep(backoffMs)
                        if (!stoppingIntentionally.get()) {
                            startDaemon(
                                root = root,
                                bootstrapScriptFile = bootstrapScriptFile,
                                port = port,
                                authToken = authToken,
                                extraEnv = extraEnv,
                                autoRestartOnCrash = autoRestartOnCrash,
                            )
                        }
                    }
                } else {
                    stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
                    appendLog("[daemon] stopped cleanly (exitCode=$exitCode)")
                }
            }
        }, "rikka-node-waiter").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stopDaemon(root: String = SHARED_WORKSPACE_ROOT): Boolean {
        stoppingIntentionally.set(true)
        val proc = activeProcess
        activeProcess = null
        clearPidFile(root)
        if (proc == null) {
            if (stateRef.get() == NodeRuntimeState.RUNNING_HEALTHY || stateRef.get() == NodeRuntimeState.STARTING) {
                stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
            }
            return true
        }
        return try {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(1, TimeUnit.SECONDS)
            }
            stateRef.set(NodeRuntimeState.INSTALLED_STOPPED)
            true
        } catch (e: Exception) {
            appendLog("[daemon] stop error: ${e.message}")
            false
        }
    }

    fun checkHealth(port: Int = currentPort, timeoutMs: Int = 1500): NodeHealthStatus {
        val url = URL("http://127.0.0.1:$port/health")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            NodeHealthStatus(
                ok = code == 200 && body.contains("\"ok\":true"),
                statusCode = code,
                body = body,
            )
        } catch (e: Exception) {
            NodeHealthStatus(
                ok = false,
                statusCode = 0,
                body = "",
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            conn.disconnect()
        }
    }

    fun runDiagnostics(root: String = SHARED_WORKSPACE_ROOT): NodeRuntimeDiagnostics {
        val rootfsOk = workspaceManager.hasRootfs(root) || !hostNodeExecutableOverride.isNullOrBlank()
        val probe = if (rootfsOk) probeInstalledNode(root).getOrNull() else null
        val health = checkHealth(currentPort)
        val linuxDir = workspaceManager.linuxDir(root)
        val libcInfo = when {
            !hostNodeExecutableOverride.isNullOrBlank() -> "host-libc"
            File(linuxDir, "lib/aarch64-linux-gnu/libc.so.6").exists() -> "glibc (aarch64-linux-gnu)"
            File(linuxDir, "lib/x86_64-linux-gnu/libc.so.6").exists() -> "glibc (x86_64-linux-gnu)"
            File(linuxDir, "lib/ld-musl-aarch64.so.1").exists() -> "musl (aarch64)"
            health.ok && health.body.contains("builtin-jvm-companion") -> "jvm-builtin-companion"
            else -> "unknown"
        }
        val effectiveState = when {
            health.ok -> NodeRuntimeState.RUNNING_HEALTHY
            probe != null && stateRef.get() == NodeRuntimeState.NOT_INSTALLED -> NodeRuntimeState.INSTALLED_STOPPED
            else -> stateRef.get()
        }
        return NodeRuntimeDiagnostics(
            state = effectiveState,
            rootfsInstalled = rootfsOk,
            nodeInstalled = probe != null,
            nodeVersion = probe?.nodeVersion
                ?: installInfoRef.get()?.nodeVersion
                ?: if (health.ok && health.body.contains("builtin-jvm-companion")) "v20.18.1-compat (内置伴生引擎)" else null,
            npmVersion = probe?.npmVersion
                ?: installInfoRef.get()?.npmVersion
                ?: if (health.ok && health.body.contains("builtin-jvm-companion")) "builtin" else null,
            abi = probe?.arch ?: detectArch(),
            libcInfo = libcInfo,
            daemonPid = activeProcess?.takeIf { it.isAlive }?.pidCompat(),
            port = currentPort,
            healthOk = health.ok,
            healthPayload = health.body.takeIf { it.isNotBlank() },
            crashCount = crashCount.get(),
            lastError = if (health.ok) null else lastErrorRef.get(),
            recentLogs = getRecentLogs(50),
        )
    }

    private fun writePidFile(root: String, pid: Long?) {
        if (pid == null || pid <= 0) return
        runCatching {
            val pidFile = File(workspaceManager.tempDir(root).apply { mkdirs() }, "rikka-node.pid")
            pidFile.writeText(pid.toString())
        }
    }

    private fun clearPidFile(root: String) {
        runCatching {
            File(workspaceManager.tempDir(root), "rikka-node.pid").delete()
        }
    }

    private fun Process.pidCompat(): Long? = runCatching {
        val m = Process::class.java.getMethod("pid")
        (m.invoke(this) as? Long)
    }.getOrNull()

    private fun downloadFile(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "HTTP $code downloading $url" }
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 32 * 1024)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    data class NodeArchiveSpec(
        val version: String,
        val arch: String,
        val fileName: String,
        val url: String,
        val sha256: String?,
    )

    companion object {
        const val SHARED_WORKSPACE_ROOT = "shared_tavern_node"
        const val ROOTFS_NODE_DIR = "/usr/local/node"
        const val DEFAULT_PORT = 18181
        const val NODE_PATH_PREFIX = "/usr/local/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        /**
         * Resolves the shared Node.js installation directory (`workspaces/shared_tavern_node/linux/usr/local/node`)
         * when the target workspace `linuxDir` does not have its own local `/usr/local/node/bin/node`.
         * Strictly resolves only from `shared_tavern_node` (never from arbitrary assistant workspaces), and
         * only the binary `/usr/local/node` directory is returned (never `files` or `tmp`), preserving strict
         * per-workspace `/workspace` and `/tmp` isolation.
         */
        fun resolveSharedNodeDir(linuxDir: File): File? {
            val localNodeBin = File(linuxDir, "usr/local/node/bin/node")
            if (localNodeBin.isFile) return null
            val workspacesBase = linuxDir.parentFile?.parentFile ?: return null
            val sharedNodeDir = File(workspacesBase, "$SHARED_WORKSPACE_ROOT/linux/usr/local/node")
            return if (File(sharedNodeDir, "bin/node").isFile) sharedNodeDir else null
        }
        const val DEFAULT_NODE_VERSION = "v20.18.1"
        private const val MAX_LOG_LINES = 300
        private const val MAX_CRASH_RESTARTS = 3

        // Official Node.js v20.18.1 release SHA-256 checksums from https://nodejs.org/dist/v20.18.1/SHASUMS256.txt
        const val SHA256_LINUX_ARM64 = "44d1ffc5905c005ace4515ca6f8c090c4c7cfce3a9a67df0dba35c727590b8f6"
        const val SHA256_LINUX_X64 = "c6fa75c841cbffac851678a472f2a5bd612fff8308ef39236190e1f8dbb0e567"
        const val NODE_ARM64_SHA256 = SHA256_LINUX_ARM64
        const val NODE_X64_SHA256 = SHA256_LINUX_X64

        fun detectArch(): String {
            val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
            return when {
                arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
                arch.contains("x86_64") || arch.contains("amd64") -> "x64"
                else -> "arm64"
            }
        }

        fun resolveRootfsMirrorUrls(arch: String = detectArch()): List<String> {
            val ubuntuArch = if (arch == "x64" || arch == "x86_64") "amd64" else "arm64"
            val fileName = "ubuntu-base-24.04.3-base-$ubuntuArch.tar.gz"
            return listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$fileName",
                "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$fileName",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/$fileName",
            )
        }

        fun resolveNodeMirrorUrls(spec: NodeArchiveSpec): List<String> {
            return listOf(
                "https://npmmirror.com/mirrors/node/${spec.version}/${spec.fileName}",
                "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/${spec.version}/${spec.fileName}",
                spec.url,
            ).distinct()
        }

        fun resolveArchiveSpec(
            arch: String = detectArch(),
            urlOverride: String? = null,
            sha256Override: String? = null,
        ): NodeArchiveSpec {
            val normalizedArch = if (arch == "x64" || arch == "x86_64") "x64" else "arm64"
            val fileName = "node-$DEFAULT_NODE_VERSION-linux-$normalizedArch.tar.xz"
            val defaultUrl = "https://nodejs.org/dist/$DEFAULT_NODE_VERSION/$fileName"
            val defaultSha = if (normalizedArch == "x64") SHA256_LINUX_X64 else SHA256_LINUX_ARM64
            return NodeArchiveSpec(
                version = DEFAULT_NODE_VERSION,
                arch = normalizedArch,
                fileName = fileName,
                url = urlOverride?.takeIf { it.isNotBlank() } ?: defaultUrl,
                sha256 = if (urlOverride.isNullOrBlank()) (sha256Override ?: defaultSha) else sha256Override,
            )
        }

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifySha256(file: File, expectedSha256: String): Boolean {
            if (!file.isFile || expectedSha256.isBlank()) return false
            return computeSha256(file).equals(expectedSha256.trim(), ignoreCase = true)
        }

        private fun parseSemverTuple(ver: String): Triple<Int, Int, Int>? {
            val clean = ver.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val nums = clean.split('.').map { it.trim() }
            if (nums.isEmpty()) return null
            val major = nums.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = nums.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = nums.getOrNull(2)?.toIntOrNull() ?: 0
            return Triple(major, minor, patch)
        }

        private fun compareSemver(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
            if (a.first != b.first) return a.first.compareTo(b.first)
            if (a.second != b.second) return a.second.compareTo(b.second)
            return a.third.compareTo(b.third)
        }

        fun satisfiesNodeEngine(versionStr: String, rangeExpr: String): Boolean {
            val v = parseSemverTuple(versionStr) ?: return false
            val orBranches = rangeExpr.split("||").map { it.trim() }.filter { it.isNotEmpty() }
            if (orBranches.isEmpty()) return true
            return orBranches.any { branch ->
                val tokens = branch.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
                tokens.all { tok ->
                    when {
                        tok.startsWith(">=") -> {
                            val target = parseSemverTuple(tok.removePrefix(">=")) ?: return@all false
                            compareSemver(v, target) >= 0
                        }
                        tok.startsWith("<=") -> {
                            val target = parseSemverTuple(tok.removePrefix("<=")) ?: return@all false
                            compareSemver(v, target) <= 0
                        }
                        tok.startsWith(">") -> {
                            val target = parseSemverTuple(tok.removePrefix(">")) ?: return@all false
                            compareSemver(v, target) > 0
                        }
                        tok.startsWith("<") -> {
                            val target = parseSemverTuple(tok.removePrefix("<")) ?: return@all false
                            compareSemver(v, target) < 0
                        }
                        tok.startsWith("^") -> {
                            val target = parseSemverTuple(tok.removePrefix("^")) ?: return@all false
                            v.first == target.first && compareSemver(v, target) >= 0
                        }
                        tok.startsWith("~") -> {
                            val target = parseSemverTuple(tok.removePrefix("~")) ?: return@all false
                            v.first == target.first && v.second == target.second && compareSemver(v, target) >= 0
                        }
                        else -> {
                            val target = parseSemverTuple(tok) ?: return@all false
                            compareSemver(v, target) == 0
                        }
                    }
                }
            }
        }

        fun isNodeVersionSupported(versionStr: String): Boolean {
            val clean = versionStr.trim().removePrefix("v")
            val parts = clean.split('.', '-').mapNotNull { it.toIntOrNull() }
            if (parts.size < 2) return false
            val major = parts[0]
            val minor = parts[1]
            // Official SillyTavern 1.19.0 package.json declares engines.node >= 18.0.0; rikkaST enforces >= 18.20.0 or >= 20.11.0 LTS (default v20.18.1)
            return (major > 20) || (major == 20 && minor >= 11) || (major == 18 && minor >= 20)
        }

        fun redactSensitiveLog(line: String): String {
            return line
                .replace(Regex("(Bearer\\s+)[A-Za-z0-9._\\-+/=]{6,}", RegexOption.IGNORE_CASE), "$1[REDACTED]")
                .replace(Regex("(RIKKA_NODE_TOKEN=)[^\\s]+"), "$1[REDACTED]")
                .replace(Regex("(pst-[A-Za-z0-9._\\-]{4})[A-Za-z0-9._\\-]+"), "$1[REDACTED]")
                .replace(Regex("(sk-[A-Za-z0-9._\\-]{4})[A-Za-z0-9._\\-]+"), "$1[REDACTED]")
                .replace(Regex("(\"key\"\\s*:\\s*\")([^\"]{6,})(\")", RegexOption.IGNORE_CASE), "$1[REDACTED]$3")
        }
    }
}
