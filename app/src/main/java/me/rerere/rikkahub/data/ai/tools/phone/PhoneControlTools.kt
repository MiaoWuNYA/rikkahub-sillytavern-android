package me.rerere.rikkahub.data.ai.tools.phone

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.phone.PhoneControlException
import me.rerere.rikkahub.service.phone.PhoneControlHub
import me.rerere.rikkahub.service.phone.PhoneError
import me.rerere.rikkahub.service.phone.PhoneFormatted

/** 敏感动作词：命中即每次停下来问用户，模型无权跳过 */
private val SENSITIVE_KEYWORDS = listOf(
    "支付", "付款", "下单", "购买", "确认支付", "立即购买", "转账", "红包", "充值",
    "删除", "清空", "卸载", "注销", "解绑", "退出登录", "发送", "指纹", "锁屏密码", "恢复出厂",
    "pay", "checkout", "delete", "uninstall", "transfer",
)

/** 纯函数便于单测：只有会改变外部状态的动作才需要审批 */
internal fun phoneActNeedsApproval(args: JsonObject?): Boolean {
    if (args == null) return false
    val haystack = listOfNotNull(
        args["target"]?.jsonPrimitive?.contentOrNull,
        args["text"]?.jsonPrimitive?.contentOrNull,
        args["action"]?.jsonPrimitive?.contentOrNull,
    ).joinToString(" ").lowercase()
    if (haystack.isBlank()) return false
    return SENSITIVE_KEYWORDS.any { haystack.contains(it) }
}

private fun toolResult(text: String): List<UIMessagePart> = listOf(UIMessagePart.Text(text))

private fun okText(message: String, screen: PhoneFormatted?, step: Int, limit: Int): String = buildString {
    append("ok=true ").append(message).append(" step=").append(step).append('/').append(limit)
    screen?.let { append('\n').append(it.text) }
}

private fun errText(error: PhoneError, message: String): String = "ok=false err=${error.code} msg=$message"

private suspend fun runTool(block: suspend () -> List<UIMessagePart>): List<UIMessagePart> = try {
    block()
} catch (e: PhoneControlException) {
    toolResult(errText(e.error, e.message.orEmpty()))
} catch (e: Exception) {
    toolResult(errText(PhoneError.ACTION_FAILED, e.message ?: "手机控制失败"))
}

private fun stepText(): Pair<Int, Int> {
    val state = PhoneControlHub.state.value
    return state.step to state.stepLimit
}

fun buildPhoneScreenTool(): Tool = Tool(
    name = "phone_screen",
    description = "Read the current phone screen outside this app. Each line is one element: " +
        "[id] role label - states, ids valid only for this screen. Zones are top/middle/bottom, lists nest. " +
        "A warning mark means a sensitive target that needs the user's confirmation. " +
        "Call it first and after any change you did not cause.",
    systemPrompt = { _, _ ->
        "Phone control: phone_screen reads, phone_tap / phone_type / phone_scroll / phone_key act. " +
            "Prefer phone_open and phone_apps over navigating by taps."
    },
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("detail", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also append element centre coordinates")
                })
            },
        )
    },
    execute = { args ->
        runTool {
            val screen = PhoneControlHub.screen(args.jsonBool("detail"))
            val (step, limit) = stepText()
            toolResult(okText("pkg=${screen.appLabel}", screen, step, limit))
        }
    },
)

fun buildPhoneShotTool(): Tool = Tool(
    name = "phone_shot",
    description = "Take a real screenshot of the phone screen and show it to yourself. " +
        "Use it when phone_screen returns almost no text (games, canvas UIs), then act with phone_tap_xy.",
    needsApproval = { false },
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        runTool {
            val file = PhoneControlHub.screenshot()
            val screen = runCatching { PhoneControlHub.screen(detail = false) }.getOrNull()
            val (step, limit) = stepText()
            listOf(
                UIMessagePart.Image(url = file.absolutePath),
                UIMessagePart.Text(okText("shot=${file.name}", screen, step, limit)),
            )
        }
    },
)

fun buildPhoneTapTool(): Tool = Tool(
    name = "phone_tap",
    description = "Tap element [id] from the last phone_screen. long=true for a long press. " +
        "The element is re-verified before tapping: err=STALE means the screen changed, read it again. " +
        "err=NEED_CONFIRM means the label is sensitive (send/pay/delete/logout): ask the user, do not retry.",
    needsApproval = { args -> phoneActNeedsApproval(args as? JsonObject) },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Element id from the last phone_screen")
                })
                put("long", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Long press instead of tap")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional label of the target, only used by the approval check")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        runTool {
            val id = args.jsonInt("id")
            if (id == null) {
                toolResult(errText(PhoneError.BAD_ARGS, "缺少 id"))
            } else {
                val result = PhoneControlHub.tap(id, args.jsonBool("long"))
                val (step, limit) = stepText()
                toolResult(okText(result.message, result.screen, step, limit))
            }
        }
    },
)

fun buildPhoneTypeTool(): Tool = Tool(
    name = "phone_type",
    description = "Replace the text of input [id] from the last phone_screen and return the new screen. " +
        "submit=true presses the keyboard action key (search/send/done). " +
        "err=SECRET means a password or verification-code field: hand it to the user.",
    needsApproval = { args -> phoneActNeedsApproval(args as? JsonObject) },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Input element id from the last phone_screen")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to type, replaces the current content")
                })
                put("submit", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Press the keyboard action key after typing")
                })
            },
            required = listOf("id", "text"),
        )
    },
    execute = { args ->
        runTool {
            val id = args.jsonInt("id")
            val text = args.jsonStr("text")
            if (id == null || text == null) {
                toolResult(errText(PhoneError.BAD_ARGS, "缺少 id 或 text"))
            } else {
                val result = PhoneControlHub.type(id, text, args.jsonBool("submit"))
                val (step, limit) = stepText()
                toolResult(okText(result.message, result.screen, step, limit))
            }
        }
    },
)

fun buildPhoneScrollTool(): Tool = Tool(
    name = "phone_scroll",
    description = "Scroll the screen. id is the list element to scroll; omit it to scroll the main page.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("up")
                        add("down")
                        add("left")
                        add("right")
                    })
                    put("description", "Scroll direction")
                })
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "List element id, optional")
                })
            },
            required = listOf("direction"),
        )
    },
    execute = { args ->
        runTool {
            val result = PhoneControlHub.scroll(args.jsonStr("direction") ?: "down", args.jsonInt("id"))
            val (step, limit) = stepText()
            toolResult(okText(result.message, result.screen, step, limit))
        }
    },
)

fun buildPhoneKeyTool(): Tool = Tool(
    name = "phone_key",
    description = "Press a system key: back / home / recents / notifications.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("back")
                        add("home")
                        add("recents")
                        add("notifications")
                    })
                    put("description", "System key")
                })
            },
            required = listOf("key"),
        )
    },
    execute = { args ->
        runTool {
            val key = args.jsonStr("key")
            if (key == null) {
                toolResult(errText(PhoneError.BAD_ARGS, "缺少 key"))
            } else {
                val result = PhoneControlHub.key(key)
                val (step, limit) = stepText()
                toolResult(okText(result.message, result.screen, step, limit))
            }
        }
    },
)

fun buildPhoneTapXyTool(): Tool = Tool(
    name = "phone_tap_xy",
    description = "Tap raw pixel coordinates. Use it only when phone_screen cannot find the target, " +
        "typically together with phone_shot. Coordinates are pixels of the screenshot you saw.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "integer")
                    put("description", "X pixel from the screenshot")
                })
                put("y", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y pixel from the screenshot")
                })
            },
            required = listOf("x", "y"),
        )
    },
    execute = { args ->
        runTool {
            val x = args.jsonInt("x")
            val y = args.jsonInt("y")
            if (x == null || y == null) {
                toolResult(errText(PhoneError.BAD_ARGS, "缺少 x 或 y"))
            } else {
                val result = PhoneControlHub.tapAt(x, y)
                val (step, limit) = stepText()
                toolResult(okText(result.message, result.screen, step, limit))
            }
        }
    },
)

fun buildPhoneCollectTool(): Tool = Tool(
    name = "phone_collect",
    description = "Read a long list completely by scrolling page by page and deduplicating. " +
        "id must be a list element from the last phone_screen. Prefer it over many phone_scroll calls.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "List element id")
                })
                put("max_pages", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many pages to scroll, 1-30, default 10")
                })
                put("until", buildJsonObject {
                    put("type", "string")
                    put("description", "Stop once this text shows up")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        runTool {
            val id = args.jsonInt("id")
            if (id == null) {
                toolResult(errText(PhoneError.BAD_ARGS, "缺少 id"))
            } else {
                toolResult(PhoneControlHub.collect(id, args.jsonInt("max_pages") ?: 10, args.jsonStr("until")))
            }
        }
    },
)

fun buildPhoneAppsTool(): Tool = Tool(
    name = "phone_apps",
    description = "List installed apps that can be launched, one per line as label - package. Filter with query.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter by label or package, optional")
                })
            },
        )
    },
    execute = { args ->
        runTool { toolResult(PhoneControlHub.listApps(args.jsonStr("query"))) }
    },
)

fun buildPhoneOpenTool(): Tool = Tool(
    name = "phone_open",
    description = "Open an app (label or package) or a URL / deep link, then read the new screen. " +
        "Prefer this over tapping launcher icons.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("app", buildJsonObject {
                    put("type", "string")
                    put("description", "App label or package name")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL or deep link")
                })
            },
        )
    },
    execute = { args ->
        runTool {
            val app = args.jsonStr("app")
            val url = args.jsonStr("url")
            val result = when {
                !app.isNullOrBlank() -> PhoneControlHub.launch(app)
                !url.isNullOrBlank() -> PhoneControlHub.openUrl(url)
                else -> null
            }
            if (result == null) {
                toolResult(errText(PhoneError.BAD_ARGS, "app 和 url 至少给一个"))
            } else {
                val (step, limit) = stepText()
                toolResult(okText(result.message, result.screen, step, limit))
            }
        }
    },
)

fun buildPhoneTools(): List<Tool> = listOf(
    buildPhoneScreenTool(),
    buildPhoneShotTool(),
    buildPhoneTapTool(),
    buildPhoneTypeTool(),
    buildPhoneScrollTool(),
    buildPhoneKeyTool(),
    buildPhoneTapXyTool(),
    buildPhoneCollectTool(),
    buildPhoneAppsTool(),
    buildPhoneOpenTool(),
)

private fun JsonElement.jsonInt(key: String): Int? =
    runCatching { (this as JsonObject)[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull()

private fun JsonElement.jsonStr(key: String): String? =
    runCatching { (this as JsonObject)[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }.getOrNull()

private fun JsonElement.jsonBool(key: String): Boolean =
    runCatching { (this as JsonObject)[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull() == true
