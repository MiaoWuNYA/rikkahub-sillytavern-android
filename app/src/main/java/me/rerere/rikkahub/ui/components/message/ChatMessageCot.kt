package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.findCustomThought
import me.rerere.rikkahub.data.model.CustomThoughtSettings
import me.rerere.rikkahub.utils.JsonInstant

internal const val CHART_DISPLAY_TOOL_NAME = "chart_display"

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
    // 显示专用，不是 UIMessagePart，更不能持久化为原生 Reasoning。
    data class CustomThoughtBlock(val text: String, val index: Int) : MessagePartBlock

    /** 成功执行的 chart_display 工具调用, 在正文中以图表卡片展示 */
    data class ChartBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 * 成功执行的 chart_display 原地替换为 ChartBlock (会切断所在的 ThinkingBlock);
 * 生成中或失败的调用仍作为普通 ToolStep 展示
 */
fun List<UIMessagePart>.groupMessageParts(
    hideNativeReasoning: Boolean = false,
    customThought: CustomThoughtSettings = CustomThoughtSettings(),
): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()
    var extractedCustomThought = false

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                if (!hideNativeReasoning) currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                if (part.isSuccessfulChartDisplay()) {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ChartBlock(part, index))
                } else {
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                val match = if (part is UIMessagePart.Text && !extractedCustomThought) {
                    findCustomThought(part.text, customThought)
                } else null
                if (match != null && part is UIMessagePart.Text) {
                    extractedCustomThought = true
                    if (match.start > 0) result.add(MessagePartBlock.ContentBlock(
                        part.copy(text = part.text.substring(0, match.start)), -index * 2 - 1,
                    ))
                    result.add(MessagePartBlock.CustomThoughtBlock(match.content, index))
                    if (match.endExclusive < part.text.length) result.add(MessagePartBlock.ContentBlock(
                        part.copy(text = part.text.substring(match.endExclusive)), -index * 2 - 2,
                    ))
                } else result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

private fun UIMessagePart.Tool.isSuccessfulChartDisplay(): Boolean {
    if (toolName != CHART_DISPLAY_TOOL_NAME || !isExecuted) return false
    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val result = runCatching { JsonInstant.parseToJsonElement(outputText) }.getOrNull() as? JsonObject
    return (result?.get("success") as? JsonPrimitive)?.booleanOrNull == true
}
