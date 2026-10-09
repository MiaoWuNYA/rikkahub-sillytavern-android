package me.rerere.rikkahub.ui.components.richtext

// 状态栏 emoji 字段行（连续 3 行以上）转 GFM 两列表格；条件苛刻防误伤
private fun statusDecor(ch: Char, next: Char?): Boolean {
    val code = ch.code
    if (code in 0x2500..0x257F) return true   // ┣ ┃ ┗ 等制表符号
    if (code == 0xFE0F) return true           // 变体选择符（🛡️ 的 ️）
    if (code in 0x2600..0x27BF) return true   // ☀ ✈ 等杂项符号
    if (code in 0x2B00..0x2BFF) return true   // ⭐ 等
    if (code in 0x2190..0x21FF) return true   // ← ↑ 箭头区（部分状态行用）
    if (Character.isHighSurrogate(ch) && next != null) {
        val cp = Character.toCodePoint(ch, next)
        if (cp in 0x1F000..0x1FAFF) return true // 🎭 📜 等主要 emoji 区
    }
    return false
}

private fun parseStatusLine(line: String): Pair<String, String>? {
    val t = line.trimStart().trimEnd('\r')
    if (t.isEmpty()) return null
    if (!statusDecor(t.first(), t.getOrNull(1))) return null
    val colon = t.indexOfFirst { it == ':' || it == '：' }
    if (colon <= 0) return null
    val field = t.substring(0, colon)
        // 制表符只是树形装饰，字段本身不需要它
        .filter { it.code !in 0x2500..0x257F }
        .trim()
    if (field.isEmpty()) return null
    val value = t.substring(colon + 1).trim()
    // GFM 单元格里的竖线要转义，否则表格被拆列
    return field.replace("|", "\\|") to value.replace("|", "\\|")
}

internal fun convertStatusBlocksToTables(content: String): String {
    // 快速路径：没有装饰字符就没有状态栏，直接返回
    if (content.none { ch ->
            ch.code in 0x2500..0x257F || ch.code in 0x2600..0x27BF ||
                ch.code in 0x2B00..0x2BFF || Character.isHighSurrogate(ch)
        }
    ) return content

    val lines = content.split('\n')
    val out = StringBuilder(content.length + 256)
    var block = ArrayList<Pair<String, String>>(16)
    var blockRaw = ArrayList<String>(16)

    fun flush() {
        if (block.size >= 3) {
            // GFM 表格前面必须是空行，否则整段不会被解析成表格
            out.append("\n| 字段 | 值 |\n| --- | --- |\n")
            block.forEach { (f, v) ->
                out.append("| ").append(f).append(" | ").append(v.ifEmpty { " " }).append(" |\n")
            }
            out.append('\n')
        } else {
            blockRaw.forEach { out.append(it).append('\n') }
        }
        block.clear(); blockRaw.clear()
    }

    for (line in lines) {
        val parsed = parseStatusLine(line)
        if (parsed != null) {
            block.add(parsed)
            blockRaw.add(line.trimEnd('\r'))
        } else {
            flush()
            out.append(line).append('\n')
        }
    }
    flush()

    // 原文末尾有没有换行保持一致
    val result = out.toString()
    return if (content.endsWith("\n")) result else result.trimEnd('\n')
}

// 行首 >>>（3 个以上）是卡的引导序列，转义防止被 markdown 吃成嵌套引用
internal fun escapeGuideSequence(content: String): String {
    if (">>>" !in content) return content
    return content.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (!trimmed.startsWith(">>>")) {
            line
        } else {
            val lead = line.take(line.length - trimmed.length)
            lead + "\\>>> " + trimmed.drop(3).removePrefix(" ")
        }
    }
}
