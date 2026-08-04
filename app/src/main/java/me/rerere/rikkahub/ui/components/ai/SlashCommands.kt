package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.files.CommandFile

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

    /** 以助手身份发言（官方 sendas 的本地简化），不触发生成 */
    SENDAS,

    /** 切换用户人设 */
    PERSONA,

    /** 触发生成：不添加新消息，直接让 AI 回复一次 */
    TRIGGER,

    /** 生成系统旁白：按提示词让 AI 写一条系统消息并插入 */
    SYSGEN,

    /** 注入提示词：把文本注入当前对话的 LLM 提示词 */
    INJECT,

    /** 变量命令：设置/读取/增删宏变量（官方变量家族） */
    VAR,

    /** 角色操作：修改当前助手 */
    RENAME,

    /** 续写上一条助手回复（官方 /continue） */
    CONTINUE,

    /** 以角色身份生成一条回复（官方 /impersonate） */
    IMPERSONATE,

    /** 查看当前发送给 AI 的提示词上下文 */
    PROMPT,

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
fun builtinSlashCommands(): List<SlashCommand> = listOf(
    SlashCommand(
        name = "help",
        description = "显示可用命令(Help)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.HELP,
    ),
    SlashCommand(
        name = "sys",
        description = "插入系统消息(Sys)：直接写入一条系统消息，不触发生成",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SYS,
    ),
    SlashCommand(
        name = "sendas",
        description = "插入助手消息(Send As)：直接写入，不触发生成",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SENDAS,
    ),
    SlashCommand(
        name = "persona",
        description = "切换用户人设(Persona)",
        argumentHint = "[人设名，可选]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.PERSONA,
    ),
    SlashCommand(
        name = "trigger",
        description = "直接触发AI回复(Trigger)：不添加新消息，让 AI 回复一次",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TRIGGER,
    ),
    SlashCommand(
        name = "sysgen",
        description = "AI生成系统旁白(Sysgen)：生成后插入聊天，不触发普通回复",
        argumentHint = "[提示词] 如 描写雨夜街道",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SYSGEN,
    ),
    SlashCommand(
        name = "inject",
        description = "注入提示词(Inject)：写入发送给AI的提示词，不进入聊天记录；默认 after·深度4·system",
        argumentHint = "[文本] [position=before|after|chat] [depth=数字] [role=user|assistant|system]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INJECT,
    ),
    SlashCommand(
        name = "char-get",
        description = "查看当前角色卡(Char Get)：提示显示角色卡摘要",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INFO,
    ),
    SlashCommand(
        name = "char-update",
        description = "修改角色卡字段(Char Update)：可用字段 name/description/personality/scenario/system_prompt/first_mes/mes_example/phi/creator_notes",
        argumentHint = "[字段=值] 如 description=新描述",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.UPDATE_CHAR,
    ),
    SlashCommand(
        name = "char-duplicate",
        description = "复制当前角色卡(Char Duplicate)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.DUPLICATE,
    ),
    SlashCommand(
        name = "rename-char",
        description = "重命名角色(Rename Char)",
        argumentHint = "[新名字]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.RENAME,
    ),
    SlashCommand(
        name = "continue",
        description = "续写最后一条助手回复(Continue)：在原回复末尾继续生成，可加补充文本",
        argumentHint = "[补充文本，可选]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.CONTINUE,
    ),
    SlashCommand(
        name = "impersonate",
        description = "以角色身份生成回复(Impersonate)：AI 以角色身份新写一条，可加开头文本由 AI 接写",
        argumentHint = "[开头文本，可选]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.IMPERSONATE,
    ),
    SlashCommand(
        name = "prompt",
        description = "查看发送给AI的提示词(Prompt)：弹窗预览实际发送的提示词",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.PROMPT,
    ),
    SlashCommand(
        name = "reroll-pick",
        description = "重新掷随机细节(Reroll Pick)：让 {{pick}} 换一批结果，可带种子数字",
        argumentHint = "[种子，可选]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.REROLL_PICK,
    ),
) + varSlashCommands()

/**
 * 官方变量命令家族（对齐 SillyTavern variables.js，仅保留本对话级 7 个）。
 */
fun varSlashCommands(): List<SlashCommand> = listOf(
    SlashCommand(
        name = "listvar",
        description = "列出当前对话变量(List Var)：提示显示本对话全部变量",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "setvar",
        description = "设置当前对话变量(Set Var)：宏里用 {{getvar::名}} 或 {{.名}} 读取",
        argumentHint = "[变量名] [值]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "getvar",
        description = "读取当前对话变量(Get Var)：提示显示当前值",
        argumentHint = "[变量名]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "addvar",
        description = "当前对话变量加值(Add Var)：数值相加，非数值拼接",
        argumentHint = "[变量名] [值]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "incvar",
        description = "当前对话变量+1(Inc Var)",
        argumentHint = "[变量名]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "decvar",
        description = "当前对话变量-1(Dec Var)",
        argumentHint = "[变量名]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "flushvar",
        description = "删除当前对话变量(Flush Var)",
        argumentHint = "[变量名]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
)

/**
 * 从已启用的 skills 中收集所有 commands 和 user-invocable skills 作为斜杠命令
 */
fun collectSlashCommands(
    enabledSkills: List<me.rerere.rikkahub.data.files.SkillMetadata>,
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
    return commands + invocableSkills + builtinSlashCommands()
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
