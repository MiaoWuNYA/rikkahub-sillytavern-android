package me.rerere.rikkahub.ui.pages.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import kotlin.uuid.Uuid

/**
 * 剧情式视频电话。
 *
 * 画面层只负责呈现和交互；ASR / AI / TTS / 音频路由继续由 VoiceCallService 统一管理。
 * 从通知返回正在进行的视频时会直接恢复通话画面，不再重复显示“开始通话”。
 */
@Composable
fun VideoCallPage(conversationId: Uuid, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val visualStore = remember { VideoCallVisualSettingsStore(context) }
    val visualSettings = remember { visualStore.read() }
    val archiveStore = remember { VideoCallArchiveStore(context) }

    val activeConversationId by VoiceCallService.activeConversationId
        .collectAsStateWithLifecycle(initialValue = VoiceCallService.activeConversationId.value)
    val activeSurface by VoiceCallService.activeCallSurface
        .collectAsStateWithLifecycle(initialValue = VoiceCallService.activeCallSurface.value)

    val conversationKey = conversationId.toString()
    val isActiveVideoCall = activeConversationId == conversationKey && activeSurface == VoiceCallSurface.Video
    val anotherCallActive = activeConversationId != null && !isActiveVideoCall

    var joined by remember(conversationId) { mutableStateOf(isActiveVideoCall) }
    var callEverActive by remember(conversationId) { mutableStateOf(isActiveVideoCall) }

    // 点通知回到仍在运行的视频时直接恢复画面；不再让用户再点一次“开始通话”。
    LaunchedEffect(isActiveVideoCall) {
        if (isActiveVideoCall) {
            joined = true
            callEverActive = true
        } else if (joined && callEverActive && activeConversationId != conversationKey) {
            // 通知栏挂断 / Service 结束后，已经打开的视频页面自动退出，避免僵尸页面。
            onBack()
        }
    }

    if (!joined) {
        VideoCallJoinPage(
            visualSettings = visualSettings,
            blockedByAnotherCall = anotherCallActive,
            onJoin = { joined = true },
            onBack = onBack,
        )
        return
    }

    val audioPermission = rememberPermissionState(PermissionRecordAudio)
    val cameraPermission = rememberPermissionState(PermissionCamera)
    var service by remember { mutableStateOf<VoiceCallService?>(null) }
    var cameraEnabled by remember { mutableStateOf(false) }
    var cameraRequested by remember { mutableStateOf(false) }
    var typedInput by remember { mutableStateOf("") }
    var callBaselineCount by remember(conversationId) { mutableStateOf<Int?>(null) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? VoiceCallService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
    }

    DisposableEffect(conversationId) {
        if (
            audioPermission.allRequiredPermissionsGranted &&
            VoiceCallService.activeConversationId.value != conversationKey
        ) {
            VoiceCallService.start(context, conversationKey, VoiceCallSurface.Video)
        }
        context.bindService(Intent(context, VoiceCallService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose { runCatching { context.unbindService(connection) } }
    }

    LaunchedEffect(Unit) {
        if (!audioPermission.allRequiredPermissionsGranted) {
            audioPermission.requestPermissions()
        }
        if (visualSettings.selfViewMode == VideoCallSelfViewMode.FRONT_CAMERA) {
            cameraRequested = true
            if (!cameraPermission.allRequiredPermissionsGranted) {
                cameraPermission.requestPermissions()
            }
        }
    }

    LaunchedEffect(cameraPermission.allRequiredPermissionsGranted, cameraRequested) {
        if (cameraRequested && cameraPermission.allRequiredPermissionsGranted) {
            cameraEnabled = true
        }
    }

    LaunchedEffect(audioPermission.allRequiredPermissionsGranted) {
        if (
            audioPermission.allRequiredPermissionsGranted &&
            VoiceCallService.activeConversationId.value == null
        ) {
            VoiceCallService.start(context, conversationKey, VoiceCallSurface.Video)
        }
    }

    val uiState by (service?.uiState ?: MutableStateFlow(VoiceCallUiState()))
        .collectAsStateWithLifecycle(initialValue = VoiceCallUiState())
    val conversationFlow = service?.conversation
        ?.map { it as me.rerere.rikkahub.data.model.Conversation? }
        ?: flowOf(null)
    val conversation by conversationFlow.collectAsStateWithLifecycle(initialValue = null)
    val assistant = conversation?.assistantId?.let { settings.getAssistantById(it) }

    val displayName = assistant?.name.orEmpty().ifBlank {
        conversation?.title.orEmpty().ifBlank { "TA" }
    }

    LaunchedEffect(conversation?.id) {
        val current = conversation ?: return@LaunchedEffect
        if (callBaselineCount == null) {
            callBaselineCount = current.currentMessages.size
        }
    }

    LaunchedEffect(service, conversation?.assistantId, displayName) {
        val activeService = service ?: return@LaunchedEffect
        val current = conversation ?: return@LaunchedEffect
        VideoCallArchiveRuntime.attach(
            store = archiveStore,
            conversationId = conversationId,
            assistantId = current.assistantId,
            assistantName = displayName,
            conversationFlow = activeService.conversation,
        )
    }

    val callMessages = callBaselineCount?.let { baseline ->
        conversation?.currentMessages?.drop(baseline).orEmpty()
    }.orEmpty()
    val latestCallAssistantText = callMessages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.toText()
        .orEmpty()
    val latestCallUserText = callMessages
        .lastOrNull { it.role == MessageRole.USER }
        ?.toText()
        .orEmpty()
    val dialogueScrollState = rememberScrollState()

    // Keep the live turn visible while it is streaming, while persisted callMessages retain
    // every completed user/assistant turn for manual scrolling.
    val liveAssistantText = uiState.assistantText
        .takeIf { it.isNotBlank() && it != latestCallAssistantText }
        .orEmpty()
    val liveUserText = uiState.userTranscript
        .takeIf { it.isNotBlank() && it != latestCallUserText }
        .orEmpty()

    LaunchedEffect(callMessages.size, liveAssistantText, liveUserText) {
        // Follow new turns only while the viewer is already near the bottom. If they scroll up
        // to reread earlier video-call messages, streaming output must not steal the scroll.
        val distanceFromBottom = dialogueScrollState.maxValue - dialogueScrollState.value
        if (distanceFromBottom <= 160) {
            dialogueScrollState.animateScrollTo(dialogueScrollState.maxValue)
        }
    }

    fun sendTypedMessage() {
        val text = typedInput.trim()
        val activeService = service ?: return
        if (text.isBlank() || uiState.status == VoiceCallStatus.Processing) return
        activeService.sendTextMessage(text)
        typedInput = ""
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0B0D))) {
        VideoCallBackground(
            customPath = visualSettings.backgroundPath,
            assistantAvatar = assistant?.avatar ?: Avatar.Dummy,
            displayName = displayName,
            opacity = visualSettings.backgroundOpacity,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .30f),
                        .32f to Color.Transparent,
                        .68f to Color.Black.copy(alpha = .08f),
                        1f to Color.Black.copy(alpha = .78f),
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            VideoCallHeader(
                displayName = displayName,
                status = uiState.status,
                modifier = Modifier.padding(top = 50.dp, end = 118.dp),
            )

            Spacer(Modifier.height(104.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(dialogueScrollState)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    callMessages.forEach { message ->
                        when (message.role) {
                            MessageRole.USER -> {
                                val text = message.toText()
                                if (text.isNotBlank()) {
                                    VideoCallUserBubble(text)
                                }
                            }
                            MessageRole.ASSISTANT -> {
                                val text = message.toText()
                                if (text.isNotBlank()) {
                                    val messageStory = storyPresentation(text)
                                    VideoCallAssistantBubble(
                                        displayName = displayName,
                                        story = messageStory,
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }

                    if (liveUserText.isNotBlank()) {
                        VideoCallUserBubble(liveUserText)
                    }
                    if (liveAssistantText.isNotBlank()) {
                        VideoCallAssistantBubble(
                            displayName = displayName,
                            story = storyPresentation(liveAssistantText),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
            ) {
                OutlinedTextField(
                    value = typedInput,
                    onValueChange = { typedInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (uiState.status) {
                                VoiceCallStatus.Processing -> "TA 正在思考…"
                                VoiceCallStatus.Speaking -> "可以打字打断 TA…"
                                else -> "对话…"
                            },
                            color = Color.White.copy(.55f),
                        )
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { sendTypedMessage() },
                            enabled = typedInput.isNotBlank() &&
                                service != null &&
                                uiState.status != VoiceCallStatus.Processing,
                        ) { Text("发送") }
                    },
                    maxLines = 3,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallButton(
                        if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                        if (uiState.isMuted) "取消静音" else "静音",
                    ) { service?.toggleMute() }

                    CallButton(HugeIcons.Camera01, if (cameraEnabled) "头像" else "前置摄像头") {
                        if (cameraEnabled) {
                            cameraEnabled = false
                        } else if (!cameraPermission.allRequiredPermissionsGranted) {
                            cameraRequested = true
                            cameraPermission.requestPermissions()
                        } else {
                            cameraEnabled = true
                        }
                    }

                    CallButton(
                        HugeIcons.VolumeHigh,
                        if (uiState.isSpeakerEnabled) "扬声器" else "听筒",
                    ) { service?.toggleSpeaker() }

                    CallButton(HugeIcons.Cancel01, "挂断", Color(0xFFE5484D)) {
                        service?.endCall()
                        VoiceCallService.stop(context)
                        onBack()
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 88.dp, end = 16.dp)
                .size(width = 104.dp, height = 142.dp)
                .border(1.dp, Color.White.copy(alpha = .58f), RoundedCornerShape(18.dp)),
            color = Color.Black.copy(alpha = .25f),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp,
        ) {
            if (cameraEnabled && cameraPermission.allRequiredPermissionsGranted) {
                FrontCameraPreview(modifier = Modifier.fillMaxSize())
            } else {
                UserAvatarPreview(
                    customPath = visualSettings.userAvatarPath,
                    avatar = settings.displaySetting.userAvatar,
                    userName = settings.displaySetting.userNickname.ifBlank { "You" },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun VideoCallJoinPage(
    visualSettings: VideoCallVisualSettings,
    blockedByAnotherCall: Boolean,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF171016))
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(HugeIcons.Camera01, null, tint = Color(0xFFFFB7C5), modifier = Modifier.size(72.dp))
            Text("视频电话", color = Color.White, fontSize = 28.sp, modifier = Modifier.padding(top = 20.dp))
            Text(
                when {
                    blockedByAnotherCall -> "当前已有其他通话进行中，请先挂断后再开始视频电话。"
                    visualSettings.selfViewMode == VideoCallSelfViewMode.FRONT_CAMERA ->
                        "通话期间会持续使用麦克风；你已选择前置摄像头，开始后会请求摄像头权限。"
                    else ->
                        "通话期间会持续使用麦克风；右上角默认显示你的头像，不会自动打开摄像头。"
                },
                color = if (blockedByAnotherCall) Color(0xFFFFB7C5) else Color.White.copy(.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            Button(onClick = onJoin, enabled = !blockedByAnotherCall) { Text("开始通话") }
            TextButton(onClick = onBack) { Text("取消") }
        }
    }
}

@Composable
private fun VideoCallUserBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.82f),
            color = Color.White.copy(alpha = .16f),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                Text("你", color = Color.White.copy(.56f), fontSize = 11.sp)
                Text(
                    text,
                    color = Color.White.copy(.92f),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoCallAssistantBubble(
    displayName: String,
    story: StoryPresentation,
) {
    if (story.action.isNotBlank()) {
        Surface(
            modifier = Modifier.padding(top = 12.dp),
            color = Color.Black.copy(alpha = .26f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                story.action,
                color = Color.White.copy(.76f),
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }

    if (story.dialogue.isNotBlank()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            color = Color.Black.copy(alpha = .48f),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
                Text(
                    displayName,
                    color = Color.White.copy(.62f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    story.dialogue,
                    color = Color.White,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoCallHeader(
    displayName: String,
    status: VoiceCallStatus,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                displayName,
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                modifier = Modifier.padding(start = 10.dp),
                color = videoStatusColor(status),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    videoStatusText(status),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
        Text(
            "正在和你视频",
            color = Color.White.copy(.70f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

private fun videoStatusText(status: VoiceCallStatus): String = when (status) {
    VoiceCallStatus.Idle -> "连接中"
    VoiceCallStatus.Listening -> "正在听你说"
    VoiceCallStatus.Processing -> "正在想"
    VoiceCallStatus.Speaking -> "正在说话"
    VoiceCallStatus.Error -> "通话异常"
}

private fun videoStatusColor(status: VoiceCallStatus): Color = when (status) {
    VoiceCallStatus.Idle -> Color.Black.copy(alpha = .34f)
    VoiceCallStatus.Listening -> Color(0xFF587C69).copy(alpha = .86f)
    VoiceCallStatus.Processing -> Color(0xFF8A6B4A).copy(alpha = .88f)
    VoiceCallStatus.Speaking -> Color(0xFF9A6675).copy(alpha = .88f)
    VoiceCallStatus.Error -> Color(0xFFB84949).copy(alpha = .90f)
}

@Composable
private fun VideoCallBackground(
    customPath: String,
    assistantAvatar: Avatar,
    displayName: String,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val customFile = customPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
    Box(modifier.background(Color(0xFF171016)), contentAlignment = Alignment.Center) {
        when {
            customFile != null -> AsyncImage(
                model = customFile,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().visualAlpha(opacity),
                contentScale = ContentScale.Crop,
            )

            assistantAvatar is Avatar.Image -> AsyncImage(
                model = assistantAvatar.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().visualAlpha(opacity),
                contentScale = ContentScale.Crop,
            )

            assistantAvatar is Avatar.Emoji -> Text(
                assistantAvatar.content,
                fontSize = 112.sp,
                modifier = Modifier.visualAlpha(opacity),
            )

            else -> AutoAIIcon(
                name = displayName,
                modifier = Modifier.size(240.dp).visualAlpha(opacity),
            )
        }
    }
}

@Composable
private fun UserAvatarPreview(
    customPath: String,
    avatar: Avatar,
    userName: String,
    modifier: Modifier = Modifier,
) {
    val customFile = customPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF252126)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            customFile != null -> AsyncImage(
                model = customFile,
                contentDescription = "用户头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            avatar is Avatar.Image -> AsyncImage(
                model = avatar.url,
                contentDescription = "用户头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            avatar is Avatar.Emoji -> Text(avatar.content, fontSize = 46.sp)
            else -> AutoAIIcon(name = userName, modifier = Modifier.size(72.dp))
        }
    }
}

private fun Modifier.visualAlpha(alpha: Float): Modifier = drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = (1f - alpha.coerceIn(0.2f, 1f)) * .7f))
}

@Composable
private fun FrontCameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }

    AndroidView(
        factory = { textureView },
        modifier = modifier,
    )

    DisposableEffect(textureView) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraThread = HandlerThread("story-video-camera").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var previewSurface: Surface? = null

        fun closeCamera() {
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { previewSurface?.release() }
            captureSession = null
            cameraDevice = null
            previewSurface = null
        }

        fun openCamera() {
            if (!textureView.isAvailable) return
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        val texture = textureView.surfaceTexture ?: return
                        previewSurface = Surface(texture)
                        val surface = previewSurface ?: return
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    }.build()
                                    runCatching { session.setRepeatingRequest(request, null, cameraHandler) }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                            },
                            cameraHandler,
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) = closeCamera()
                    override fun onError(camera: CameraDevice, error: Int) = closeCamera()
                }, cameraHandler)
            } catch (_: SecurityException) {
                closeCamera()
            } catch (_: Exception) {
                closeCamera()
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = openCamera()

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) openCamera()

        onDispose {
            textureView.surfaceTextureListener = null
            closeCamera()
            cameraThread.quitSafely()
        }
    }
}

private data class StoryPresentation(val action: String, val dialogue: String)

private fun storyPresentation(text: String): StoryPresentation {
    val actionRegex = Regex("\\*([^*]{2,80})\\*|\\(([^()]{2,80})\\)")
    val match = actionRegex.find(text)
    val action = match?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
        .orEmpty().ifBlank { "TA 正在镜头那边陪着你" }
    return StoryPresentation(action, text.replace(actionRegex, "").trim())
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color = Color.Black.copy(.42f),
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color),
            modifier = Modifier.size(58.dp),
        ) {
            Icon(icon, label, tint = Color.White)
        }
        Text(
            label,
            color = Color.White.copy(.82f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
