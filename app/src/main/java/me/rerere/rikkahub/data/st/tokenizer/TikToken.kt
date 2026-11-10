package me.rerere.rikkahub.data.st.tokenizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.Base64
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * tokenizer 模式（设置项）：cl100k / o200k / 关闭。
 *
 * 默认 [CL100K]（GPT-4/3.5 词表，与 ST 本地 tokenizer 的默认方案一致）；
 * [OFF] 时完全使用既有启发式估算（中日韩 1 字 ≈ 1 token，其余 4 字符 ≈ 1 token）。
 */
@Serializable
enum class TokenizerMode {
    @SerialName("off")
    OFF,

    @SerialName("cl100k")
    CL100K,

    @SerialName("o200k")
    O200K;

    val encoding: TikToken.Encoding?
        get() = when (this) {
            OFF -> null
            CL100K -> TikToken.Encoding.CL100K_BASE
            O200K -> TikToken.Encoding.O200K_BASE
        }

    companion object {
        /** 宽松解析（未知值默认 cl100k，保证旧数据/坏数据可读） */
        fun parse(raw: String?): TokenizerMode = when (raw?.trim()?.lowercase()) {
            "off", "none", "false", "0" -> OFF
            "o200k", "o200k_base" -> O200K
            else -> CL100K
        }
    }
}

/**
 * 真 tiktoken BPE（Kotlin 原生实现）—— [v240 W5]。
 *
 * 词表：随包的 OpenAI 官方 `.tiktoken` 文件
 * （`app/src/main/assets/st-runtime/vendor/data/{cl100k_base,o200k_base}.tiktoken`，
 * 每行 `base64(token) + 空格 + rank`）。
 * 正则：逐字取自官方 tiktoken `tiktoken_ext/openai_public.py` 的 `pat_str`
 * （cl100k_base / o200k_base；含 possessive quantifier 与 `(?i:)` 内联 flag）。
 * 算法：官方 `byte_pair_merge`（core.py）——每个正则 chunk → UTF-8 bytes →
 * 反复合并「rank 最小、并列最左」的相邻段，段数即 token 数。
 *
 * 设计约束（对齐 W5 验收）：
 * - **词表只加载一次**：按 encoding 缓存在 [states]（单例 + 双检锁），后续命中内存；
 * - **必须可降级**：loader 未挂载 / 资源缺失 / 词表不完整（256 单字节 token 缺失）
 *   一律返回 null，调用方回退启发式估算，绝不阻断生成；
 * - 核心层不依赖 Android：App 侧通过 [attach] 注入 assets 读取器（见 RikkaHubApp）。
 */
/**
 * 统一 token 计数入口（[v240 W5]）：
 * - 真 tokenizer 可用（词表已加载）→ 返回真值；
 * - 关闭 / 词表缺失 / 加载失败 → 回退启发式估算 [estimateTokensHeuristic]，绝不阻断生成。
 */
fun countTokens(text: String, mode: TokenizerMode): Int {
    val encoding = mode.encoding ?: return estimateTokensHeuristic(text)
    return TikToken.count(text, encoding) ?: estimateTokensHeuristic(text)
}

/** 既有启发式估算（W5 之前的唯一实现，保留为降级路径）：中日韩 1 字 ≈ 1 token，其余 4 字符 ≈ 1 token */
fun estimateTokensHeuristic(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF) {
            cjk++
        } else {
            other++
        }
    }
    return cjk + (other + 3) / 4
}

object TikToken {

    /** 官方编码（pat_str 为 openai_public.py 原文，逐字对齐） */
    enum class Encoding(
        val id: String,
        val assetPath: String,
        private val patternString: String,
    ) {
        CL100K_BASE(
            id = "cl100k_base",
            assetPath = "st-runtime/vendor/data/cl100k_base.tiktoken",
            patternString = "'(?i:[sdmt]|ll|ve|re)|[^\\r\\n\\p{L}\\p{N}]?+\\p{L}++|\\p{N}{1,3}+| ?[^\\s\\p{L}\\p{N}]++[\\r\\n]*+|\\s++$|\\s*[\\r\\n]|\\s+(?!\\S)|\\s",
        ),
        O200K_BASE(
            id = "o200k_base",
            assetPath = "st-runtime/vendor/data/o200k_base.tiktoken",
            patternString = listOf(
                "[^\\r\\n\\p{L}\\p{N}]?[\\p{Lu}\\p{Lt}\\p{Lm}\\p{Lo}\\p{M}]*[\\p{Ll}\\p{Lm}\\p{Lo}\\p{M}]+(?i:'s|'t|'re|'ve|'m|'ll|'d)?",
                "[^\\r\\n\\p{L}\\p{N}]?[\\p{Lu}\\p{Lt}\\p{Lm}\\p{Lo}\\p{M}]+[\\p{Ll}\\p{Lm}\\p{Lo}\\p{M}]*(?i:'s|'t|'re|'ve|'m|'ll|'d)?",
                "\\p{N}{1,3}",
                " ?[^\\s\\p{L}\\p{N}]+[\\r\\n/]*",
                "\\s*[\\r\\n]+",
                "\\s+(?!\\S)",
                "\\s+",
            ).joinToString("|"),
        );

        /**
         * tiktoken（Rust regex）的 `\s` 是 Unicode White_Space，Java 默认 `\s` 只有 ASCII，
         * 不处理会把 NBSP / 全角空格等切错（W5 调研结论 7）。
         *
         * [v243 BUGFIX E1] **Android 的 java.util.regex 是 ICU 实现，不支持
         * `Pattern.UNICODE_CHARACTER_CLASS`** —— 真机直接抛
         * `UnsupportedOperationException: UNICODE_CHARACTER_CLASS flag not supported`，
         * 于是 tokenizer 整条链路（上下文预算 / 裁剪）在第一次用到时就废掉。
         *
         * 修法：**不用那个 flag，改成把 `\s` / `\S` 在 pattern 字符串里显式展开成
         * Unicode White_Space 的字符类**（`\p{L}` / `\p{N}` / `\p{Lu}` 这类属性转义
         * Android 是支持的，不需要动）。
         *
         * 注意 `\s` 也出现在字符类内部（`[^\s\p{L}\p{N}]`）：Java 的字符类支持嵌套联合
         * （`[^[WS]\p{L}\p{N}]` == `[^WS\p{L}\p{N}]`），所以同一个替换对两种位置都成立。
         */
        val pattern: Pattern by lazy {
            Pattern.compile(
                patternString
                    .replace("\\S", "[^" + WS_BODY + "]")
                    .replace("\\s", "[" + WS_BODY + "]")
            )
        }

        /**
         * Unicode White_Space（tiktoken `\s` 的语义）的字符类**内容**。
         * = U+0009..U+000D / U+0020 / U+0085 / U+00A0 / U+1680 / U+2000..U+200A /
         *   U+2028 / U+2029 / U+202F / U+205F / U+3000 / U+FEFF
         * （Java 默认 `\s` 只有 [ \t\n\x0B\f\r]，会漏掉 NBSP/窄不换行空格/全角空格等）
         */
        private val WS_BODY =
            "\\t\\n\\x0B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    }

    private class State {
        @Volatile
        var ranks: Map<String, Int>? = null

        @Volatile
        var failure: Throwable? = null

        val lock = Any()
    }

    /** chunk 级计数缓存（LRU 语义：超限整体清空重建，防止内存膨胀） */
    private const val CHUNK_CACHE_MAX = 8192

    @Volatile
    private var loader: ((String) -> InputStream?)? = null

    private val states = ConcurrentHashMap<Encoding, State>()
    private val chunkCache = ConcurrentHashMap<String, Int>()

    /**
     * App 侧注入 assets 读取器（核心层不持有 Context；测试注入 File::inputStream 即可）。
     *
     * 换 loader 时清掉历史失败记录，允许重试：
     * 单测/宿主可能先在没有 loader 的情况下调用过 [count]（失败被缓存），
     * 之后才挂载 assets —— 不清失败的话会永久降级成启发式。
     */
    fun attach(loader: ((String) -> InputStream?)?) {
        this.loader = loader
        states.values.forEach { it.failure = null }
    }

    /** 词表是否已成功加载（测试/诊断用） */
    fun isLoaded(encoding: Encoding): Boolean = states[encoding]?.ranks != null

    /** 已加载词表条目数（测试用）；未加载返回 -1 */
    fun vocabSize(encoding: Encoding): Int = states[encoding]?.ranks?.size ?: -1

    /** 最近一次词表加载失败原因（诊断/测试用）；未失败返回 null */
    fun lastFailure(encoding: Encoding): Throwable? = states[encoding]?.failure

    /** 清空全部缓存（测试用） */
    fun resetForTest() {
        states.clear()
        chunkCache.clear()
    }

    /**
     * 真分词计数。
     * @return token 数；词表不可用（未挂载/加载失败）返回 null → 调用方回退启发式
     */
    fun count(text: String, encoding: Encoding): Int? {
        if (text.isEmpty()) return 0
        val ranks = ranksOf(encoding) ?: return null
        val matcher = encoding.pattern.matcher(text)
        var total = 0
        while (matcher.find()) {
            val chunk = matcher.group()
            if (chunk.isEmpty()) continue
            total += countChunk(chunk, encoding, ranks)
        }
        return total
    }

    // ==================== 词表加载（只加载一次） ====================

    private fun ranksOf(encoding: Encoding): Map<String, Int>? {
        val state = states.computeIfAbsent(encoding) { State() }
        state.ranks?.let { return it }
        if (state.failure != null) return null
        synchronized(state.lock) {
            state.ranks?.let { return it }
            if (state.failure != null) return null
            return try {
                val ranks = loadRanks(encoding)
                state.ranks = ranks
                ranks
            } catch (e: Throwable) {
                state.failure = e
                null
            }
        }
    }

    private fun loadRanks(encoding: Encoding): Map<String, Int> {
        val opener = loader ?: error("tokenizer vocabulary loader is not attached")
        val input = opener(encoding.assetPath) ?: error("missing vocabulary: ${encoding.assetPath}")
        val ranks = HashMap<String, Int>(capacityFor(encoding))
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val sep = line.lastIndexOf(' ')
                if (sep <= 0) continue
                val base64 = line.substring(0, sep).trim()
                val rank = line.substring(sep + 1).trim().toIntOrNull() ?: continue
                val bytes = try {
                    Base64.getDecoder().decode(base64)
                } catch (_: Exception) {
                    continue
                }
                if (bytes.isEmpty()) continue
                ranks[String(bytes, Charsets.ISO_8859_1)] = rank
            }
        }
        // 完整性护栏：256 个单字节 token 必须齐全（官方两套词表都满足），
        // 否则视为坏词表 → 回退启发式，绝不产出错误计数。
        for (b in 0..255) {
            val single = String(byteArrayOf(b.toByte()), Charsets.ISO_8859_1)
            if (!ranks.containsKey(single)) {
                error("incomplete vocabulary ${encoding.id}: single byte $b missing")
            }
        }
        return ranks
    }

    private fun capacityFor(encoding: Encoding): Int = when (encoding) {
        Encoding.CL100K_BASE -> 1 shl 18 // 100256 行
        Encoding.O200K_BASE -> 1 shl 19 // 199998 行
    }

    // ==================== 分词 ====================

    private fun countChunk(chunk: String, encoding: Encoding, ranks: Map<String, Int>): Int {
        val key = encoding.id + '\u0000' + chunk
        chunkCache[key]?.let { return it }
        val count = bpeMergeCount(chunk.toByteArray(Charsets.UTF_8), ranks)
        if (chunkCache.size >= CHUNK_CACHE_MAX) chunkCache.clear()
        chunkCache[key] = count
        return count
    }

    /**
     * 官方 `byte_pair_merge` 的堆优化实现（结果与朴素「逐轮找全局最小 rank 对」等价）：
     * 用双向链表维护相邻段，用优先队列按 (rank, 下标) 取最小对；弹出时惰性校验有效性。
     * 并列 rank 时取最左（tiktoken 的严格小于比较语义）。
     */
    private fun bpeMergeCount(bytes: ByteArray, ranks: Map<String, Int>): Int {
        val n = bytes.size
        if (n == 0) return 0
        if (n == 1) return 1

        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { it + 1 }
        next[n - 1] = -1
        val alive = BooleanArray(n) { true }
        val startPos = IntArray(n) { it }
        val endPos = IntArray(n) { it + 1 }
        val partCache = arrayOfNulls<String>(n)

        fun part(index: Int): String {
            partCache[index]?.let { return it }
            val value = String(bytes, startPos[index], endPos[index] - startPos[index], Charsets.ISO_8859_1)
            partCache[index] = value
            return value
        }

        fun rankOf(left: Int): Int {
            val right = next[left]
            if (right < 0) return -1
            return ranks[part(left) + part(right)] ?: -1
        }

        val heap = PriorityQueue<IntArray>(Comparator { a, b ->
            if (a[0] != b[0]) a[0] - b[0] else a[1] - b[1]
        })
        for (i in 0 until n - 1) {
            val rank = rankOf(i)
            if (rank >= 0) heap.add(intArrayOf(rank, i))
        }

        var count = n
        while (heap.isNotEmpty()) {
            val candidate = heap.poll()
            val left = candidate[1]
            if (!alive[left]) continue
            val right = next[left]
            if (right < 0) continue
            if (rankOf(left) != candidate[0]) continue // 过期条目

            // 合并 [left] + [right]
            endPos[left] = endPos[right]
            partCache[left] = null
            alive[right] = false
            next[left] = next[right]
            if (next[right] >= 0) prev[next[right]] = left
            count--

            // 新产生的相邻对（左邻与自身）
            val newRight = next[left]
            if (newRight >= 0) {
                val rank = rankOf(left)
                if (rank >= 0) heap.add(intArrayOf(rank, left))
            }
            val newLeft = prev[left]
            if (newLeft >= 0) {
                val rank = rankOf(newLeft)
                if (rank >= 0) heap.add(intArrayOf(rank, newLeft))
            }
        }
        return count
    }
}
