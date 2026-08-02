package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.datastore.Settings

/**
 * 变量斜杠命令操作（对齐酒馆官方 variables.js 的变量家族语义）。
 * chat 级以 conversationId 为作用域，global 级跨对话持久。
 */
enum class SlashVarOp {
    SET,
    GET,
    ADD,
    INC,
    DEC,
    FLUSH,
    LIST,
}

/**
 * 应用变量命令到 Settings 快照。
 *
 * @return (新 Settings, 结果文本)。未发生写入时返回原 Settings。
 * 语义对齐官方：
 *  - set：写入变量，返回 "name = value"
 *  - get：读取变量，未设置返回 "（未设置）"
 *  - add：数值相加，非数值拼接（与宏引擎 SettingsMacroVars.add 一致）
 *  - inc/dec：数值 +1/-1，非数值按 0 起算
 *  - flush：删除变量
 *  - list：列出本对话变量与全局变量
 */
fun applyMacroVarSlash(
    settings: Settings,
    op: SlashVarOp,
    name: String,
    value: String,
    global: Boolean,
    chatKey: String,
): Pair<Settings, String> {
    val newGlobal = settings.macroGlobalVariables.toMutableMap()
    val chatVars = settings.macroChatVariables.toMutableMap()
    val chat = chatVars[chatKey]?.toMutableMap() ?: mutableMapOf()

    fun current(): String? = if (global) newGlobal[name] else chat[name]

    fun store(): MutableMap<String, String> = if (global) newGlobal else chat

    val result = when (op) {
        SlashVarOp.SET -> {
            store()[name] = value
            "$name = $value"
        }

        SlashVarOp.GET -> current() ?: "（未设置）"

        SlashVarOp.ADD -> {
            val left = current()?.toLongOrNull()
            val right = value.toLongOrNull()
            val next = when {
                left != null && right != null -> (left + right).toString()
                current() == null -> value
                else -> (current() ?: "") + value
            }
            store()[name] = next
            next
        }

        SlashVarOp.INC -> {
            val next = (current()?.toLongOrNull() ?: 0L) + 1L
            store()[name] = next.toString()
            next.toString()
        }

        SlashVarOp.DEC -> {
            val next = (current()?.toLongOrNull() ?: 0L) - 1L
            store()[name] = next.toString()
            next.toString()
        }

        SlashVarOp.FLUSH -> {
            store().remove(name)
            "已删除 $name"
        }

        SlashVarOp.LIST -> {
            val lines = buildList {
                if (chat.isNotEmpty()) {
                    append("本对话: " + chat.entries.joinToString("、") { "${it.key}=${it.value}" })
                }
                if (newGlobal.isNotEmpty()) {
                    append("全局: " + newGlobal.entries.joinToString("、") { "${it.key}=${it.value}" })
                }
            }
            if (lines.isEmpty()) "（暂无变量）" else lines.joinToString("\n")
        }
    }

    val newSettings = when {
        global -> settings.copy(macroGlobalVariables = newGlobal)
        op == SlashVarOp.LIST -> settings
        else -> settings.copy(macroChatVariables = chatVars.apply { put(chatKey, chat) })
    }
    return newSettings to result
}
