package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 主题图标：优先使用酒馆主题里为该按钮指定的图片，否则回退到内置矢量图标。
 *
 * 官方把发送栏按钮做成带 FontAwesome 字体的 div（如 `#send_but`），主题因此可以
 * 直接改写它们。实测 553 个主题里 452 个改过发送栏，其中 210 个用
 * `background-image: url(...)` 换了图、80 个改了 `color`、127 个改了字号。
 *
 * 这里按同样的语义落版：
 * - 有图片 → 渲染图片（保留主题给的颜色作 tint 不起作用时忽略）；
 * - 有颜色 → 覆盖内置图标的 tint；
 * - 有字号 → 换算成图标尺寸缩放；
 * - 主题若写了 `display:none`，由调用方隐藏该按钮（本组件不处理隐藏）。
 */
@Composable
fun ThemedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    tint: Color? = null,
    scale: Float? = null,
    baseSize: Dp = 20.dp,
) {
    val size = scale?.let { baseSize * it } ?: baseSize
    val iconTint = tint ?: LocalContentColor.current

    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * 头像框：主题常把一圈装饰图叠在头像上（官方写法是 `.avatar::before`，
 * 尺寸往往比头像本身还大，如 112px 框套 50px 头像）。
 *
 * 这里用「放大 + 居中叠加」还原该效果，而不是替换头像本身。
 * 返回 null 表示该侧没有主题头像框。
 */
@Composable
fun ThemedAvatarFrame(
    frameUrl: String?,
    avatarSize: Dp,
    scale: Float? = null,
    modifier: Modifier = Modifier,
) {
    if (frameUrl.isNullOrBlank()) return
    val frameSize = avatarSize * (scale ?: 1.6f)
    AsyncImage(
        model = frameUrl,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(frameSize),
    )
}
