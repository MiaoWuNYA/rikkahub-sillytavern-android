package me.rerere.rikkahub.data.st.expressions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

object ExpressionStore {

    /** 每个对话当前展示的立绘（messageId + label） */
    data class Selection(val messageId: String, val label: String)

    /** 官方 `STREAMING_UPDATE_INTERVAL`（毫秒） */
    const val CLASSIFY_INTERVAL_MS: Long = 10_000L

    /** 分类缓存条数上限（超出后整体清空重建，对齐官方 spriteCache 的粗粒度 LRU 语义） */
    const val MAX_CACHE_ENTRIES: Int = 512

    private val labelCache = ConcurrentHashMap<String, String>()
    private val lastClassifyAt = ConcurrentHashMap<String, Long>()
    private val _selections = MutableStateFlow<Map<String, Selection>>(emptyMap())

    /** conversationId → 当前立绘；供 Compose 直接 collect */
    val selections: StateFlow<Map<String, Selection>> = _selections.asStateFlow()

    // ==================== messageId 分类缓存 ====================

    /** 读取某条消息已缓存的分类结果（无缓存返回 null） */
    fun cachedLabel(messageId: String): String? = labelCache[messageId]

    /** 写入某条消息的分类结果 */
    fun putLabel(messageId: String, label: String) {
        if (labelCache.size >= MAX_CACHE_ENTRIES) labelCache.clear()
        labelCache[messageId] = label
    }

    /** 测试/回滚用：清空全部运行态 */
    fun resetForTest() {
        labelCache.clear()
        lastClassifyAt.clear()
        _selections.value = emptyMap()
    }

    // ==================== 分类节流（官方 10s 间隔） ====================

    fun shouldThrottleClassify(
        conversationId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val last = lastClassifyAt[conversationId] ?: return false
        return nowMillis - last < CLASSIFY_INTERVAL_MS
    }

    /** 记录一次实际发生的分类调用时间 */
    fun markClassified(
        conversationId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        lastClassifyAt[conversationId] = nowMillis
    }

    // ==================== 当前立绘选择 ====================

    /** 选中某条消息的分类结果作为当前立绘（消息/标签未变时不产生新事件） */
    fun select(conversationId: String, messageId: String, label: String) {
        val selection = Selection(messageId, label)
        _selections.update { current ->
            if (current[conversationId] == selection) current else current + (conversationId to selection)
        }
    }

    /** 清掉某对话的展示态（不清理 messageId 缓存：同一消息重新进入时可直接复用） */
    fun clearConversation(conversationId: String) {
        _selections.update { it - conversationId }
    }
}
