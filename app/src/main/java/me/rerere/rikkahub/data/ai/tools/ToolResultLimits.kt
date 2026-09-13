package me.rerere.rikkahub.data.ai.tools

/**
 * 工具结果进入对话历史后会每轮重复计费，超长输出必须截断。
 * 保留头尾各一半，方便模型同时看到开头结构与最后的结论/报错。
 */
private const val TOOL_RESULT_MAX_CHARS = 20_000

/** file read 单行最大返回字符数（minified JSON/JS、单行 base64 一行可达数 MB） */
const val MAX_READ_LINE_CHARS = 2_000

fun String.truncateForToolResult(maxChars: Int = TOOL_RESULT_MAX_CHARS): String {
    if (length <= maxChars) return this
    val head = maxChars / 2
    val tail = maxChars - head
    return take(head) + "\n...[truncated ${length - maxChars} chars, total $length]...\n" + takeLast(tail)
}
