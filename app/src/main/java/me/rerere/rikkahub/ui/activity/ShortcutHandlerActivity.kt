package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.voice.VideoCallPage
import me.rerere.rikkahub.ui.pages.voice.VoiceCallPage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.android.ext.android.inject
import java.io.File
import kotlin.uuid.Uuid

/**
 * 原有桌面相机快捷方式宿主，同时承担通话通知的轻量返回入口。
 *
 * 通知返回不依赖 RouteActivity.onNewIntent：即使主 Activity 已被系统回收，只要前台通话
 * Service 仍在，点通知也能直接重新绑定到正确的语音/视频页面。
 */
class ShortcutHandlerActivity : ComponentActivity() {
    private val settingsStore by inject<SettingsStore>()
    private var photoURI: Uri? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            finish()
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            photoURI?.let {
                val intent = Intent(this, RouteActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, it.toString())
                }
                startActivity(intent)
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoConversationId = intent.getStringExtra(EXTRA_VIDEO_CALL_CONVERSATION_ID)
        val voiceConversationId = intent.getStringExtra(EXTRA_VOICE_CALL_CONVERSATION_ID)
        val callConversationId = videoConversationId ?: voiceConversationId
        if (callConversationId != null) {
            showActiveCall(
                conversationId = callConversationId,
                video = videoConversationId != null,
            )
            return
        }

        // 原有桌面相机快捷方式行为保持不变。
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showActiveCall(conversationId: String, video: Boolean) {
        val id = runCatching { Uuid.parse(conversationId) }.getOrNull()
        if (id == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
            RikkahubTheme {
                CompositionLocalProvider(
                    LocalSettings provides settings,
                ) {
                    if (video) {
                        VideoCallPage(conversationId = id, onBack = { finish() })
                    } else {
                        VoiceCallPage(conversationId = id, onBack = { finish() })
                    }
                }
            }
        }
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "shortcut_camera_image.jpg")
        photoURI = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", imageFile)
        photoURI?.let {
            takePictureLauncher.launch(it)
        } ?: finish()
    }

    companion object {
        const val EXTRA_VOICE_CALL_CONVERSATION_ID = "openVoiceCallConversationId"
        const val EXTRA_VIDEO_CALL_CONVERSATION_ID = "openVideoCallConversationId"
    }
}
