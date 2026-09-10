/*
* rikkahub-plus
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.activity.ShortcutHandlerActivity
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.createCustomAsrState
import me.rerere.rikkahub.ui.hooks.createCustomTtsState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallSurface
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"

/**
 * 语音 / 剧情视频通话后台服务。
 *
 * ASR、AI 生成与 TTS 都跟随 Service 生命周期，页面只负责 bind + UI。返回聊天页或切到
 * 后台时通话继续；只有主动挂断才真正结束。
 */
class VoiceCallService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val httpClient: OkHttpClient by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "VoiceCallService coroutine exception", error)
        }
    )

    private lateinit var conversationId: Uuid
    private lateinit var asr: CustomAsrState
    private lateinit var tts: CustomTtsState

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var audioRoutePrepared = false
    private var callSurface: VoiceCallSurface = VoiceCallSurface.Voice
    private var resourcesReleased = false
    private var conversationReferenceHeld = false
    private var lastMessageSentAt = 0L

    private val _uiState = MutableStateFlow(VoiceCallUiState())
    val uiState: StateFlow<VoiceCallUiState> = _uiState.asStateFlow()

    val conversation: StateFlow<Conversation>
        get() = chatService.getConversationFlow(conversationId)

    private var vadJob: Job? = null
    private var speakingMonitorJob: Job? = null
    private var conversationMonitorJob: Job? = null
    private var chatErrorMonitorJob: Job? = null
    private var asrMonitorJob: Job? = null
    private var interruptDetectJob: Job? = null

    /** 已经发送给流式 TTS 的 assistant 文本长度。 */
    private var ttsSentLength = 0
    private var isMuted = false

    companion object {
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

        private val _activeCallSurface = MutableStateFlow<VoiceCallSurface?>(null)
        val activeCallSurface: StateFlow<VoiceCallSurface?> = _activeCallSurface.asStateFlow()

        fun isRunning(): Boolean = _activeConversationId.value != null

        /** 语音页保持默认 Voice；剧情视频页显式传 Video。 */
        fun start(
            context: Context,
            conversationId: String,
            surface: VoiceCallSurface = VoiceCallSurface.Voice,
        ) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_CALL_SURFACE, surface.name)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.e(TAG, "启动 VoiceCallService 失败, conversationId=$conversationId, surface=$surface", it)
                }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceCallService::class.java)) }
                .onFailure { Log.e(TAG, "停止 VoiceCallService 失败", it) }
        }

        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_CALL_SURFACE = "callSurface"
        const val ACTION_HANG_UP = "me.rerere.rikkahub.VOICE_CALL_HANG_UP"
        const val NOTIFICATION_ID = 40001
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            // 通知栏挂断没有页面按钮自己的 onBack，因此这里同步清空 active state，
            // 让仍打开的视频页通过 activeCall observer 自动返回聊天。
            endCall(clearActiveState = true)
            stopSelf()
            return START_NOT_STICKY
        }

        val convIdStr = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (convIdStr == null) {
            Log.e(TAG, "onStartCommand 缺少 conversationId 参数")
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedSurface = intent.getStringExtra(EXTRA_CALL_SURFACE)
            ?.let { runCatching { VoiceCallSurface.valueOf(it) }.getOrNull() }
            ?: VoiceCallSurface.Voice

        // 同一个通话被页面重新 bind / start 时不要重复初始化，也不要偷偷切换 surface。
        if (_activeConversationId.value == convIdStr) return START_NOT_STICKY
        if (_activeConversationId.value != null) {
            Log.w(TAG, "已有通话 ${_activeConversationId.value} 在进行, 忽略新的 start 请求 $convIdStr")
            return START_NOT_STICKY
        }

        conversationId = runCatching { Uuid.parse(convIdStr) }.getOrElse {
            Log.e(TAG, "conversationId 解析失败: $convIdStr", it)
            stopSelf()
            return START_NOT_STICKY
        }
        callSurface = requestedSurface
        resourcesReleased = false
        _activeConversationId.value = convIdStr
        _activeCallSurface.value = requestedSurface

        // Android 要求 startForegroundService 后 5 秒内同步进入前台。
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(_uiState.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败, conversationId=$conversationId", e)
            clearActiveCallState()
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                // 视频/语音页面离开聊天页后仍要长期观察同一个 ConversationSession。
                // 没有引用时 Session 5 秒后可能被回收，导致 AI 写入新 Session、通话 UI 还盯旧 Flow。
                holdConversationReference()
                chatService.initializeConversation(conversationId)

                asr = createCustomAsrState(applicationContext, httpClient, settingsStore)
                tts = createCustomTtsState(applicationContext, settingsStore)
                startCall()

                launch {
                    uiState.collect { state ->
                        runCatching {
                            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(state))
                        }.onFailure { Log.e(TAG, "刷新通话通知失败", it) }
                    }
                }

                launch {
                    asr.state.collect { asrState ->
                        updateAmplitudes(asrState.amplitudes)
                        if (asrState.status == me.rerere.asr.ASRStatus.Error) {
                            val message = asrState.errorMessage ?: "语音识别发生未知错误"
                            Log.e(TAG, "ASR 底层报错, conversationId=$conversationId, msg=$message")
                            _uiState.update {
                                it.copy(
                                    status = VoiceCallStatus.Error,
                                    errorMessage = "语音识别错误: $message",
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化通话失败, conversationId=$conversationId", e)
                releaseConversationReference()
                _uiState.update {
                    it.copy(
                        status = VoiceCallStatus.Error,
                        errorMessage = "初始化失败: ${e.message}",
                    )
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun holdConversationReference() {
        if (conversationReferenceHeld) return
        chatService.addConversationReference(conversationId)
        conversationReferenceHeld = true
    }

    private fun releaseConversationReference() {
        if (!conversationReferenceHeld) return
        conversationReferenceHeld = false
        chatService.removeConversationReference(conversationId)
    }

    private fun clearActiveCallState() {
        _activeConversationId.value = null
        _activeCallSurface.value = null
    }

    private fun prepareAudioRoute() {
        if (!audioRoutePrepared) {
            previousAudioMode = audioManager.mode
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            audioRoutePrepared = true
        }
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Log.w(TAG, "设置 MODE_IN_COMMUNICATION 失败", it) }
        // 视频默认外放，语音默认听筒。UI 直接读取同一份 state，不再出现“按钮写扬声器但实际没外放”。
        setSpeakerEnabled(callSurface == VoiceCallSurface.Video)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
            _uiState.update { it.copy(isSpeakerEnabled = enabled) }
        }.onFailure {
            Log.e(TAG, "切换扬声器失败, enabled=$enabled", it)
            _uiState.update { state -> state.copy(errorMessage = "音频输出切换失败: ${it.message}") }
        }
    }

    fun toggleSpeaker() = setSpeakerEnabled(!_uiState.value.isSpeakerEnabled)

    private fun restoreAudioRoute() {
        if (!audioRoutePrepared) return
        runCatching {
            @Suppress("DEPRECATION")
            previousSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
            previousAudioMode?.let { audioManager.mode = it }
        }.onFailure { Log.w(TAG, "恢复通话前音频路由失败", it) }
        previousSpeakerphoneOn = null
        previousAudioMode = null
        audioRoutePrepared = false
    }

    fun startCall() {
        if (_uiState.value.status != VoiceCallStatus.Idle) return
        ttsSentLength = 0
        isMuted = false
        prepareAudioRoute()
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                assistantText = "",
                errorMessage = null,
                isMuted = false,
            )
        }

        try {
            asr.start { transcript -> _uiState.update { it.copy(userTranscript = transcript) } }
        } catch (e: Exception) {
            Log.e(TAG, "启动 ASR 失败, conversationId=$conversationId", e)
            _uiState.update {
                it.copy(status = VoiceCallStatus.Error, errorMessage = "麦克风启动失败: ${e.message}")
            }
            return
        }

        startVadDetection()
        startAsrMonitor()
        startConversationMonitor()
    }

    /** 回到用户回合；若 ASR provider 已在上一轮停止，主动重新启动录音。 */
    private fun startListening() {
        if (::tts.isInitialized) tts.stop()
        ttsSentLength = 0
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                errorMessage = null,
            )
        }
        interruptDetectJob?.cancel()
        if (!isMuted && ::asr.isInitialized && !asr.state.value.isRecording) {
            restartAsrAfterProviderStop()
        }
        startVadDetection()
    }

    private fun startVadDetection() {
        vadJob?.cancel()
        vadJob = serviceScope.launch {
            var lastTranscript = ""
            var silenceStartTime = 0L
            var lastAmplitudeTime = System.currentTimeMillis()
            val silenceThresholdMs = 800L
            val minTranscriptLength = 2
            val amplitudeTimeoutMs = 2_000L

            while (true) {
                delay(100)
                if (_uiState.value.status != VoiceCallStatus.Listening) break
                if (isMuted || !_uiState.value.autoSendEnabled) continue

                val transcript = _uiState.value.userTranscript
                val recentAmplitude = _uiState.value.amplitudes.takeLast(3).average().toFloat()
                if (recentAmplitude > 0.05f) lastAmplitudeTime = System.currentTimeMillis()

                if (transcript != lastTranscript) {
                    lastTranscript = transcript
                    silenceStartTime = 0L
                } else if (transcript.length >= minTranscriptLength) {
                    if (silenceStartTime == 0L) silenceStartTime = System.currentTimeMillis()
                    val silentFor = System.currentTimeMillis() - silenceStartTime
                    val amplitudeSilentFor = System.currentTimeMillis() - lastAmplitudeTime
                    if (silentFor >= silenceThresholdMs || amplitudeSilentFor >= amplitudeTimeoutMs) {
                        Log.d(TAG, "VAD auto-send: $transcript")
                        sendCurrentMessage()
                        break
                    }
                }
            }
        }
    }

    private fun sendCurrentMessage() = sendCallMessage(_uiState.value.userTranscript.trim())

    /** 视频电话文字输入与语音输入共用 Processing → AI → TTS → Listening 状态机。 */
    fun sendTextMessage(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank() || _uiState.value.status == VoiceCallStatus.Processing) return
        sendCallMessage(normalized)
    }

    private fun sendCallMessage(text: String) {
        val transcript = text.trim()
        vadJob?.cancel()
        if (transcript.isBlank()) {
            startListening()
            return
        }

        if (_uiState.value.status == VoiceCallStatus.Speaking) {
            interruptDetectJob?.cancel()
            tts.stop()
        }

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Processing,
                assistantText = "",
                userTranscript = "",
                errorMessage = null,
            )
        }
        ttsSentLength = 0
        lastMessageSentAt = System.currentTimeMillis()

        // 每轮发送前重新挂好 Conversation / generationDone / error 监听，避免某个 provider
        // 或上一轮打断/取消导致 collector 已退出，出现“第一轮能回、第二轮永久思考”。
        startConversationMonitor()

        try {
            chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(transcript)))
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败, conversationId=$conversationId", e)
            _uiState.update {
                it.copy(status = VoiceCallStatus.Error, errorMessage = "发送失败: ${e.message}")
            }
        }
    }

    private fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = serviceScope.launch {
            conversation.collect { currentConversation ->
                if (_uiState.value.status != VoiceCallStatus.Processing &&
                    _uiState.value.status != VoiceCallStatus.Speaking
                ) return@collect

                val lastMessage = currentConversation.currentMessages.lastOrNull()
                if (lastMessage?.role != MessageRole.ASSISTANT) return@collect

                val currentText = lastMessage.toText()
                _uiState.update { it.copy(assistantText = currentText) }

                if (currentText.length > ttsSentLength) {
                    val newText = currentText.substring(ttsSentLength)
                    extractCompleteSentences(newText).forEach { sentence ->
                        if (sentence.isNotBlank()) {
                            tts.speak(sentence, flushCalled = false)
                            Log.d(TAG, "Streaming TTS: $sentence")
                        }
                    }
                    ttsSentLength = currentText.length - getPendingRemainder(newText).length
                }

                if (_uiState.value.status == VoiceCallStatus.Processing && currentText.isNotBlank()) {
                    _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
                    startInterruptDetection()
                }
            }
        }

        speakingMonitorJob?.cancel()
        speakingMonitorJob = serviceScope.launch {
            chatService.generationDoneFlow.collect { convId ->
                if (convId == conversationId) onGenerationDone()
            }
        }

        chatErrorMonitorJob?.cancel()
        chatErrorMonitorJob = serviceScope.launch {
            chatService.errors.collect { errors ->
                if (_uiState.value.status != VoiceCallStatus.Processing || lastMessageSentAt <= 0L) {
                    return@collect
                }
                val error = errors.lastOrNull {
                    it.conversationId == conversationId && it.timestamp >= lastMessageSentAt
                } ?: return@collect
                Log.e(TAG, "AI 生成失败, conversationId=$conversationId", error.error)
                _uiState.update {
                    it.copy(
                        status = VoiceCallStatus.Error,
                        errorMessage = error.error.message ?: "AI 回复生成失败",
                    )
                }
            }
        }
    }

    private suspend fun onGenerationDone() {
        val finalText = _uiState.value.assistantText
        if (finalText.length > ttsSentLength) {
            val remaining = finalText.substring(ttsSentLength)
            if (remaining.isNotBlank()) {
                tts.speak(remaining, flushCalled = false)
                ttsSentLength = finalText.length
            }
        }

        // 空回复也不要让状态永久卡 Processing。
        if (finalText.isBlank()) {
            startListening()
            return
        }

        _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
        startInterruptDetection()
        waitForTtsToFinish()
        if (_uiState.value.status == VoiceCallStatus.Speaking) startListening()
    }

    private suspend fun waitForTtsToFinish() {
        val waitStart = System.currentTimeMillis()
        while (!tts.isSpeaking.value && System.currentTimeMillis() - waitStart < 5_000L) {
            delay(100)
        }

        val idleTimeoutMs = 5_000L
        val hardDeadlineMs = 300_000L
        val startTime = System.currentTimeMillis()
        var lastActiveTime = startTime
        while (true) {
            val now = System.currentTimeMillis()
            val playbackStatus = tts.playbackState.value.status
            val active = tts.isSpeaking.value ||
                playbackStatus == me.rerere.tts.model.PlaybackStatus.Playing ||
                playbackStatus == me.rerere.tts.model.PlaybackStatus.Buffering
            if (active) lastActiveTime = now
            if (!active && now - lastActiveTime >= idleTimeoutMs) break
            if (now - startTime > hardDeadlineMs) {
                Log.w(TAG, "TTS 播放超过 5 分钟未结束, 强制停止")
                tts.stop()
                break
            }
            delay(300)
        }
        delay(300)
    }

    private fun extractCompleteSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char in charArrayOf('。', '？', '！', '.', '?', '!', '\n')) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(result::add)
                current.clear()
            }
        }
        return result
    }

    private fun getPendingRemainder(text: String): String {
        val lastEnd = text.lastIndexOfAny(charArrayOf('。', '？', '！', '.', '?', '!', '\n'))
        return when {
            lastEnd < 0 -> text
            lastEnd < text.length - 1 -> text.substring(lastEnd + 1)
            else -> ""
        }
    }

    private fun startInterruptDetection() {
        interruptDetectJob?.cancel()
        interruptDetectJob = serviceScope.launch {
            val baselineTranscript = _uiState.value.userTranscript
            while (true) {
                delay(150)
                if (_uiState.value.status != VoiceCallStatus.Speaking) break
                if (isMuted) continue

                val transcript = _uiState.value.userTranscript
                val recentAmplitude = _uiState.value.amplitudes.takeLast(3).average().toFloat()
                val hasNewTranscript = transcript.length > baselineTranscript.length + 1
                val hasLoudVoice = recentAmplitude > 0.15f
                if (hasNewTranscript || hasLoudVoice) {
                    Log.d(TAG, "检测到用户打断: transcript=$transcript, amplitude=$recentAmplitude")
                    interruptSpeaking()
                    break
                }
            }
        }
    }

    fun interruptSpeaking() {
        if (_uiState.value.status != VoiceCallStatus.Speaking) return
        speakingMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        tts.stop()
        startListening()
        // generationDone collector 在打断后重新挂回，避免后续轮次失去完成事件。
        startConversationMonitor()
    }

    private fun startAsrMonitor() {
        asrMonitorJob?.cancel()
        asrMonitorJob = serviceScope.launch {
            var wasRecording = false
            asr.state.collect { state ->
                val isRecording = state.isRecording
                if (wasRecording && !isRecording && !isMuted &&
                    _uiState.value.status == VoiceCallStatus.Listening
                ) {
                    val transcript = state.transcript.trim()
                    if (transcript.isNotEmpty() && _uiState.value.autoSendEnabled) {
                        Log.d(TAG, "ASR provider stopped; auto-send: $transcript")
                        sendCurrentMessage()
                    } else {
                        restartAsrAfterProviderStop()
                    }
                }
                wasRecording = isRecording
            }
        }
    }

    private fun restartAsrAfterProviderStop() {
        if (isMuted || _uiState.value.status != VoiceCallStatus.Listening) return
        runCatching {
            asr.start { transcript -> _uiState.update { it.copy(userTranscript = transcript) } }
        }.onFailure {
            Log.e(TAG, "ASR 自动恢复失败", it)
            _uiState.update { state -> state.copy(errorMessage = "麦克风恢复失败: ${it.message}") }
        }
    }

    fun toggleMute() {
        val targetMuted = !isMuted
        try {
            if (targetMuted) {
                asr.stop()
                isMuted = true
            } else {
                asr.start { transcript -> _uiState.update { it.copy(userTranscript = transcript) } }
                isMuted = false
            }
            _uiState.update { it.copy(isMuted = isMuted) }
        } catch (e: Exception) {
            // 取消静音失败时保持“静音”事实，不让 UI 假装麦克风已经恢复。
            if (!targetMuted) isMuted = true
            Log.e(TAG, "切换静音状态失败, targetMuted=$targetMuted", e)
            _uiState.update {
                it.copy(isMuted = isMuted, errorMessage = "麦克风切换失败: ${e.message}")
            }
        }
    }

    fun toggleAutoSend() {
        _uiState.update { it.copy(autoSendEnabled = !it.autoSendEnabled) }
    }

    /**
     * 停止本场通话。
     *
     * 页面内挂断按钮自己会 onBack，因此默认不在这里同步清空 active state，避免页面 observer
     * 同时再 onBack 一次造成双重导航；通知栏挂断则显式传 clearActiveState=true。
     */
    fun endCall(clearActiveState: Boolean = false) {
        vadJob?.cancel()
        speakingMonitorJob?.cancel()
        conversationMonitorJob?.cancel()
        chatErrorMonitorJob?.cancel()
        asrMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        if (::asr.isInitialized) asr.stop()
        if (::tts.isInitialized) tts.stop()
        restoreAudioRoute()
        releaseConversationReference()
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Idle,
                isMuted = false,
                isSpeakerEnabled = false,
                amplitudes = emptyList(),
            )
        }
        isMuted = false
        if (clearActiveState) clearActiveCallState()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.e(TAG, "stopForeground 失败", it) }
    }

    private fun releaseControllers() {
        if (resourcesReleased) return
        resourcesReleased = true
        if (::asr.isInitialized) runCatching { asr.cleanup() }
            .onFailure { Log.e(TAG, "ASR cleanup 失败", it) }
        if (::tts.isInitialized) runCatching { tts.cleanup() }
            .onFailure { Log.e(TAG, "TTS cleanup 失败", it) }
    }

    fun updateAmplitudes(amplitudes: List<Float>) {
        _uiState.update { it.copy(amplitudes = amplitudes) }
    }

    private fun buildNotification(state: VoiceCallUiState): android.app.Notification {
        val contentText = when (state.status) {
            VoiceCallStatus.Listening -> "正在聆听..."
            VoiceCallStatus.Processing -> "正在思考..."
            VoiceCallStatus.Speaking -> "正在说话..."
            VoiceCallStatus.Error -> state.errorMessage ?: "通话出错"
            VoiceCallStatus.Idle -> "通话中"
        }
        val isVideo = callSurface == VoiceCallSurface.Video
        val returnExtra = if (isVideo) {
            ShortcutHandlerActivity.EXTRA_VIDEO_CALL_CONVERSATION_ID
        } else {
            ShortcutHandlerActivity.EXTRA_VOICE_CALL_CONVERSATION_ID
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            Intent(this, ShortcutHandlerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(returnExtra, conversationId.toString())
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val hangUpIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceCallService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(if (isVideo) "视频通话" else "语音通话")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "挂断", hangUpIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onDestroy() {
        try {
            endCall()
            releaseControllers()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 清理失败", e)
        } finally {
            // UI 挂断路径会在 stopService() 后紧接着 onBack()。把 active state 清理排到下一轮
            // 主线程消息，保证页面自己的返回先完成，避免 observer 再弹一次导航栈。
            Handler(Looper.getMainLooper()).post { clearActiveCallState() }
            serviceScope.cancel()
            super.onDestroy()
        }
    }
}
