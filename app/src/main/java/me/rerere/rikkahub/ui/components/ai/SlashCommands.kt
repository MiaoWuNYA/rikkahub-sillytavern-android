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
        description = "插入系统消息(Sys)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SYS,
    ),
    SlashCommand(
        name = "sendas",
        description = "以助手身份发言(Send As)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SENDAS,
    ),
    SlashCommand(
        name = "persona",
        description = "切换用户人设(Persona)",
        argumentHint = "[人设名]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.PERSONA,
    ),
    SlashCommand(
        name = "trigger",
        description = "无消息直接触发AI回复(Trigger)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TRIGGER,
    ),
    SlashCommand(
        name = "sysgen",
        description = "AI生成系统旁白并插入(Sysgen)",
        argumentHint = "[提示词] 如 描写雨夜街道",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.SYSGEN,
    ),
    SlashCommand(
        name = "inject",
        description = "注入提示词到当前对话(Inject)",
        argumentHint = "[文本] [position=before|chat] [depth=数字] [role=user|assistant]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INJECT,
    ),
    SlashCommand(
        name = "char-get",
        description = "查看当前角色卡(Char Get)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INFO,
    ),
    SlashCommand(
        name = "char-update",
        description = "修改角色卡字段(Char Update)",
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
) + varSlashCommands()

/**
 * 官方变量命令家族（对齐 SillyTavern variables.js，仅保留本对话级 7 个）。
 */
fun varSlashCommands(): List<SlashCommand> = listOf(
    SlashCommand(
        name = "listvar",
        description = "列出变量(List Var)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "setvar",
        description = "设置本对话变量(Set Var)",
        argumentHint = "[key] [值]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "getvar",
        description = "读取本对话变量(Get Var)",
        argumentHint = "[key]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "addvar",
        description = "本对话变量加值(Add Var)",
        argumentHint = "[key] [值]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "incvar",
        description = "本对话变量+1(Inc Var)",
        argumentHint = "[key]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "decvar",
        description = "本对话变量-1(Dec Var)",
        argumentHint = "[key]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.VAR,
    ),
    SlashCommand(
        name = "flushvar",
        description = "删除本对话变量(Flush Var)",
        argumentHint = "[key]",
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
