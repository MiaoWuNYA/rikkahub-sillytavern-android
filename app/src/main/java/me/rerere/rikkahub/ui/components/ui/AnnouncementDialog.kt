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
import coil3.compose.AsyncImage

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

    // 配图分两种来源：
    //   · 内置公告的图放在 assets 里
    //   · 云端公告的图是一个 http 地址
    //
    // 网络图交给 Coil，不自己 openStream。
    //
    // 这里踩过一个坑：原来用 `java.net.URL(url).openStream()` 手写下载，
    // 它**没有任何超时**——图床本身 36KB / 0.2 秒就返回了，但连接一卡
    // 就是无限等，用户看到的是弹窗里一块空白晾五六秒。
    // 而且没有任何缓存，同一条公告每次点进设置都重新下一遍。
    //
    // Coil 自带磁盘与内存缓存、连接超时、以及请求取消，
    // 项目本来就在用它，没有理由为一张公告图另造一套。
    val assetPath = remember(announcement.image) {
        AnnouncementManager.imageAssetPath(announcement)
    }
    val remoteUrl = remember(announcement.image) {
        AnnouncementManager.remoteImageUrl(announcement)
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
                when {
                    // 内置图：直接从 assets 解，本来就是本地文件，秒出
                    assetPath != null -> {
                        val bmp = remember(assetPath) {
                            runCatching {
                                context.assets.open(assetPath).use { stream ->
                                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }

                    // 云端图：交给 Coil，走它自己的缓存与超时
                    remoteUrl != null -> AsyncImage(
                        model = remoteUrl,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    )

                    else -> Unit
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
            // 只有一个按钮：知道了。勾了「不再提示」就永久关掉，没勾就下次还弹。
            //
            // 这里前后改过两版：先是「不再提示按钮 + 勾选框」并存，
            // 两者做同一件事、用户不知道该点哪个；然后是「勾选框 + 稍后」，
            // 但「知道了」和「稍后」并列时语义是重叠的——都是关闭，
            // 区别只在下次弹不弹，而那个区别已经由勾选框表达清楚了。
            //
            // 一个弹窗只需要一个决定性的按钮。
            Button(onClick = { onDismiss(dontShowAgain) }) {
                Text("知道了")
            }
        },
        dismissButton = {
            if (announcement.dismissible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                    )
                    Text(
                        "不再提示",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}
