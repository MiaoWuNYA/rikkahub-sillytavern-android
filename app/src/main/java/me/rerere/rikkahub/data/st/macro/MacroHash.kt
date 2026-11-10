package me.rerere.rikkahub.data.st.macro

/**
 * ST 字符串哈希 —— 从 WorldInfoEngine 解耦的本地移植（cyrb53，63-bit）。
 *
 * 移植自 SillyTavern 1.18.0 `public/scripts/utils.js` 的 `getStringHash`。
 * 供 `{{pick}}` 种子链路（内容哈希 / 会话哈希）使用，保证与 ST 的确定性一致。
 *
 * 注意：JS 使用 UTF-16 code unit，Kotlin Char 同为 UTF-16，逐位行为一致。
 */
internal object MacroHash {
    /**
     * 63-bit 整数哈希（等价 ST cyrb53 变体）。
     * 黄金向量：getStringHash("hello world") == 3259054761512980L（见单元测试）。
     */
    fun getStringHash(str: String, seed: Int = 0): Long {
        var h1: Int = -0x21524111 xor seed // 0xdeadbeef
        var h2: Int = 0x41c6ce57 xor seed
        for (element in str) {
            val ch = element.code
            h1 = imul32(h1 xor ch, C2654435761)
            h2 = imul32(h2 xor ch, C1597334677)
        }
        val h1New = imul32(h1 xor (h1 ushr 16), C2246822507) xor
            imul32(h2 xor (h2 ushr 13), C3266489909)
        val h2New = imul32(h2 xor (h2 ushr 16), C2246822507) xor
            imul32(h1New xor (h1New ushr 13), C3266489909)

        return 4294967296L * (2097151L and h2New.toLong()) + (h1New.toLong() and 0xFFFFFFFFL)
    }

    /** 32 位有符号乘法（对齐 JS Math.imul） */
    private fun imul32(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

    // 大质数乘法常量（换算为 Int 二补码表示）
    private const val C2654435761 = -1640531535 // = 2654435761 - 2^32
    private const val C1597334677 = 1597334677
    private const val C2246822507 = -2048144789 // = 2246822507 - 2^32
    private const val C3266489909 = -1028477387 // = 3266489909 - 2^32
}
