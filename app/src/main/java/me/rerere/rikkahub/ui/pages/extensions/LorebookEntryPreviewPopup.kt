package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import me.rerere.rikkahub.data.model.PromptInjection

/** A separate window changes neither the fixed safety area nor the list's viewport. */
@Composable
internal fun LorebookEntryPreviewPopup(entry: PromptInjection.RegexInjection, onDismiss: () -> Unit) {
    LorebookReadOnlyPopup("条目预览", "lorebook-entry-preview", "关闭预览", onDismiss) {
        Text(entry.name.ifBlank { "未命名条目" }, style = MaterialTheme.typography.titleMedium)
        Text(entry.content.ifEmpty { "（正文为空）" }, Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LorebookReadOnlyPopup(
    title: String, tag: String, closeLabel: String, onDismiss: () -> Unit, content: @Composable () -> Unit,
) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = anchorBounds.center.x - popupContentSize.width / 2
                val above = anchorBounds.top - popupContentSize.height - margin
                val y = if (above >= margin) above else anchorBounds.bottom + margin
                return IntOffset(
                    x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
                )
            }
        }
    }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(Modifier.padding(8.dp).widthIn(max = 360.dp).fillMaxWidth()
            .heightIn(max = 320.dp).testTag(tag),
            shape = MaterialTheme.shapes.large, shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onDismiss) { Text(closeLabel) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }
}
