package me.rerere.rikkahub.ui.components.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Announcement
import me.rerere.rikkahub.data.service.AnnouncementManager

/**
 * 公告弹窗。
 *
 * 用原生的 AlertDialog 而不是网页或自绘浮层：它跟随系统与主题，
 * 不需要 WebView（省体积也省一次加载），深色模式自动正确，
 * 而且返回键、点击外部这些交互用户已经熟悉。
 *
 * 内容全部来自安装包内的 assets，不联网。
 */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var dontShowAgain by remember { mutableStateOf(false) }

    // 配图：从 assets 解成 Bitmap。
    // 缺图或解码失败时返回 null，界面自动跳过这块，不留空白。
    val imageBitmap = remember(announcement.image) {
        val path = AnnouncementManager.imageAssetPath(announcement) ?: return@remember null
        runCatching {
            context.assets.open(path).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = {
            // 点外部 = 关闭但**不**记「不再提示」。想永久关掉必须显式勾选，
            // 免得用户随手点一下就再也看不到后续公告。
            onDismiss(false)
        },
        title = {
            if (announcement.title.isNotBlank()) {
                Text(announcement.title, style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                if (announcement.body.isNotBlank()) {
                    Text(
                        // 直接用 Text，不解析 Markdown——公告里写 ** 只会
                        // 原样显示星号，正文按段落写就好。
                        text = announcement.body,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss(dontShowAgain) }) { Text("知道了") }
        },
        dismissButton = {
            if (announcement.dismissible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                    )
                    TextButton(onClick = { onDismiss(true) }) {
                        Text(
                            "不再提示",
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
        },
    )
}
