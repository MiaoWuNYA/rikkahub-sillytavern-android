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
 * context 仅用于结果文案的本地化，可为 null（纯 JVM 单元测试环境），
 * 此时回退到内置中文文案。
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
    context: Context?,
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

    fun str(id: Int, vararg args: Any): String = context?.getString(id, *args) ?: when (id) {
        R.string.slash_var_unset -> "（未设置）"
        R.string.slash_var_deleted -> "已删除 ${args.getOrNull(0) ?: ""}"
        R.string.slash_var_empty -> "（暂无变量）"
        R.string.slash_var_list_prefix -> "本对话: "
        R.string.slash_field_sep -> "、"
        else -> ""
    }

    val result = when (op) {
        SlashVarOp.SET -> {
            store()[name] = value
            "$name = $value"
        }

        SlashVarOp.GET -> current() ?: str(R.string.slash_var_unset)

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
            str(R.string.slash_var_deleted, name)
        }

        SlashVarOp.LIST -> {
            if (chat.isEmpty()) {
                str(R.string.slash_var_empty)
            } else {
                str(R.string.slash_var_list_prefix) + chat.entries.joinToString(str(R.string.slash_field_sep)) { "${it.key}=${it.value}" }
            }
        }
    }

    // GET/LIST 不写入，返回原 Settings 实例（调用方用引用相等判断是否需要持久化）
    val newSettings = when {
        op == SlashVarOp.LIST || op == SlashVarOp.GET -> settings
        else -> settings.copy(macroChatVariables = chatVars.apply { put(chatKey, chat) })
    }
    return newSettings to result
}
