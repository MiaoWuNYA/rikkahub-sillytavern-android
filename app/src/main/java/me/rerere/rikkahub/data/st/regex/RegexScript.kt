package me.rerere.rikkahub.data.st.regex

import kotlinx.serialization.Serializable

@Serializable
data class RegexScript(
    val id: String = "",
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    /** 匹配内容中需要剔除的字符串（对齐 ST trimStrings，作用于 $1/$<name>/{{match}} 引用值） */
    val trimStrings: List<String> = emptyList(),
    /** 应用位置集合，取值见 [RegexPlacement]（ST：数字数组） */
    val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    /** 仅格式化显示（Markdown 渲染时应用）；对齐 ST markdownOnly */
    val markdownOnly: Boolean = false,
    /** 仅格式化提示词（组装发送给 AI 的提示词时应用）；对齐 ST promptOnly */
    val promptOnly: Boolean = false,
    /** 编辑消息时是否应用（配合 isEdit）；对齐 ST runOnEdit */
    val runOnEdit: Boolean = false,
    /** findRegex 的宏替换模式，取值见 [SubstituteRegex]；对齐 ST substituteRegex */
    val substituteRegex: Int = SubstituteRegex.NONE,
    /** 最小消息深度（含）；null 或 < -1 视为不限制；对齐 ST minDepth */
    val minDepth: Int? = null,
    /** 最大消息深度（含）；null 或 < 0 视为不限制；对齐 ST maxDepth */
    val maxDepth: Int? = null,
    /** [P1-3] 若来自酒馆预设内嵌正则（extensions.regex_scripts），记录所属预设组名；用户自建/全局导入为 null。 */
    val sourcePresetGroup: String? = null,
)

/** ST regex_placement（public/scripts/extensions/regex/engine.js） */
object RegexPlacement {
    /** 已废弃（ST 保留仅用于兼容旧数据） */
    const val MD_DISPLAY = 0
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3

    /** 4 为 legacy sendAs，已废弃 */
    const val WORLD_INFO = 5
    const val REASONING = 6
}

/** ST substitute_find_regex（findRegex 的宏替换模式） */
object SubstituteRegex {
    const val NONE = 0

    /** 先做宏替换，再作为正则编译（对齐 ST substituteParamsExtended） */
    const val RAW = 1

    /** 宏替换后对替换值做正则转义（对齐 ST sanitizeRegexMacro） */
    const val ESCAPED = 2
}
