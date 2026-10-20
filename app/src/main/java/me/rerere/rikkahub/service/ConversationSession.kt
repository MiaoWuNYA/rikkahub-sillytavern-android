package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val IDLE_TIMEOUT_MS = 5_000L

private const val TAG = "ConversationSession"

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
    /**
     * 初始内容是否可信：false 表示 session 由空 Conversation 兜底建起，
     * state 不代表数据库真实内容，任何保存都被拒绝，直到 rehydrate 恢复。
     */
    @Volatile
    var contentTrusted: Boolean = true,
) {
    // 会话状态
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    private val initializationMutex = Mutex()
    private val metadataMutex = Mutex()
    @Volatile
    private var initialized = false
    val messageQueue = MessageQueue()

    // 会话保存互斥锁：后台触发源与用户操作并发"读-改-存"同一会话时序列化保存动作。
    val saveMutex = Mutex()

    // 页面切换和 SSE 重连只加载一次；活跃 session 的内存状态始终优先。
    suspend fun initialize(load: suspend () -> Conversation) {
        initializationMutex.withLock {
            if (initialized) return
            val conversation = load()
            synchronized(this) {
                // 加载挂起期间可能已经通过保存或编辑写入了更新的状态。
                if (!initialized) updateConversation(conversation)
            }
        }
    }

    /** 用数据库里的真实内容重新填充，并恢复可信标记。 */
    fun rehydrate(conversation: Conversation) {
        _state.value = conversation
        contentTrusted = true
    }

    @Synchronized
    fun updateConversation(conversation: Conversation) {
        require(conversation.id == id)
        _state.value = conversation
        initialized = true
    }

    /**
     * 更新尚未落库的新会话的草稿字段（例如跟随抽屉选中的文件夹）。
     *
     * 未初始化时**只改内存、不置 [initialized]**：新会话页面刚打开时，
     * 外部改动可能抢在 [initialize] 之前跑完，一旦这里把会话标记成已初始化，
     * initialize 的 `if (initialized) return` 会整段跳过，开场白（含多开场白
     * 选择）就再也插不进来。已初始化的会话走正常路径。
     */
    fun updateDraftState(transform: (Conversation) -> Conversation) {
        synchronized(this) {
            if (initialized) updateConversation(transform(_state.value))
            else _state.value = transform(_state.value)
        }
    }

    // 元数据先应用到最新内存状态；落库只更新对应列，不能用旧消息快照覆盖流式输出。
    internal suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            val updated = synchronized(this) {
                update(state.value).also(::updateConversation)
            }
            persist(updated)
        }
    }

    // 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    suspend fun finishGeneration(save: suspend (Conversation) -> Unit): Conversation =
        withContext(NonCancellable) {
            val current = state.value
            val conversation = current.copy(
                messageNodes = current.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { it.finishReasoning() })
                },
                updateAt = Instant.now(),
            )
            updateConversation(conversation)
            save(conversation)
            conversation
        }

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
