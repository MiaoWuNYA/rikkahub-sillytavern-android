package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.plugin.model.PluginMessageAction

/** 插件声明的对话末尾按钮 */
@Composable
fun PluginMessageActionRow(
    actions: List<PluginMessageAction>,
    onAction: (PluginMessageAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            AssistChip(
                onClick = { onAction(action) },
                label = {
                    Text(
                        text = if (action.icon.isBlank()) action.label else "${action.icon} ${action.label}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}
