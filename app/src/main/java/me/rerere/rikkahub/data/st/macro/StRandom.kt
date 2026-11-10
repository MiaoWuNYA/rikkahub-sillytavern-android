package me.rerere.rikkahub.data.st.macro

import kotlin.math.floor

/**
 * ST 随机支持库 —— Kotlin 原生移植。
 *
 * - [StSeedrandom]：移植自 seedrandom 3.0.5（(c) 2019 David Bau，MIT 许可）。
 *   该移植是 bit-exact 的：相同种子字符串 → 与 JS 版完全一致的 [0,1) 输出序列。
 *   （黄金向量由 Node 运行原版库生成，在单元测试中逐位校验。）
 * - [StDroll]：移植自 droll 0.2.1（(c) Ethan Zimmerman，MIT 许可）。
 *
 * 说明：ST 的 `{{random}}` 使用 entropy（非确定性），不经本库；
 * 只有 `{{pick}}` 使用确定性 ARC4 序列。
 */
internal object StSeedrandom {

    // 与 seedrandom.js 对齐的 IEEE 754 相关常量
    private const val WIDTH = 256          // 每个 RC4 输出 0 <= x < 256
    private const val CHUNKS = 6           // 每个 double 至少 6 个 RC4 输出
    private const val DIGITS = 52          // double 有 52 位有效数字
    private const val MASK = WIDTH - 1
    private val STARTDENOM: Double = Math.pow(WIDTH.toDouble(), CHUNKS.toDouble())
    private val SIGNIFICANCE: Double = Math.pow(2.0, DIGITS.toDouble())
    private val OVERFLOW: Double = SIGNIFICANCE * 2.0

    /** 对齐 `seedrandom(seed)`（确定性字符串种子模式）：返回 [0,1) 双精度序列 */
    fun create(seed: String): () -> Double {
        val arc4 = Arc4(mixkey(seed))
        return { nextDouble(arc4) }
    }

    /**
     * mixkey —— 对齐 seedrandom mixkey()。
     *
     * 返回 “JS 数组语义” 的 key：长度为 min(256, 字符串长度)（空串 → 长度 0，
     * 由 ARC4 构造器按 [0] 处理）。注意 JS 中未赋值的下标读取为 undefined，
     * `undefined * 19 → NaN → ToInt32(NaN)=0`，与读取 0 完全等价。
     */
    private fun mixkey(stringseed: String): IntArray {
        val len = minOf(WIDTH, stringseed.length)
        val key = IntArray(len)
        var smear = 0
        var j = 0
        while (j < stringseed.length) {
            val k = MASK and j
            // JS: smear ^= key[k] * 19   （Int 自然回绕 = ToInt32 语义）
            smear = smear xor (key[k] * 19)
            key[k] = MASK and (smear + stringseed[j].code)
            j++
        }
        return key
    }

    /** ARC4（对齐 seedrandom ARC4：KSA + g(count) 拼接输出；构造时丢弃 256 个输出） */
    private class Arc4(key: IntArray) {
        val s = IntArray(WIDTH)
        var i = 0
        var j = 0

        init {
            // 空 key 视为 [0]
            val k = if (key.isEmpty()) intArrayOf(0) else key
            for (idx in 0 until WIDTH) s[idx] = idx
            var jj = 0
            for (ii in 0 until WIDTH) {
                val t = s[ii]
                jj = MASK and (jj + k[ii % k.size] + t)
                s[ii] = s[jj]
                s[jj] = t
            }
            // 对齐 `(me.g = function...)(width)`：RC4-drop[256]
            g(WIDTH)
        }

        /**
         * 对齐 ARC4.g(count)：把接下来的 count 个字节拼成一个数（< 256^count，Double 精确表示）。
         *
         * JS 原文：
         * ```
         * t = s[i = mask & (i + 1)];
         * r = r * width + s[mask & ((s[i] = s[j = mask & (j + t)]) + (s[j] = t))];
         * ```
         */
        fun g(count: Int): Double {
            var r = 0.0
            var ii = i
            var jj = j
            var remaining = count
            while (remaining-- > 0) {
                ii = MASK and (ii + 1)
                val t = s[ii]
                jj = MASK and (jj + t)
                val moved = s[jj]
                s[ii] = moved
                s[jj] = t
                r = r * WIDTH + s[MASK and (moved + t)]
            }
            i = ii
            j = jj
            return r
        }
    }

    /** 对齐 seedrandom 的 prng()：由 6 字节块构造 [0,1) 的双精度随机数 */
    private fun nextDouble(arc4: Arc4): Double {
        var n = arc4.g(CHUNKS)
        var d = STARTDENOM
        var x = 0.0
        while (n < SIGNIFICANCE) {
            n = (n + x) * WIDTH
            d *= WIDTH
            x = arc4.g(1)
        }
        while (n >= OVERFLOW) {
            n /= 2
            d /= 2
            x = floor(x / 2)
        }
        return (n + x) / d
    }
}

/** droll 0.2.1 移植（骰子公式解析与投掷） */
internal object StDroll {

    private val FORMULA_REGEX = Regex("^([1-9]\\d*)?d([1-9]\\d*)([+-]\\d+)?$", RegexOption.IGNORE_CASE)

    class Formula(val numDice: Int, val numSides: Int, val modifier: Int)

    /**
     * 对齐 droll.parse：`/^([1-9]\d*)?d([1-9]\d*)([+-]\d+)?$/i`。
     * 解析失败（或数字超出 Int 范围）返回 null。
     */
    fun parse(formula: String): Formula? {
        val m = FORMULA_REGEX.matchEntire(formula) ?: return null
        val dicePart = m.groupValues[1]
        val sidesPart = m.groupValues[2]
        val modPart = m.groupValues[3]
        val numDice = if (dicePart.isEmpty()) 1 else (dicePart.toIntOrNull() ?: return null)
        val numSides = sidesPart.toIntOrNull() ?: return null
        val modifier = if (modPart.isEmpty()) 0 else (modPart.toIntOrNull() ?: return null)
        return Formula(numDice, numSides, modifier)
    }

    /** 对齐 droll.validate */
    fun validate(formula: String): Boolean = parse(formula) != null

    /**
     * 对齐 droll.roll：逐骰 `1 + floor(rand * numSides)`，总和 + 修正值。
     * 返回总点数；公式非法返回 null。
     */
    fun roll(formula: String, random: kotlin.random.Random = kotlin.random.Random.Default): Int? {
        val f = parse(formula) ?: return null
        var total = f.modifier
        repeat(f.numDice) {
            total += 1 + floor(random.nextDouble() * f.numSides).toInt()
        }
        return total
    }
}
