package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThought

/** 独立 ChainOfThought 实例，折叠状态不会和原生推理/工具步骤互相影响。 */
@Composable
fun CustomThoughtCard(
    text: String,
    loading: Boolean = false,
    expandedState: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    ChainOfThought(
        steps = listOf(text),
        collapsedAdaptiveWidth = true,
        cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) { thought ->
        ControlledChainOfThoughtStep(
            expanded = expandedState.value,
            onExpandedChange = { expandedState.value = it },
            icon = { Icon(HugeIcons.Idea01, contentDescription = null) },
            label = { Text("自定义思考链", style = MaterialTheme.typography.titleSmall) },
            collapsedAdaptiveWidth = true,
        ) {
            val content = @Composable {
                MarkdownBlock(
                    content = thought,
                    renderCards = false,
                    isStreaming = loading,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            if (loading) content() else SelectionContainer { content() }
        }
    }
}
