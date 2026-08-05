package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings

/**
 * 变量斜杠命令操作（对齐酒馆官方 variables.js 的变量家族语义）。
 * 仅本对话级：以 conversationId 为作用域，跨对话隔离。
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
 *  - list：列出本对话变量
 */
fun applyMacroVarSlash(
    context: Context,
    settings: Settings,
    op: SlashVarOp,
    name: String,
    value: String,
    chatKey: String,
): Pair<Settings, String> {
    val chatVars = settings.macroChatVariables.toMutableMap()
    val chat = chatVars[chatKey]?.toMutableMap() ?: mutableMapOf()

    fun current(): String? = chat[name]

    fun store(): MutableMap<String, String> = chat

    val result = when (op) {
        SlashVarOp.SET -> {
            store()[name] = value
            "$name = $value"
        }

        SlashVarOp.GET -> current() ?: context.getString(R.string.slash_var_unset)

        SlashVarOp.ADD -> {
            // 官方 Number() 语义：小数也按数值相加（10.5+1.5=12），结果整数时去掉 .0
            val left = current()?.toDoubleOrNull()
            val right = value.toDoubleOrNull()
            val next = when {
                left != null && right != null -> {
                    val sum = left + right
                    if (sum % 1.0 == 0.0) sum.toLong().toString() else sum.toString()
                }
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
            context.getString(R.string.slash_var_deleted, name)
        }

        SlashVarOp.LIST -> {
            if (chat.isEmpty()) {
                context.getString(R.string.slash_var_empty)
            } else {
                context.getString(R.string.slash_var_list_prefix) + chat.entries.joinToString(context.getString(R.string.slash_field_sep)) { "${it.key}=${it.value}" }
            }
        }
    }

    val newSettings = when {
        op == SlashVarOp.LIST -> settings
        else -> settings.copy(macroChatVariables = chatVars.apply { put(chatKey, chat) })
    }
    return newSettings to result
}
