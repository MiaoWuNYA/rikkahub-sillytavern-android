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

/** 单个命名参数的说明与例句（全部走本地化资源） */
data class SlashParam(
    val nameRes: Int,        // 参数名，如 "name="
    val descRes: Int,        // 说明文字
    val exampleRes: Int,     // 例句（含参数用法）
)

/** 完整命令例句与效果说明（全部走本地化资源） */
data class SlashExample(
    val commandRes: Int,  // 完整命令，如 "/sys name=旁白 雨夜"
    val descRes: Int,     // 效果说明
)

/**
 * 内置斜杠命令类型（对齐酒馆官方常用命令，全局可用）
 */
enum class BuiltinSlashKind {
    /** 帮助：列出内置命令 */
    HELP,

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
            SlashParam(R.string.slash_param_sys_name_name, R.string.slash_param_sys_name_desc, R.string.slash_param_sys_name_example),
            SlashParam(R.string.slash_param_sys_at_name, R.string.slash_param_sys_at_desc, R.string.slash_param_sys_at_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_sys_1_cmd, R.string.slash_example_sys_1_desc),
            SlashExample(R.string.slash_example_sys_2_cmd, R.string.slash_example_sys_2_desc),
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
            SlashParam(R.string.slash_param_sendas_name_name, R.string.slash_param_sendas_name_desc, R.string.slash_param_sendas_name_example),
            SlashParam(R.string.slash_param_sendas_at_name, R.string.slash_param_sendas_at_desc, R.string.slash_param_sendas_at_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_sendas_1_cmd, R.string.slash_example_sendas_1_desc),
            SlashExample(R.string.slash_example_sendas_2_cmd, R.string.slash_example_sendas_2_desc),
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
            SlashParam(R.string.slash_param_send_name_name, R.string.slash_param_send_name_desc, R.string.slash_param_send_name_example),
            SlashParam(R.string.slash_param_send_at_name, R.string.slash_param_send_at_desc, R.string.slash_param_send_at_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_send_1_cmd, R.string.slash_example_send_1_desc),
            SlashExample(R.string.slash_example_send_2_cmd, R.string.slash_example_send_2_desc),
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
            SlashParam(R.string.slash_param_persona_mode_name, R.string.slash_param_persona_mode_desc, R.string.slash_param_persona_mode_example),
            SlashParam(R.string.slash_param_persona_off_name, R.string.slash_param_persona_off_desc, R.string.slash_param_persona_off_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_persona_1_cmd, R.string.slash_example_persona_1_desc),
            SlashExample(R.string.slash_example_persona_2_cmd, R.string.slash_example_persona_2_desc),
            SlashExample(R.string.slash_example_persona_3_cmd, R.string.slash_example_persona_3_desc),
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
            SlashParam(R.string.slash_param_sysgen_name_name, R.string.slash_param_sysgen_name_desc, R.string.slash_param_sysgen_name_example),
            SlashParam(R.string.slash_param_sysgen_at_name, R.string.slash_param_sysgen_at_desc, R.string.slash_param_sysgen_at_example),
            SlashParam(R.string.slash_param_sysgen_trim_name, R.string.slash_param_sysgen_trim_desc, R.string.slash_param_sysgen_trim_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_sysgen_1_cmd, R.string.slash_example_sysgen_1_desc),
            SlashExample(R.string.slash_example_sysgen_2_cmd, R.string.slash_example_sysgen_2_desc),
        ),
    ),
    SlashCommand(
        name = "char-update",
        description = context.getString(R.string.slash_char_update_desc),
        argumentHint = context.getString(R.string.slash_char_update_hint),
        content = "",
        filePath = "builtin",
        builtinKind = BuiltinSlashKind.UPDATE_CHAR,
        params = listOf(
            SlashParam(R.string.slash_param_char_update_field_name, R.string.slash_param_char_update_field_desc, R.string.slash_param_char_update_field_example),
            SlashParam(R.string.slash_param_char_update_tags_name, R.string.slash_param_char_update_tags_desc, R.string.slash_param_char_update_tags_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_char_update_1_cmd, R.string.slash_example_char_update_1_desc),
            SlashExample(R.string.slash_example_char_update_2_cmd, R.string.slash_example_char_update_2_desc),
            SlashExample(R.string.slash_example_char_update_3_cmd, R.string.slash_example_char_update_3_desc),
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
            SlashParam(R.string.slash_param_rename_char_name_name, R.string.slash_param_rename_char_name_desc, R.string.slash_param_rename_char_name_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_rename_char_1_cmd, R.string.slash_example_rename_char_1_desc),
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
            SlashParam(R.string.slash_param_continue_text_name, R.string.slash_param_continue_text_desc, R.string.slash_param_continue_text_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_continue_1_cmd, R.string.slash_example_continue_1_desc),
            SlashExample(R.string.slash_example_continue_2_cmd, R.string.slash_example_continue_2_desc),
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
            SlashParam(R.string.slash_param_impersonate_prompt_name, R.string.slash_param_impersonate_prompt_desc, R.string.slash_param_impersonate_prompt_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_impersonate_1_cmd, R.string.slash_example_impersonate_1_desc),
            SlashExample(R.string.slash_example_impersonate_2_cmd, R.string.slash_example_impersonate_2_desc),
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
            SlashParam(R.string.slash_param_gen_trim_name, R.string.slash_param_gen_trim_desc, R.string.slash_param_gen_trim_example),
            SlashParam(R.string.slash_param_gen_as_name, R.string.slash_param_gen_as_desc, R.string.slash_param_gen_as_example),
            SlashParam(R.string.slash_param_gen_length_name, R.string.slash_param_gen_length_desc, R.string.slash_param_gen_length_example),
            SlashParam(R.string.slash_param_gen_name_name, R.string.slash_param_gen_name_desc, R.string.slash_param_gen_name_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_gen_1_cmd, R.string.slash_example_gen_1_desc),
            SlashExample(R.string.slash_example_gen_2_cmd, R.string.slash_example_gen_2_desc),
            SlashExample(R.string.slash_example_gen_3_cmd, R.string.slash_example_gen_3_desc),
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
            SlashParam(R.string.slash_param_setvar_name_name, R.string.slash_param_setvar_name_desc, R.string.slash_param_setvar_name_example),
            SlashParam(R.string.slash_param_setvar_value_name, R.string.slash_param_setvar_value_desc, R.string.slash_param_setvar_value_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_setvar_1_cmd, R.string.slash_example_setvar_1_desc),
            SlashExample(R.string.slash_example_setvar_2_cmd, R.string.slash_example_setvar_2_desc),
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
            SlashParam(R.string.slash_param_getvar_name_name, R.string.slash_param_getvar_name_desc, R.string.slash_param_getvar_name_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_getvar_1_cmd, R.string.slash_example_getvar_1_desc),
            SlashExample(R.string.slash_example_getvar_2_cmd, R.string.slash_example_getvar_2_desc),
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
            SlashParam(R.string.slash_param_addvar_name_name, R.string.slash_param_addvar_name_desc, R.string.slash_param_addvar_name_example),
            SlashParam(R.string.slash_param_addvar_value_name, R.string.slash_param_addvar_value_desc, R.string.slash_param_addvar_value_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_addvar_1_cmd, R.string.slash_example_addvar_1_desc),
            SlashExample(R.string.slash_example_addvar_2_cmd, R.string.slash_example_addvar_2_desc),
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
            SlashParam(R.string.slash_param_incvar_name_name, R.string.slash_param_incvar_name_desc, R.string.slash_param_incvar_name_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_incvar_1_cmd, R.string.slash_example_incvar_1_desc),
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
            SlashParam(R.string.slash_param_decvar_name_name, R.string.slash_param_decvar_name_desc, R.string.slash_param_decvar_name_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_decvar_1_cmd, R.string.slash_example_decvar_1_desc),
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
            SlashParam(R.string.slash_param_flushvar_name_name, R.string.slash_param_flushvar_name_desc, R.string.slash_param_flushvar_name_example),
        ),
        examples = listOf(
            SlashExample(R.string.slash_example_flushvar_1_cmd, R.string.slash_example_flushvar_1_desc),
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
