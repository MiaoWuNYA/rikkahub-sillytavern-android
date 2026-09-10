package me.rerere.rikkahub.ui.pages.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallPage"

private val ColorIdle = Color(0xFF9D9A55)
private val ColorListening = Color(0xFFC6BD56)
private val ColorProcessing = Color(0xFFFDAE4F)
private val ColorSpeaking = Color(0xFFF58232)
private val ColorBgWarm = Color(0xFF17130E)

private fun statusAccentColor(status: VoiceCallStatus): Color = when (status) {
    VoiceCallStatus.Idle -> ColorIdle
    VoiceCallStatus.Listening -> ColorListening
    VoiceCallStatus.Processing -> ColorProcessing
    VoiceCallStatus.Speaking -> ColorSpeaking
    VoiceCallStatus.Error -> Color(0xFFE5484D)
}

@Composable
fun VoiceCallPage(
    conversationId: Uuid,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val conversationKey = conversationId.toString()

    val activeConversationId by VoiceCallService.activeConversationId
        .collectAsStateWithLifecycle(initialValue = VoiceCallService.activeConversationId.value)
    val activeSurface by VoiceCallService.activeCallSurface
        .collectAsStateWithLifecycle(initialValue = VoiceCallService.activeCallSurface.value)
    val isActiveVoiceCall = activeConversationId == conversationKey && activeSurface == VoiceCallSurface.Voice
    val anotherCallActive = activeConversationId != null && !isActiveVoiceCall

    if (anotherCallActive) {
        VoiceCallBlockedPage(onBack = onBack)
        return
    }

    var boundService by remember { mutableStateOf<VoiceCallService?>(null) }
    var callEverActive by remember(conversationId) { mutableStateOf(isActiveVoiceCall) }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)

    LaunchedEffect(isActiveVoiceCall) {
        if (isActiveVoiceCall) {
            callEverActive = true
        } else if (callEverActive && activeConversationId != conversationKey) {
            onBack()
        }
    }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? VoiceCallService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }

    DisposableEffect(conversationId) {
        if (
            asrPermission.allRequiredPermissionsGranted &&
            VoiceCallService.activeConversationId.value != conversationKey
        ) {
            VoiceCallService.start(context, conversationKey, VoiceCallSurface.Voice)
        }
        context.bindService(Intent(context, VoiceCallService::class.java), connection, Context.BIND_AUTO_CREATE)

        onDispose {
            runCatching { context.unbindService(connection) }
                .onFailure { Log.e(TAG, "unbindService failed", it) }
        }
    }

    LaunchedEffect(Unit) {
        if (!asrPermission.allRequiredPermissionsGranted) {
            asrPermission.requestPermissions()
        }
    }

    LaunchedEffect(asrPermission.allRequiredPermissionsGranted) {
        if (
            asrPermission.allRequiredPermissionsGranted &&
            VoiceCallService.activeConversationId.value == null
        ) {
            VoiceCallService.start(context, conversationKey, VoiceCallSurface.Voice)
        }
    }

    val uiState by (boundService?.uiState ?: MutableStateFlow(VoiceCallUiState()))
        .collectAsStateWithLifecycle(initialValue = VoiceCallUiState())
    val conversationFlow = boundService?.conversation
        ?.map { it as me.rerere.rikkahub.data.model.Conversation? }
        ?: flowOf(null)
    val conversation by conversationFlow.collectAsStateWithLifecycle(initialValue = null)
    val assistant = conversation?.assistantId?.let { settings.getAssistantById(it) }

    val displayName = assistant?.name.orEmpty().ifBlank {
        conversation?.title.orEmpty().ifBlank { "TA" }
    }
    val accentColor by animateColorAsState(
        targetValue = statusAccentColor(uiState.status),
        animationSpec = tween(durationMillis = 650),
        label = "voiceCallAccent",
    )

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBgWarm)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = .32f),
                            accentColor.copy(alpha = .10f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * .30f),
                        radius = size.maxDimension * .65f,
                    )
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(54.dp))

            VoiceCallAvatar(
                avatar = assistant?.avatar ?: Avatar.Dummy,
                displayName = displayName,
                accentColor = accentColor,
            )

            Text(
                displayName,
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )

            Text(
                "语音通话中",
                color = Color.White.copy(alpha = .58f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 5.dp),
            )

            Surface(
                color = accentColor.copy(alpha = .18f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Text(
                    statusText(uiState.status),
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            VoiceOrb(
                amplitudes = uiState.amplitudes,
                status = uiState.status,
                baseColor = accentColor,
                size = 118.dp,
            )

            if (boundService == null) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = .46f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(top = 14.dp).size(22.dp),
                )
            }

            val subtitleText = when (uiState.status) {
                VoiceCallStatus.Listening,
                VoiceCallStatus.Processing -> uiState.userTranscript
                VoiceCallStatus.Speaking,
                VoiceCallStatus.Idle -> uiState.assistantText
                VoiceCallStatus.Error -> ""
            }
            val subtitleAuthor = when (uiState.status) {
                VoiceCallStatus.Listening,
                VoiceCallStatus.Processing -> "你"
                else -> displayName
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (subtitleText.isNotBlank()) {
                    StreamingSubtitle(
                        author = subtitleAuthor,
                        text = subtitleText,
                        accentColor = accentColor,
                    )
                } else {
                    Text(
                        when (uiState.status) {
                            VoiceCallStatus.Listening -> "我在听。"
                            VoiceCallStatus.Processing -> "TA 正在想怎么回答你…"
                            VoiceCallStatus.Speaking -> "TA 正在说话…"
                            VoiceCallStatus.Error -> "通话出现了问题"
                            VoiceCallStatus.Idle -> "正在连接语音…"
                        },
                        color = Color.White.copy(alpha = .40f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                LabeledControlButton(
                    icon = if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                    label = if (uiState.isMuted) "取消静音" else "静音",
                    selected = uiState.isMuted,
                    enabled = boundService != null,
                ) { boundService?.toggleMute() }

                LabeledControlButton(
                    icon = HugeIcons.VolumeHigh,
                    label = if (uiState.isSpeakerEnabled) "扬声器" else "听筒",
                    selected = uiState.isSpeakerEnabled,
                    enabled = boundService != null,
                ) { boundService?.toggleSpeaker() }

                LabeledControlButton(
                    icon = HugeIcons.Cancel01,
                    label = "挂断",
                    destructive = true,
                    enabled = true,
                ) {
                    boundService?.endCall()
                    VoiceCallService.stop(context)
                    onBack()
                }
            }
        }
    }
}

@Composable
private fun VoiceCallBlockedPage(onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ColorBgWarm)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("当前已有其他通话", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "请先结束正在进行的语音或视频通话，再开始新的语音电话。",
                color = Color.White.copy(alpha = .62f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
            Button(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
private fun VoiceCallAvatar(
    avatar: Avatar,
    displayName: String,
    accentColor: Color,
) {
    Surface(
        modifier = Modifier
            .size(132.dp)
            .border(2.dp, accentColor.copy(alpha = .55f), CircleShape),
        shape = CircleShape,
        color = Color.White.copy(alpha = .08f),
        shadowElevation = 12.dp,
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape), contentAlignment = Alignment.Center) {
            when (avatar) {
                is Avatar.Image -> AsyncImage(
                    model = avatar.url,
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                is Avatar.Emoji -> Text(avatar.content, fontSize = 58.sp)
                else -> AutoAIIcon(name = displayName, modifier = Modifier.size(92.dp))
            }
        }
    }
}

@Composable
private fun StreamingSubtitle(
    author: String,
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        if (text.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = .20f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .heightIn(max = 154.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                author,
                color = accentColor.copy(alpha = .88f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text,
                color = Color.White.copy(alpha = .93f),
                fontSize = 16.sp,
                lineHeight = 25.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun LabeledControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ControlButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            backgroundColor = when {
                destructive -> Color(0xFFE5484D)
                selected -> Color.White.copy(alpha = .30f)
                else -> Color.White.copy(alpha = .13f)
            },
            iconTint = Color.White,
            enabled = enabled,
        )
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) .66f else .30f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconTint: Color,
    size: Dp = 62.dp,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (enabled) backgroundColor else backgroundColor.copy(alpha = .28f),
        modifier = Modifier.size(size),
        enabled = enabled,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = .42f),
                modifier = Modifier.size(size * .40f),
            )
        }
    }
}

private fun statusText(status: VoiceCallStatus): String = when (status) {
    VoiceCallStatus.Idle -> "连接中"
    VoiceCallStatus.Listening -> "正在听你说"
    VoiceCallStatus.Processing -> "正在想"
    VoiceCallStatus.Speaking -> "正在说话"
    VoiceCallStatus.Error -> "通话异常"
}
