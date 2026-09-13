package me.rerere.rikkahub.ui.components.message.tools

/**
 * 工具显示名：把内部英文工具名映射为中文雅称，仅用于 UI 展示（工具调用气泡、
 * 生成状态等），不改变发送给模型的实际工具名。
 * 未登记的工具（MCP / 插件等）原样显示原名。
 */
private val exactNames = mapOf(
    "conversation_search" to "探寻搜查",
    "recent_chats" to "溯源观澜",
    "read_history_message" to "回溯拾遗",
    "search_web" to "天网搜罗",
    "scrape_web" to "剖页取真",
    "web_fetch" to "摘星引卷",
    "web_search" to "天网搜罗",
    "calculator" to "神机妙算",
    "execute_python" to "灵枢演算",
    "eval_javascript" to "锦囊码算",
    "execute_command" to "乾纲令行",
    "memory_tool" to "心念归藏",
    "use_skill" to "秘技启封",
    "database_query" to "天书检阅",
    "present_file" to "献卷呈览",
    "text_to_speech" to "清音雅颂",
    "get_time_info" to "察今知时",
    "clipboard_tool" to "拾简掇墨",
    "clipboard" to "拾简掇墨",
    "ask_user" to "垂询问策",
    "device_toolbox" to "万象百宝囊",
    "life_calendar" to "岁时录",
    "life_memo" to "手札杂记",
    "shared_reading" to "共读同赏",
    "shared_music" to "弦歌共聆",
    "shared_diary" to "双鲤日记",
    "anniversary_book" to "节庆铭记",
    "read_couple_space" to "观俪影轩",
    "post_couple_space" to "题俪影轩",
    "comment_couple_space" to "附骥留评",
    "delete_couple_space_post" to "拭去旧题",
    "task_create" to "布局落子",
    "task_get" to "察势观局",
    "task_list" to "阅尽楸枰",
    "task_mgmt" to "运筹帷幄",
)

private val prefixNames = mapOf(
    "file_write" to "布墨成书",
    "file_read" to "启卷览文",
    "file_list" to "遍历书阁",
    "file_copy" to "副本留影",
    "file_move" to "移笥归档",
    "file_mkdir" to "辟阁立阁",
    "file_delete" to "焚简毁牍",
    "file_search" to "遍检千卷",
    "workspace_" to "书斋运筹",
    "memory_" to "心念归藏",
    "task_" to "运筹帷幄",
)

fun toolDisplayName(toolName: String): String {
    exactNames[toolName]?.let { return it }
    prefixNames.forEach { (prefix, label) ->
        if (toolName.startsWith(prefix)) return label
    }
    return toolName
}
