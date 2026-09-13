package me.rerere.rikkahub.ui.components.message.tools

/**
 * 工具显示名：把内部英文工具名映射为中文雅称，仅用于 UI 展示（工具调用气泡、
 * 生成状态等），不改变发送给模型的实际工具名。
 * 未登记的工具（MCP / 插件等）原样显示原名；MCP 工具（mcp__服务__工具）
 * 会先剥离服务前缀再尝试映射。
 */
private val exactNames = mapOf(
    // ── 记忆 / 对话检索 ──
    "conversation_search" to "探寻搜查",
    "recent_chats" to "溯源观澜",
    "read_history_message" to "回溯拾遗",
    "memory_tool" to "心念归藏",
    "database_query" to "天书检阅",
    // ── 网络 ──
    "search_web" to "天网搜罗",
    "web_search" to "天网搜罗",
    "scrape_web" to "剖页取真",
    "web_fetch" to "摘星引卷",
    "open_url" to "御风遨游",
    "download_file" to "纳卷入库",
    "share" to "传阅千里",
    // ── 计算与代码 ──
    "calculator" to "神机妙算",
    "execute_python" to "灵枢演算",
    "eval_javascript" to "锦囊码算",
    "execute_command" to "乾纲令行",
    // ── 技能 / 系统 ──
    "use_skill" to "秘技启封",
    "present_file" to "献卷呈览",
    "text_to_speech" to "清音雅颂",
    "get_time_info" to "察今知时",
    "clipboard_tool" to "拾简掇墨",
    "clipboard" to "拾简掇墨",
    "ask_user" to "垂询问策",
    "device_toolbox" to "万象百宝囊",
    "post_notification" to "张榜示谕",
    "show_toast" to "浮语掠影",
    "vibrate" to "震机传讯",
    "file" to "书阁司卷",
    // ── 生活陪伴 ──
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
    // ── 任务 ──
    "task_create" to "布局落子",
    "task_get" to "察势观局",
    "task_list" to "阅尽楸枰",
    "task_mgmt" to "运筹帷幄",
    // ── 设备百宝囊 ──
    "control_music" to "掌乐司音",
    "launch_app" to "启阁唤灵",
    "set_alarm" to "晨钟司唤",
    "set_timer" to "定时刻漏",
    "set_torch" to "燃炬照明",
    "set_brightness" to "调光弄影",
    "set_volume" to "调声控量",
    "set_wallpaper" to "换屏易景",
    "get_battery_info" to "察元探能",
    "get_storage_info" to "察仓量廪",
    "get_wifi_info" to "察网通灵",
    "get_location" to "寻踪定域",
    "get_audio_info" to "察音辨声",
    "get_volume" to "察声闻量",
    "get_brightness" to "察光测亮",
    "get_telephony_info" to "察讯通络",
    "read_sensor" to "机枢感测",
    "list_sensors" to "遍察机枢",
    "scan_media" to "摄录巡检",
    "open_file" to "开函展卷",
    "read_sms" to "启缄阅信",
    "list_contacts" to "阅名册通讯",
    "search_contacts" to "名册寻人",
    "list_call_log" to "阅往来电录",
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
    // MCP 工具名为 mcp__服务名__工具名：剥离服务前缀后用工具本名再查一次映射
    if (toolName.startsWith("mcp__")) {
        val raw = toolName.substringAfter("mcp__", "").substringAfter("__", "")
        if (raw.isNotEmpty()) {
            exactNames[raw]?.let { return it }
            prefixNames.forEach { (prefix, label) ->
                if (label.isNotEmpty() && raw.startsWith(prefix)) return label
            }
            return raw
        }
        return toolName
    }
    prefixNames.forEach { (prefix, label) ->
        if (label.isNotEmpty() && toolName.startsWith(prefix)) return label
    }
    return toolName
}
