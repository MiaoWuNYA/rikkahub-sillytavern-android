package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台子代理注册表：工具只负责派活，状态与结果留在这里等主线取回。
 */
class SubAgentHub {
    enum class State { RUNNING, DONE, FAILED }

    class Run internal constructor(
        val id: String,
        val conversationId: String,
        val assistantName: String,
        val prompt: String,
    ) {
        val startedAt: Long = System.currentTimeMillis()

        @Volatile var state: State = State.RUNNING
        @Volatile var answer: String? = null
        @Volatile var error: String? = null
        // 结果已被主线取走或已回传：避免重复注入
        @Volatile var picked: Boolean = false
    }

    private val counter = AtomicLong(0)
    private val runs = ConcurrentHashMap<String, Run>()

    fun register(conversationId: String, assistantName: String, prompt: String): Run {
        val id = "sa" + counter.incrementAndGet().toString(36) +
            Integer.toHexString((System.nanoTime() and 0xffff).toInt())
        val run = Run(id, conversationId, assistantName, prompt)
        runs[id] = run
        if (runs.size > MAX_KEPT_RUNS) {
            runs.values.sortedBy { it.startedAt }
                .take(runs.size - MAX_KEPT_RUNS)
                .forEach { runs.remove(it.id) }
        }
        return run
    }

    fun finish(run: Run, answer: String) {
        run.answer = answer
        run.state = State.DONE
    }

    fun fail(run: Run, message: String) {
        run.error = message
        run.state = State.FAILED
    }

    fun get(id: String): Run? = runs[id]

    fun recent(conversationId: String, limit: Int = 8): List<Run> = runs.values
        .filter { it.conversationId == conversationId }
        .sortedByDescending { it.startedAt }
        .take(limit)

    private companion object {
        const val MAX_KEPT_RUNS = 200
    }
}
