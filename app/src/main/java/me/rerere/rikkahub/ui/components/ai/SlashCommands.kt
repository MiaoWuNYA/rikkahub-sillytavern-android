package me.rerere.rikkahub.ui.components.ai

import android.content.Context

import me.rerere.rikkahub.data.files.CommandFile
import me.rerere.rikkahub.R

/**
 * 斜杠命令 — 来自 skill 的 commands 目录下的 .md 文件
 * Claude Code 风格：每个 commands/xxx.md 自动变成 /xxx 命令
 */
data class SlashCommand(
    val name: String,                    // 命令名，如 "code-review"
    val description: String,             // 简短描述
    val allowedTools: List<String> = emptyList(), // 允许的工具（内置命令默认空）
    // 参数提示，如 "[文本]" / "[key]"。约定：空 = 点击建议时直接执行；
    // 非空 = 点击建议时填入输入框，等用户补参数后按发送执行（需要参数的命令必须填，漏填会被当无参数直接执行）
    val argumentHint: String = "",
    val disableModelInvocation: Boolean = false, // 纯脚本不调模型
    val content: String,                 // 指令正文
    val filePath: String,                // 源文件路径，如 "commands/code-review.md"
    val builtinKind: BuiltinSlashKind? = null, // 内置命令类型（非空时为官方风格内置命令）
    // 帮助弹窗中逐参数的中文说明与例句（内置命令使用）
    val params: List<SlashParam> = emptyList(),
    // 帮助弹窗中的完整命令例句（含效果说明）
    val examples: List<SlashExample> = emptyList(),
)

/** 单个命名参数的说明与例句 */
data class SlashParam(
    val name: String,        // 参数名，如 "name="
    val description: String, // 中文说明
    val example: String,     // 例句（含参数用法）
)

/** 完整命令例句与效果说明 */
data class SlashExample(
    val command: String,  // 完整命令，如 "/sys name=旁白 雨夜"
    val description: String, // 效果说明
)

/**
 * 内置斜杠命令类型（对齐酒馆官方常用命令，全局可用）
 */
enum class BuiltinSlashKind {
    /** 帮助：列出内置命令 */
    HELP,

    /** 信息展示：Toast 显示结果 */
    INFO,

    /** 系统消息：插入 SYSTEM 角色消息，不触发生成 */
    SYS,

    /** 以助手身份发言（官方 sendas），不触发生成 */
    SENDAS,

    /** 以用户身份发言（官方 send），不触发生成 */
    SEND,

    /** 切换用户人设 */
    PERSONA,

    /** 触发生成：不添加新消息，直接让 AI 回复一次 */
    TRIGGER,

    /** 生成系统旁白：按提示词让 AI 写一条系统消息并插入 */
    SYSGEN,

    /** 变量命令：设置/读取/增删宏变量（官方变量家族） */
    VAR,

    /** 角色操作：修改当前助手 */
    RENAME,

    /** 续写上一条助手回复（官方 /continue） */
    CONTINUE,

    /** 以角色身份生成一条回复（官方 /impersonate） */
    IMPERSONATE,

    /** 安静生成：用提示词让 AI 生成文本，结果填入输入框（官方 /gen） */
    GEN,

    /** 重新掷 {{pick}} 稳定随机（官方 /reroll-pick） */
    REROLL_PICK,

    /** 修改当前角色卡字段（字段=值） */
    UPDATE_CHAR,

    /** 复制当前角色卡（需要页面回调） */
    DUPLICATE,
}

/**
 * 内置命令 — 官方 SillyTavern 常用命令的实用子集，全局可用
 */
fun builtinSlashCommands(context: Context): List<SlashCommand> = listOf(
    SlashCommand(
        name = "help",
        description = context.getString(R.string.slash_help_desc),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.HELP,
    ),
    SlashCommand(
        name = "sys",
        description = context.getString(R.string.slash_sys_desc),
        argumentHint = context.getString(R.string.slash_sys_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SYS,
        params = listOf(
            SlashParam("name=", "消息显示的名字（默认 System）", "/sys name=旁白 雨夜，街道空旷"),
            SlashParam("at=", "插入位置：非负数从开头数，负数从末尾数（-1=最后一条之前）", "/sys at=-1 战斗结束，四周安静下来"),
        ),
        examples = listOf(
            SlashExample("/sys 雨夜，路灯忽明忽暗", "在聊天末尾插入一条系统消息"),
            SlashExample("/sys name=旁白 at=-1 她握紧了剑", "用「旁白」名字、插在最后一条消息之前"),
        ),
    ),
    SlashCommand(
        name = "sendas",
        description = context.getString(R.string.slash_sendas_desc),
        argumentHint = context.getString(R.string.slash_sendas_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SENDAS,
        params = listOf(
            SlashParam("name=", "以哪个角色的身份发言（默认当前角色名）", "/sendas name=宁凝 她轻轻推开窗"),
            SlashParam("at=", "插入位置（同 /sys 的 at）", "/sendas at=0 开场白"),
        ),
        examples = listOf(
            SlashExample("/sendas 她笑了一下", "以当前角色身份插入一条助手消息（不触发生成）"),
            SlashExample("/sendas name=宁凝 at=-1 她在心里想道", "以「宁凝」身份、插在最后一条之前"),
        ),
    ),
    SlashCommand(
        name = "send",
        description = context.getString(R.string.slash_send_desc),
        argumentHint = context.getString(R.string.slash_send_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SEND,
        params = listOf(
            SlashParam("name=", "发言显示的名字（默认你的名字）", "/send name=我 我想去夜市"),
            SlashParam("at=", "插入位置（同 /sys 的 at）", "/send at=0 开场第一句话"),
        ),
        examples = listOf(
            SlashExample("/send 我们换个话题吧", "以你的身份插入一条用户消息（不触发生成）"),
            SlashExample("/send name=旅行者 我明天出发", "以「旅行者」的名字插入"),
        ),
    ),
    SlashCommand(
        name = "persona",
        description = context.getString(R.string.slash_persona_desc),
        argumentHint = context.getString(R.string.slash_persona_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.PERSONA,
        params = listOf(
            SlashParam("mode=", "lookup 只选已有人设 / temp 只设临时用户名 / all 先找已有再设临时（默认）", "/persona mode=temp 小名"),
            SlashParam("off / none", "关闭当前人设", "/persona off"),
        ),
        examples = listOf(
            SlashExample("/persona 智乃", "切换到已保存的人设「智乃」"),
            SlashExample("/persona mode=temp 冒险者", "只设置临时用户名，不保存人设"),
            SlashExample("/persona off", "关闭当前人设"),
        ),
    ),
    SlashCommand(
        name = "trigger",
        description = context.getString(R.string.slash_trigger_desc),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TRIGGER,
    ),
    SlashCommand(
        name = "sysgen",
        description = context.getString(R.string.slash_sysgen_desc),
        argumentHint = context.getString(R.string.slash_sysgen_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SYSGEN,
        params = listOf(
            SlashParam("name=", "旁白消息显示的名字（默认 System）", "/sysgen name=旁白 描写深夜的走廊"),
            SlashParam("at=", "插入位置（同 /sys 的 at）", "/sysgen at=-1 描写角色握剑的手"),
            SlashParam("trim=", "true 时把结果裁剪到完整句子", "/sysgen trim=true 描写雨后的街道"),
        ),
        examples = listOf(
            SlashExample("/sysgen 描写雨夜街道", "让 AI 写一条系统旁白插入聊天（会触发一次生成）"),
            SlashExample("/sysgen trim=true name=旁白 描写角色内心的挣扎", "裁剪到完整句子，以「旁白」名字插入"),
        ),
    ),
    SlashCommand(
        name = "char-get",
        description = context.getString(R.string.slash_char_get_desc),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INFO,
    ),
    SlashCommand(
        name = "char-update",
        description = context.getString(R.string.slash_char_update_desc),
        argumentHint = context.getString(R.string.slash_char_update_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.UPDATE_CHAR,
        params = listOf(
            SlashParam("字段=值", "可多个同时更新：name/description/personality/scenario/systemPrompt/firstMessage/messageExamples/creatorNotes/postHistoryInstructions/characterVersion/creator/tags；值含空格用引号", "/char-update description=新的描述 name=新的名字"),
            SlashParam("tags=", "标签，多个用逗号分隔", "/char-update tags=战士,沉默寡言"),
        ),
        examples = listOf(
            SlashExample("/char-update name=女仆长", "修改角色名字"),
            SlashExample("/char-update firstMessage=早上好，我是来报到的冒险者", "修改开场白"),
            SlashExample("/char-update name=A description=B tags=战士", "同时更新多个字段"),
        ),
    ),
    SlashCommand(
        name = "char-duplicate",
        description = context.getString(R.string.slash_char_duplicate_desc),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.DUPLICATE,
    ),
    SlashCommand(
        name = "rename-char",
        description = context.getString(R.string.slash_rename_char_desc),
        argumentHint = context.getString(R.string.slash_rename_char_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.RENAME,
        params = listOf(
            SlashParam("新名字", "要改成的名字", "/rename-char 女仆长"),
        ),
        examples = listOf(
            SlashExample("/rename-char 女仆长", "把当前角色改名为「女仆长」"),
        ),
    ),
    SlashCommand(
        name = "continue",
        description = context.getString(R.string.slash_continue_desc),
        argumentHint = context.getString(R.string.slash_continue_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.CONTINUE,
        params = listOf(
            SlashParam("追加文本", "续写前追加的内容（可选，不填直接续写）", "/continue 然后她转头看向窗外"),
        ),
        examples = listOf(
            SlashExample("/continue", "让 AI 接着最后一条回复继续写"),
            SlashExample("/continue 然后她转头看向窗外", "在追加内容后继续写"),
        ),
    ),
    SlashCommand(
        name = "impersonate",
        description = context.getString(R.string.slash_impersonate_desc),
        argumentHint = context.getString(R.string.slash_impersonate_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.IMPERSONATE,
        params = listOf(
            SlashParam("提示词", "引导 AI 说出的话（可选）", "/impersonate 你会怎样回应这段挑衅"),
        ),
        examples = listOf(
            SlashExample("/impersonate", "让 AI 站在你的视角生成下一句话，填进输入框"),
            SlashExample("/impersonate 你会怎样回应这段挑衅", "带引导地生成你的发言"),
        ),
    ),
    SlashCommand(
        name = "gen",
        description = context.getString(R.string.slash_gen_desc),
        argumentHint = context.getString(R.string.slash_gen_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.GEN,
        params = listOf(
            SlashParam("trim=", "true 替换输入框内容；false（默认）追加到末尾", "/gen trim=true 写一首诗"),
            SlashParam("as=", "char 以角色视角生成（结合 name= 选角色）；默认系统视角", "/gen as=char name=宁凝 她会怎么回应"),
            SlashParam("length=", "生成 token 上限（临时覆盖模型设置）", "/gen length=200 写一段战斗描写"),
            SlashParam("name=", "as=char 时按角色名选卡", "/gen name=宁凝 宁凝看到星空会说"),
        ),
        examples = listOf(
            SlashExample("/gen 写一首关于星空的诗", "以系统指令生成，结果追加到输入框末尾"),
            SlashExample("/gen trim=true as=char name=宁凝 她对落雪作何感想", "替换输入框，以「宁凝」角色视角生成"),
            SlashExample("/gen length=200 描写一场夜战", "限制 200 token 生成"),
        ),
    ),
    SlashCommand(
        name = "reroll-pick",
        description = context.getString(R.string.slash_reroll_pick_desc),
        argumentHint = context.getString(R.string.slash_reroll_pick_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.REROLL_PICK,
    ),
) + varSlashCommands(context)

/**
 * 官方变量命令家族（对齐 SillyTavern variables.js，仅保留本对话级 7 个）。
 */
fun varSlashCommands(context: Context): List<SlashCommand> = listOf(
    SlashCommand(
        name = "listvar",
        description = context.getString(R.string.slash_listvar_desc),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "setvar",
        description = context.getString(R.string.slash_setvar_desc),
        argumentHint = context.getString(R.string.slash_setvar_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
        params = listOf(
            SlashParam("变量名", "要设置的变量名（也支持 key= 写法）", "/setvar key=好感度 50"),
            SlashParam("值", "变量内容，含空格可用引号", "/setvar name 张三"),
        ),
        examples = listOf(
            SlashExample("/setvar money 100", "设置变量 money 为 100"),
            SlashExample("/setvar key=好感度 50", "用 key= 写法设置（支持含空格/引号）"),
        ),
    ),
    SlashCommand(
        name = "getvar",
        description = context.getString(R.string.slash_getvar_desc),
        argumentHint = context.getString(R.string.slash_getvar_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
        params = listOf(
            SlashParam("变量名", "要读取的变量名（也支持 key= 写法）", "/getvar key=好感度"),
        ),
        examples = listOf(
            SlashExample("/getvar money", "读取变量 money 的值"),
            SlashExample("/getvar key=好感度", "用 key= 写法读取"),
        ),
    ),
    SlashCommand(
        name = "addvar",
        description = context.getString(R.string.slash_addvar_desc),
        argumentHint = context.getString(R.string.slash_addvar_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
        params = listOf(
            SlashParam("变量名", "要相加的变量名", "/addvar money 50"),
            SlashParam("数值", "加上的数值（支持小数）", "/addvar 好感度 10.5"),
        ),
        examples = listOf(
            SlashExample("/addvar money 50", "数值相加：100 + 50 = 150"),
            SlashExample("/addvar 好感度 10.5", "支持小数相加"),
        ),
    ),
    SlashCommand(
        name = "incvar",
        description = context.getString(R.string.slash_incvar_desc),
        argumentHint = context.getString(R.string.slash_incvar_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
        params = listOf(
            SlashParam("变量名", "要 +1 的变量名", "/incvar money"),
        ),
        examples = listOf(
            SlashExample("/incvar money", "变量 +1（未设置时从 0 起算）"),
        ),
    ),
    SlashCommand(
        name = "decvar",
        description = context.getString(R.string.slash_decvar_desc),
        argumentHint = context.getString(R.string.slash_decvar_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
        params = listOf(
            SlashParam("变量名", "要 -1 的变量名", "/decvar money"),
        ),
        examples = listOf(
            SlashExample("/decvar money", "变量 -1（未设置时从 0 起算）"),
        ),
    ),
    SlashCommand(
        name = "flushvar",
        description = context.getString(R.string.slash_flushvar_desc),
        argumentHint = context.getString(R.string.slash_flushvar_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
        params = listOf(
            SlashParam("变量名", "要删除的变量名", "/flushvar money"),
        ),
        examples = listOf(
            SlashExample("/flushvar money", "删除变量 money"),
        ),
    ),
)

/**
 * 从已启用的 skills 中收集所有 commands 和 user-invocable skills 作为斜杠命令
 */
fun collectSlashCommands(
    enabledSkills: List<me.rerere.rikkahub.data.files.SkillMetadata>,
    context: Context,
): List<SlashCommand> {
    val commands = enabledSkills.flatMap { skill ->
        skill.commands.map { cmd ->
            SlashCommand(
                name = cmd.name,
                description = cmd.description,
                allowedTools = cmd.allowedTools,
                argumentHint = cmd.argumentHint,
                disableModelInvocation = cmd.disableModelInvocation,
                content = cmd.content,
                filePath = cmd.filePath,
            )
        }
    }
    // 同时收集 user-invocable 的 skill 本身作为斜杠命令
    val invocableSkills = enabledSkills.filter { it.userInvocable }.map { skill ->
        SlashCommand(
            name = skill.name,
            description = skill.description,
            allowedTools = skill.allowedTools,
            argumentHint = "",
            disableModelInvocation = false,
            content = skill.name, // 触发时用 skill name 匹配 auto-trigger
            filePath = "SKILL.md (${skill.name})",
        )
    }
    return commands + invocableSkills + builtinSlashCommands(context)
}

/**
 * 匹配用户输入的斜杠命令
 */
fun matchSlashCommand(
    input: String,
    commands: List<SlashCommand>,
): SlashCommand? {
    val trimmed = input.trimStart()
    if (!trimmed.startsWith("/")) return null
    val name = trimmed.substring(1).split(" ").first().lowercase()
    return commands.find { it.name.lowercase() == name }
}
