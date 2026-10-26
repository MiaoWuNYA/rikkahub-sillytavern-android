package me.rerere.rikkahub.data.ai.tools.phone

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
import me.rerere.rikkahub.service.phone.PhoneActionRequest
import me.rerere.rikkahub.service.phone.PhoneControlException
import me.rerere.rikkahub.service.phone.PhoneControlHub
import me.rerere.rikkahub.service.phone.PhoneError
import me.rerere.rikkahub.service.phone.PhoneScreenSnapshot

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

private fun screenPayload(snapshot: PhoneScreenSnapshot, step: Int, limit: Int): String = buildString {
    append(snapshot.render())
    append("\nstep=").append(step).append('/').append(limit)
}

private fun errorText(error: PhoneError, message: String): String =
    "ok=false err=${error.code} msg=$message"

private fun toolResult(text: String): List<UIMessagePart> = listOf(UIMessagePart.Text(text))

fun buildPhoneScreenTool(): Tool = Tool(
    name = "phone_screen",
    description = "Read the words currently visible on the phone screen outside this app. " +
        "Returns a numbered list where each line is `index|role|text`; role is one char: " +
        "t=text, b=tappable, i=input box, c=checkable, s=scrollable, x=other interactive. " +
        "Use the index with phone_act. Call this first, and again whenever the screen changed " +
        "outside your control.",
    systemPrompt = { _, _ ->
        "Phone control: phone_screen reads the current screen, phone_act taps/types/scrolls it. " +
            "If an app has a direct entry (launch_app / open_url / share), use that instead of navigating by taps."
    },
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("fresh", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Force a fresh read even if the cached screen looks unchanged")
                })
            }
        )
    },
    execute = { args ->
        val fresh = args.jsonObjectBoolean("fresh")
        try {
            val snapshot = PhoneControlHub.readScreen(force = fresh)
            val state = PhoneControlHub.state.value
            toolResult(screenPayload(snapshot, state.step, state.stepLimit))
        } catch (e: PhoneControlException) {
            toolResult(errorText(e.error, e.message.orEmpty()))
        } catch (e: Exception) {
            toolResult(errorText(PhoneError.NO_WINDOW, e.message ?: "读屏失败"))
        }
    }
)

fun buildPhoneActTool(): Tool = Tool(
    name = "phone_act",
    description = "Act on the phone screen read by phone_screen. Actions: " +
        "tap / long_press (target = index or visible text), input (target + text), " +
        "scroll (direction up|down), back, wait. " +
        "Returns ok=true/false with err code and the new screen. " +
        "After acting, trust the returned screen instead of calling phone_screen again. " +
        "If err=STALE the indexes are invalid: call phone_screen. " +
        "If err=NOT_FOUND the text is absent: scroll or pick from the returned candidates. " +
        "If err=PAUSED the user stopped the task; do not retry.",
    systemPrompt = { _, _ ->
        "Phone control rules: never guess coordinates, never retry a PAUSED or BLOCKED package, " +
            "one action per call, and read the returned screen to decide the next step."
    },
    needsApproval = { args -> phoneActNeedsApproval(args as? JsonObject) },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("tap"); add("long_press"); add("input"); add("scroll"); add("back"); add("wait")
                    })
                    put("description", "tap | long_press | input | scroll | back | wait")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Index from phone_screen (e.g. \"4\") or the visible text of the item")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to type for action=input")
                })
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("down"); add("up") })
                    put("description", "Scroll direction, default down")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val obj = args as? JsonObject ?: return@Tool toolResult(errorText(PhoneError.BAD_ARGS, "参数必须是对象"))
        val action = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (action.isBlank()) return@Tool toolResult(errorText(PhoneError.BAD_ARGS, "缺少 action"))
        try {
            val result = PhoneControlHub.perform(
                PhoneActionRequest(
                    action = action,
                    target = obj["target"]?.jsonPrimitive?.contentOrNull,
                    text = obj["text"]?.jsonPrimitive?.contentOrNull,
                    direction = obj["direction"]?.jsonPrimitive?.contentOrNull,
                )
            )
            val state = PhoneControlHub.state.value
            val head = "ok=true act=$action changed=${result.changed} step=${state.step}/${state.stepLimit} msg=${result.message}"
            val screen = result.screen
            if (screen == null) toolResult(head) else toolResult(head + "\n" + screen.render())
        } catch (e: PhoneControlException) {
            toolResult(errorText(e.error, e.message.orEmpty()))
        } catch (e: Exception) {
            toolResult(errorText(PhoneError.ACTION_FAILED, e.message ?: "动作失败"))
        }
    }
)

fun buildPhoneFindTool(): Tool = Tool(
    name = "phone_find",
    description = "Search the current screen for visible text and return matching indexes (max 5). " +
        "Use it when you know the label but not where it is. Call phone_screen first.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to look for on the current screen")
                })
            },
            required = listOf("query")
        )
    },
    execute = { args ->
        val query = args.jsonObjectString("query")
        if (query.isNullOrBlank()) return@Tool toolResult(errorText(PhoneError.BAD_ARGS, "缺少 query"))
        val snapshot = runCatching { PhoneControlHub.readScreen() }.getOrNull()
            ?: return@Tool toolResult(errorText(PhoneError.NO_SERVICE, "无障碍服务未开启或当前没有可读窗口"))
        val matches = snapshot.nodes.filter { it.text.contains(query, ignoreCase = true) }
        if (matches.isEmpty()) {
            val hint = snapshot.nodes.take(5).joinToString("；") { "${it.index}|${it.text.take(16)}" }
            return@Tool toolResult(errorText(PhoneError.NOT_FOUND, "屏幕没有「$query」，当前前几项：$hint"))
        }
        val body = matches.take(5).joinToString("\n") { "${it.index}|${it.role.code}|${it.text}" }
        toolResult("ok=true count=${matches.size}\n$body")
    }
)

fun buildPhoneTools(): List<Tool> = listOf(buildPhoneScreenTool(), buildPhoneActTool(), buildPhoneFindTool())

private fun kotlinx.serialization.json.JsonElement.jsonObjectBoolean(key: String): Boolean =
    runCatching { (this as JsonObject)[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull() == true

private fun kotlinx.serialization.json.JsonElement.jsonObjectString(key: String): String? =
    runCatching { (this as JsonObject)[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
