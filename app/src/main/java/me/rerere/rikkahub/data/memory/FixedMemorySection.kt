package me.rerere.rikkahub.data.memory

private const val FIXED_MEMORY_BEGIN = "<!-- RIKKAHUB_FIXED_MEMORY_BEGIN -->"
private const val FIXED_MEMORY_END = "<!-- RIKKAHUB_FIXED_MEMORY_END -->"

/**
 * 固定记忆区：以 HTML 注释标记嵌入 assistant.systemPrompt。
 * UI 编辑固定记忆时只替换标记区内容，不覆盖角色卡其余部分。
 */
fun String.extractFixedMemory(): String {
    val start = indexOf(FIXED_MEMORY_BEGIN)
    val end = indexOf(FIXED_MEMORY_END)
    if (start < 0 || end < 0 || end <= start) return ""
    return substring(start + FIXED_MEMORY_BEGIN.length, end).trim()
}

fun String.withFixedMemory(memory: String): String {
    val clean = memory.trim()
    val start = indexOf(FIXED_MEMORY_BEGIN)
    val end = indexOf(FIXED_MEMORY_END)
    val base = if (start >= 0 && end > start) {
        removeRange(start, end + FIXED_MEMORY_END.length).trimEnd()
    } else {
        trimEnd()
    }
    if (clean.isBlank()) return base
    val block = buildString {
        appendLine(FIXED_MEMORY_BEGIN)
        appendLine("## Fixed memory")
        appendLine("Treat the following as stable user-approved memory. Keep it consistent unless the user explicitly changes it.")
        appendLine(clean)
        append(FIXED_MEMORY_END)
    }
    return if (base.isBlank()) block else "$base\n\n$block"
}
