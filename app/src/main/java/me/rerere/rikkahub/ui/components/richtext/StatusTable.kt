package me.rerere.rikkahub.ui.components.richtext

/**
 * 状态栏文本 → GFM 表格。
 *
 * 大量卡片的输出状态栏是「emoji 开头的字段行」堆出来的：
 *
 *     🎭身份: 转移事件幸存者
 *     📜冒险者等级: 未注册
 *     💪🏼身体状况: 55%｜40%｜左臂有未愈合的撕裂伤
 *     🌀玩家能力:
 *     ┣ 剑术: 初级 exp:0/100
 *     ┗ 特殊能力: 【无】
 *
 * 这在酒馆里也是普通文本，视觉上靠 emoji 撑排版。放进窄屏聊天列表
 * 就是一坨挤在一起的行，长值还会和下一行混在一起读。
 *
 * 把连续的状态行转成两列 GFM 表格（字段 | 值），两个渲染器
 * （自研 AST 路径与 MarkdownNew 的 HTML 路径）都能正确渲染表格。
 *
 * **触发条件刻意苛刻**，避免把普通聊天文本误转成表格：
 *   · 每行行首必须是「装饰字符」——emoji / 变体选择符 / 制表符号（┣┃┗）
 *   · 必须有 `字段: 值` 结构（全半角冒号都认）
 *   · **连续 ≥3 行**才成块；不满足则原样保留
 */
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

/** 一行状态字段：(字段（含 emoji），值)；不是状态行返回 null。 */
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
            out.append("| 字段 | 值 |\n| --- | --- |\n")
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
