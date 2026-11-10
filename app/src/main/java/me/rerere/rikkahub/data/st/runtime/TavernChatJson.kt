package me.rerere.rikkahub.data.st.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode

private val tavernChatJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 将当前 [Conversation] 序列化为 SillyTavern `getContext().chat` 标准数组格式（`ChatMessage[]`）。
 *
 * 每项结构（对齐 SillyTavern `script.js` 与 `JS-Slash-Runner` / `MC-记忆表格` 契约）：
 * - `mes`: 当前选中 swipe 的纯文本
 * - `name`: 发言者名称（用户昵称或助手名称）
 * - `is_user`: 是否为用户消息
 * - `is_system`: 是否为隐藏消息（对齐 ST `/hide` 的 `is_system` 字段）
 * - `swipe_id`: 当前选中分支下标（`node.selectIndex`）
 * - `swipes`: 全部分支文本列表（`node.messages.map { it.toText() }`）
 * - `extra`: `{}`（供扩展挂载附加元数据）
 */
/**
 * 楼层 JSON 节点级 LRU 缓存（Task F & F-04 修复）：
 * - 在 200~500 楼长对话中，每次发送消息、`fireEvent` 或流式输出 token 时，`RikkaBridge.getChatJson()`
 *   都会被调用；但绝大部分历史楼层的 `MessageNode` 引用与内容完全未变。
 * - `NodeCacheKey` 完整覆盖被序列化进单楼层 JSON 的全部字段：
 *   `(nodeId, index, selectIndex, messagesSize, currentMsgId, currentText, swipes, sendDate, isUser, isHidden, speakerName, altGreetingCount)`。
 * - 这样无论是修改未选中的后台分支（non-selected swipe）、修改角色卡 `alternateGreetings` 文本、修改发送时间或发言者角色，
 *   还是遇到相同 32-bit `hashCode()` 的不同字符串（如 `"Aa"` 与 `"BB"`），缓存键均能通过精确相等性比较立即识别并重建，绝不返回陈旧 JSON！
 */
internal object TavernChatJsonCache {
    private const val MAX_NODE_ENTRIES = 1024

    data class NodeCacheKey(
        val nodeId: String,
        val index: Int,
        val selectIndex: Int,
        val messagesSize: Int,
        val currentMsgId: String,
        val currentText: String,
        val swipes: List<String>,
        val sendDate: String,
        val isUser: Boolean,
        val isHidden: Boolean,
        val speakerName: String,
        val altGreetingCount: Int,
    )

    private val lock = Any()
    private val nodeMap = object : java.util.LinkedHashMap<NodeCacheKey, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeCacheKey, String>?): Boolean =
            size > MAX_NODE_ENTRIES
    }

    @Volatile
    var lastCacheHits: Int = 0
        internal set

    fun getOrBuildNodeJson(
        index: Int,
        node: MessageNode,
        current: UIMessage,
        speakerName: String,
        alternateGreetings: List<String>,
    ): Pair<String, Boolean> {
        val currentText = current.toText()
        val isUser = current.role == MessageRole.USER
        val effectiveAltCount = if (index == 0 && !isUser && node.messages.size == 1) alternateGreetings.size else 0
        val swipesList = if (effectiveAltCount > 0) {
            listOf(currentText) + alternateGreetings
        } else if (node.messages.size == 1) {
            listOf(currentText)
        } else {
            node.messages.map { it.toText() }
        }
        val sendDate = current.createdAt.toString()
        val key = NodeCacheKey(
            nodeId = node.id.toString(),
            index = index,
            selectIndex = node.selectIndex,
            messagesSize = node.messages.size,
            currentMsgId = current.id.toString(),
            currentText = currentText,
            swipes = swipesList,
            sendDate = sendDate,
            isUser = isUser,
            isHidden = current.isHidden,
            speakerName = speakerName,
            altGreetingCount = effectiveAltCount,
        )
        synchronized(lock) {
            nodeMap[key]?.let { return it to true }
        }
        val built = buildJsonObject {
            put("message_id", index)
            put("name", speakerName)
            put("is_user", isUser)
            put("is_system", current.isHidden)
            put("send_date", sendDate)
            put("mes", currentText)
            put("swipe_id", node.selectIndex)
            put(
                "swipes",
                buildJsonArray {
                    swipesList.forEach { s -> add(JsonPrimitive(s)) }
                },
            )
            put("extra", buildJsonObject {})
        }.toString()
        synchronized(lock) {
            nodeMap[key] = built
        }
        return built to false
    }

    fun clear() {
        synchronized(lock) {
            nodeMap.clear()
            lastCacheHits = 0
        }
    }
}

fun buildTavernChatJson(
    conversation: Conversation?,
    settings: Settings?,
): String {
    if (conversation == null || conversation.messageNodes.isEmpty()) return "[]"
    val assistant = settings?.getAssistantById(conversation.assistantId)
        ?: settings?.getCurrentAssistant()
    val charName = assistant?.tavernData?.name?.ifBlank { assistant.name }
        ?: assistant?.name?.ifBlank { "Assistant" }
        ?: "Assistant"
    val activePersona = settings?.personas?.firstOrNull { it.id == settings.activePersonaId }
    val userName = activePersona?.name?.ifBlank { settings.displaySetting.userNickname }
        ?: settings?.displaySetting?.userNickname?.ifBlank { "User" }
        ?: "User"

    val alternateGreetings = assistant?.tavernData?.alternateGreetings
        ?.filter { it.isNotBlank() }
        .orEmpty()

    val sb = StringBuilder(conversation.messageNodes.size * 256)
    sb.append('[')
    var first = true
    var hits = 0
    conversation.messageNodes.forEachIndexed { index, node ->
        val current = node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull()
        if (current != null) {
            val isUser = current.role == MessageRole.USER
            val speakerName = current.name?.takeIf { it.isNotBlank() }
                ?: if (isUser) userName else charName
            val (nodeJson, hit) = TavernChatJsonCache.getOrBuildNodeJson(
                index = index,
                node = node,
                current = current,
                speakerName = speakerName,
                alternateGreetings = alternateGreetings,
            )
            if (hit) hits++
            if (!first) sb.append(',')
            first = false
            sb.append(nodeJson)
        }
    }
    sb.append(']')
    TavernChatJsonCache.lastCacheHits = hits
    return sb.toString()
}

/**
 * 将 JS 侧 `setChatMessages` / `setChatMessage` / `saveChat` 回传的增量更新数组应用到 [Conversation]。
 *
 * 支持：
 * 1. 仅传 `swipe_id`（如角色卡开场白按钮点击切换内置预设开场白 `setChatMessages([{ message_id: 0, swipe_id: 2 }])`）：
 *    若第 0 楼当前仅有 1 条消息但角色卡包含 `alternateGreetings`，自动将 `alternateGreetings` 展开为该楼层的 `messages` 分支并切换到目标 `swipe_id`；
 * 2. 传 `swipes` 数组和/或 `mes`/`message` 文本：同步更新分支文本与选中下标；
 * 3. 传 `is_system` / `name`：同步更新隐藏状态与发言者名称。
 */
fun applyTavernChatUpdates(
    conversation: Conversation,
    updatesJson: String,
    alternateGreetings: List<String> = emptyList(),
): Conversation? {
    if (updatesJson.isBlank()) return null
    val arr = runCatching { tavernChatJson.parseToJsonElement(updatesJson) as? JsonArray }.getOrNull()
        ?: return null
    if (arr.isEmpty()) return null

    val nodes = conversation.messageNodes.toMutableList()
    var mutated = false

    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        val messageId = obj["message_id"]?.jsonPrimitiveOrNull?.intOrNull
            ?: obj["id"]?.jsonPrimitiveOrNull?.intOrNull
            ?: continue
        if (messageId !in nodes.indices) continue

        val node = nodes[messageId]
        val currentMsg = node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull() ?: continue

        val swipesArr = obj["swipes"] as? JsonArray
        val mesText = obj["mes"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: obj["message"]?.jsonPrimitiveOrNull?.contentOrNull
        val swipeId = obj["swipe_id"]?.jsonPrimitiveOrNull?.intOrNull
        val isSystem = obj["is_system"]?.jsonPrimitiveOrNull?.booleanOrNull
        val newName = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull

        if (swipesArr == null && mesText == null && swipeId == null && isSystem == null && newName == null) {
            continue
        }

        val baseMessages: List<UIMessage> = if (
            swipesArr == null &&
            mesText == null &&
            swipeId != null &&
            swipeId >= node.messages.size &&
            messageId == 0 &&
            currentMsg.role == MessageRole.ASSISTANT &&
            alternateGreetings.isNotEmpty()
        ) {
            val firstText = node.messages.firstOrNull()?.toText().orEmpty()
            val allGreetingTexts = listOf(firstText) + alternateGreetings.filter { it.isNotBlank() }
            allGreetingTexts.mapIndexed { idx, gText ->
                val existing = node.messages.getOrNull(idx)
                existing?.copy(parts = replaceTextParts(existing.parts, gText))
                    ?: currentMsg.copy(
                        id = kotlin.uuid.Uuid.random(),
                        parts = listOf(UIMessagePart.Text(gText)),
                    )
            }
        } else {
            node.messages
        }

        val updatedMessages: List<UIMessage> = when {
            swipesArr != null && swipesArr.isNotEmpty() -> {
                val swipeTexts = swipesArr.map { it.jsonPrimitiveOrNull?.contentOrNull ?: "" }
                val targetSwipeIdx = (swipeId ?: node.selectIndex).coerceIn(0, swipeTexts.lastIndex)
                swipeTexts.mapIndexed { idx, sText ->
                    val effectiveText = if (idx == targetSwipeIdx && mesText != null) mesText else sText
                    val baseMsg = baseMessages.getOrNull(idx) ?: currentMsg.copy(id = kotlin.uuid.Uuid.random())
                    baseMsg.withUpdatedFields(
                        newText = effectiveText,
                        isSystem = if (idx == targetSwipeIdx) isSystem else null,
                        newName = if (idx == targetSwipeIdx) newName else null,
                    )
                }
            }
            mesText != null -> {
                val targetIdx = (swipeId ?: node.selectIndex).coerceIn(0, (baseMessages.size - 1).coerceAtLeast(0))
                if (baseMessages.isEmpty()) {
                    listOf(
                        currentMsg.withUpdatedFields(
                            newText = mesText,
                            isSystem = isSystem,
                            newName = newName,
                        )
                    )
                } else {
                    baseMessages.mapIndexed { idx, msg ->
                        if (idx == targetIdx) {
                            msg.withUpdatedFields(
                                newText = mesText,
                                isSystem = isSystem,
                                newName = newName,
                            )
                        } else {
                            msg
                        }
                    }
                }
            }
            else -> {
                val targetIdx = (swipeId ?: node.selectIndex).coerceIn(0, (baseMessages.size - 1).coerceAtLeast(0))
                baseMessages.mapIndexed { idx, msg ->
                    if (idx == targetIdx && (isSystem != null || newName != null)) {
                        msg.withUpdatedFields(
                            newText = null,
                            isSystem = isSystem,
                            newName = newName,
                        )
                    } else {
                        msg
                    }
                }
            }
        }

        val newSelectIndex = (swipeId ?: node.selectIndex).coerceIn(0, (updatedMessages.size - 1).coerceAtLeast(0))
        val newNode = node.copy(
            messages = updatedMessages,
            selectIndex = newSelectIndex,
        )
        if (newNode != node) {
            nodes[messageId] = newNode
            mutated = true
        }
    }

    return if (mutated) conversation.copy(messageNodes = nodes) else null
}

/**
 * 解析 JSR `createChatMessages` 传入的待插入楼层数组。
 */
data class RuntimeChatInsertItem(
    val role: MessageRole,
    val message: String,
    val name: String? = null,
    val data: JsonObject? = null,
)

fun parseRuntimeChatInserts(jsonText: String): List<RuntimeChatInsertItem> {
    if (jsonText.isBlank()) return emptyList()
    val arr = runCatching { tavernChatJson.parseToJsonElement(jsonText) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val roleStr = obj["role"]?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()?.trim() ?: "assistant"
        val role = when (roleStr) {
            "user", "human" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            else -> MessageRole.ASSISTANT
        }
        val msg = obj["message"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: obj["mes"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: ""
        val name = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        val dataObj = obj["data"] as? JsonObject
        RuntimeChatInsertItem(
            role = role,
            message = msg,
            name = name,
            data = dataObj,
        )
    }
}

private fun UIMessage.withUpdatedFields(
    newText: String?,
    isSystem: Boolean?,
    newName: String?,
): UIMessage {
    var updated = if (newText != null) {
        copy(parts = replaceTextParts(parts, newText))
    } else {
        this
    }
    if (isSystem != null) {
        updated = if (isSystem) updated.withHidden() else updated.withoutHidden()
    }
    if (!newName.isNullOrBlank()) {
        updated = updated.copy(name = newName)
    }
    return updated
}

private fun replaceTextParts(parts: List<UIMessagePart>, newText: String): List<UIMessagePart> {
    val nonText = parts.filterNot { it is UIMessagePart.Text }
    return listOf(UIMessagePart.Text(newText)) + nonText
}

private val kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

/**
 * 将 Conversation 导出为官方 SillyTavern `.jsonl` 聊天记录格式（首行为对话元数据头，后续每行为一条消息 JSON）。
 */
fun exportConversationToStJsonl(
    conversation: Conversation,
    userName: String = "User",
    characterName: String = "Assistant",
    chatMetadata: JsonObject = buildJsonObject {},
): String {
    val lines = mutableListOf<String>()
    val header = buildJsonObject {
        put("user_name", JsonPrimitive(userName))
        put("character_name", JsonPrimitive(characterName))
        put("create_date", JsonPrimitive(conversation.createAt.toString()))
        put("chat_metadata", chatMetadata)
    }
    lines += header.toString()
    val chatArrayJson = buildTavernChatJson(conversation, null)
    val arr = runCatching { Json.parseToJsonElement(chatArrayJson) as? JsonArray }.getOrNull() ?: JsonArray(emptyList())
    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        lines += obj.toString()
    }
    return lines.joinToString("\n")
}

/**
 * 从官方 SillyTavern `.jsonl` 聊天记录文本无损导入为 Conversation（支持多 swipes、is_user、is_system 隐藏标志与消息级 variables）。
 */
fun importConversationFromStJsonl(
    jsonl: String,
    assistantId: kotlin.uuid.Uuid = kotlin.uuid.Uuid.random(),
    title: String = "",
): Conversation {
    val rawLines = jsonl.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val nodes = mutableListOf<me.rerere.rikkahub.data.model.MessageNode>()
    var resolvedTitle = title
    for ((idx, line) in rawLines.withIndex()) {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
        // 首行若是 header（包含 user_name / character_name / chat_metadata 且无 mes 字段）则跳过消息构造
        if (idx == 0 && !obj.containsKey("mes") && (obj.containsKey("user_name") || obj.containsKey("character_name") || obj.containsKey("chat_metadata"))) {
            if (resolvedTitle.isBlank()) {
                resolvedTitle = (obj["character_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
            continue
        }
        val isUser = (obj["is_user"] as? JsonPrimitive)?.booleanOrNull == true
        val isSystemHidden = (obj["is_system"] as? JsonPrimitive)?.booleanOrNull == true
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
        val mes = (obj["mes"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val swipesArr = (obj["swipes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val swipeTexts = if (!swipesArr.isNullOrEmpty()) swipesArr else listOf(mes)
        val swipeId = ((obj["swipe_id"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, (swipeTexts.size - 1).coerceAtLeast(0))
        val role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT
        val messages = swipeTexts.map { text ->
            val base = UIMessage(
                role = role,
                parts = listOf(UIMessagePart.Text(text)),
                name = name,
            )
            if (isSystemHidden) base.withHidden() else base.withoutHidden()
        }
        nodes += me.rerere.rikkahub.data.model.MessageNode(
            id = kotlin.uuid.Uuid.random(),
            messages = messages,
            selectIndex = swipeId,
        )
    }
    return Conversation(
        id = kotlin.uuid.Uuid.random(),
        assistantId = assistantId,
        title = resolvedTitle.ifBlank { "Imported ST Chat" },
        messageNodes = nodes,
    )
}
