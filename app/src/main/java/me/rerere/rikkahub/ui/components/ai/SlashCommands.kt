package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.files.CommandFile

/**
 * 斜杠命令 — 来自 skill 的 commands 目录下的 .md 文件
 * Claude Code 风格：每个 commands/xxx.md 自动变成 /xxx 命令
 */
data class SlashCommand(
    val name: String,                    // 命令名，如 "code-review"
    val description: String,             // 简短描述
    val allowedTools: List<String>,      // 允许的工具
    val argumentHint: String = "",       // 参数提示，如 "[project-name]"
    val disableModelInvocation: Boolean = false, // 纯脚本不调模型
    val content: String,                 // 指令正文
    val filePath: String,                // 源文件路径，如 "commands/code-review.md"
    val builtinKind: BuiltinSlashKind? = null, // 内置命令类型（非空时为官方风格内置命令）
)

/**
 * 内置斜杠命令类型（对齐酒馆官方常用命令，全局可用）
 */
enum class BuiltinSlashKind {
    /** 文本处理：处理参数后填入输入框 */
    TEXT,

    /** 信息展示：Toast 显示结果 */
    INFO,

    /** 角色操作：修改当前助手 */
    RENAME,

    /** 生成操作：重新生成（需要页面回调） */
    REGENERATE,
}

/**
 * 内置命令 — 官方 SillyTavern 常用命令的实用子集，全局可用
 */
fun builtinSlashCommands(): List<SlashCommand> = listOf(
    SlashCommand(
        name = "echo",
        description = "原样输出参数(Echo)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "setinput",
        description = "设置输入框内容(Set Input)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "lower",
        description = "转小写(Lowercase)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "upper",
        description = "转大写(Uppercase)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "trimstart",
        description = "去掉开头空白(Trim Start)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "trimend",
        description = "去掉结尾空白(Trim End)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "substr",
        description = "截取子串(Substring)",
        argumentHint = "[起始] [长度] [文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "tokens",
        description = "估算Token数(Tokens)",
        argumentHint = "[文本]",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.TEXT,
    ),
    SlashCommand(
        name = "model",
        description = "查看当前模型(Model)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INFO,
    ),
    SlashCommand(
        name = "char-get",
        description = "查看当前角色卡(Char Get)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.INFO,
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
        name = "regenerate",
        description = "重新生成最后一条回复(Regenerate)",
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.REGENERATE,
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
