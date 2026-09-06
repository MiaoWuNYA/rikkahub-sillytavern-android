package me.rerere.rikkahub.data.ai.tools.ynufe

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.time.LocalDate

/**
 * ynufe 教务工具 — 云南财经大学强智教务系统统一入口。
 *
 * 凭据持久化在设置页（YnufeStore，隔离 DataStore），AI 调用时自动复用，
 * 无需每次重新输入学号密码。会话过期时自动静默续期（内置验证码 OCR），
 * OCR 连续失败时返回验证码图片让用户手输。
 *
 * 支持 action:
 *  - status        查询当前登录状态
 *  - login         登录（默认读设置里保存的账号密码；也可显式传 account/password 覆盖）
 *  - logout        退出并清除会话（可选 clear_credentials=true 同时清除凭据）
 *  - schedule      查课表（可选 semester，如 2025-2026-1；默认当前学期）
 *  - grade         查成绩（GPA / 总学分 / 明细）
 *  - exam          查考试安排（可选 semester / type: 期初|期中|期末，默认期末）
 *  - announcement  查教务公告列表
 *  - help          返回使用说明
 */
fun createYnufeTool(context: Context): Tool = Tool(
    name = "ynufe",
    description = "云南财经大学教务系统查询工具（强智教务）。" +
        "用于查询课表(schedule)、成绩(grade)、考试安排(exam)、教务公告(announcement)，" +
        "以及登录状态(status)、登录(login)、退出(logout)。" +
        "账号密码已在设置页保存，无需向用户索要；仅在返回 need_login/need_captcha 时按提示引导用户。" +
        "登录失败返回 captcha_image 时：把图片用 markdown 展示给用户，请用户读出 4 位验证码，" +
        "再调用 ynufe {action:login, captcha:\"<用户输入>\"} 重试。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "操作: status | login | logout | schedule | grade | exam | announcement | help")
                })
                put("account", buildJsonObject {
                    put("type", "string")
                    put("description", "学号（可选，仅在用户明确提供且未保存凭据时传入）")
                })
                put("password", buildJsonObject {
                    put("type", "string")
                    put("description", "密码（可选，仅在用户明确提供且未保存凭据时传入）")
                })
                put("captcha", buildJsonObject {
                    put("type", "string")
                    put("description", "4 位图形验证码（可选；用户手输验证码后登录时传入）")
                })
                put("semester", buildJsonObject {
                    put("type", "string")
                    put("description", "学年学期（可选，如 2025-2026-1；不传默认当前学期）")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "exam 用：期初 | 期中 | 期末（默认期末）")
                })
                put("clear_credentials", buildJsonObject {
                    put("type", "boolean")
                    put("description", "logout 时是否同时清除保存的账号密码（默认 false 只清会话）")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val json = args.jsonObject
        val action = json["action"]?.jsonPrimitive?.contentOrNull
            ?: error("action is required")
        val accountArg = json["account"]?.jsonPrimitive?.contentOrNull
        val passwordArg = json["password"]?.jsonPrimitive?.contentOrNull
        val captchaArg = json["captcha"]?.jsonPrimitive?.contentOrNull
        val semesterArg = json["semester"]?.jsonPrimitive?.contentOrNull
        val typeArg = json["type"]?.jsonPrimitive?.contentOrNull
        val clearCreds = json.boolField("clear_credentials")

        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val store = YnufeStore(context)
            val client = YnufeClient(context)

            when (action) {
                "help" -> helpJson()
                "status" -> statusJson(store, client)
                "login" -> loginFlowJson(store, client, context, accountArg, passwordArg, captchaArg, saveCredentials = true)
                "logout" -> {
                    if (clearCreds) store.clearAll() else store.clearSession()
                    buildJsonObject {
                        put("success", true)
                        put("logged_in", false)
                        put("message", if (clearCreds) "已退出并清除保存的账号密码" else "已退出登录（凭据仍保存在设置中）")
                    }
                }
                "schedule", "grade", "exam", "announcement" ->
                    dataJson(store, client, context, action, semesterArg, typeArg)
                else -> buildJsonObject {
                    put("success", false)
                    put("error", "未知 action: $action")
                    put("allowed", "status | login | logout | schedule | grade | exam | announcement | help")
                }
            }
        }

        listOf(UIMessagePart.Text(result.toString()))
    }
)

// ======================= 内部实现 =======================

private fun JsonObject.boolField(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.content == "true"

/** 根据当前日期推算默认学年学期（如 2025-2026-1）。 */
internal fun defaultSemesterId(): String {
    val now = LocalDate.now()
    val y = now.year
    return when (now.monthValue) {
        in 8..12 -> "$y-${y + 1}-1"
        1 -> "${y - 1}-$y-1"
        else -> "${y - 1}-$y-2"
    }
}

/** 会话是否有效（能正常拉到主框架页即有效；client 会话失效会抛异常）。 */
private suspend fun verifySession(client: YnufeClient): Boolean = try {
    client.getHtml("/jsxsd/framework/xsMain_new.jsp?t1=1")
    true
} catch (e: Exception) {
    false
}

private suspend fun statusJson(store: YnufeStore, client: YnufeClient): JsonObject {
    val auth = store.snapshot()
    val loggedIn = auth.jsessionid.isNotEmpty() && verifySession(client)
    return buildJsonObject {
        put("logged_in", loggedIn)
        put("account", auth.account)
        put("has_credentials", auth.hasCredentials)
        if (!loggedIn) {
            put("need_login", true)
            put(
                "message",
                if (auth.hasCredentials) "会话已过期，凭据已保存，调用 action=login 可自动重新登录"
                else "未登录且设置中未保存账号密码，请引导用户到 设置 → 教务系统账号 填写并保存"
            )
        }
    }
}

/** 数据查询：确保登录（必要时静默续期）→ 拉取对应端点 → 解析为 JSON。 */
private suspend fun dataJson(
    store: YnufeStore,
    client: YnufeClient,
    context: Context,
    action: String,
    semesterArg: String?,
    typeArg: String?,
): JsonObject {
    val auth = store.snapshot()
    if (!verifySession(client)) {
        if (!auth.hasCredentials) {
            return buildJsonObject {
                put("action", action)
                put("success", false)
                put("need_login", true)
                put("message", "未登录且未保存凭据，请引导用户到 设置 → 教务系统账号 保存学号密码，或在对话中提供后调用 action=login")
            }
        }
        val loginResult = loginFlowJson(store, client, context, null, null, null, saveCredentials = false)
        if (!loginResult.boolField("logged_in")) {
            return buildJsonObject {
                put("action", action)
                put("success", false)
                put("need_login", true)
                put("login_result", loginResult)
            }
        }
    }

    val sem = semesterArg?.takeIf { it.isNotBlank() } ?: defaultSemesterId()

    return try {
        val data: JsonObject = when (action) {
            "schedule" -> YnufeParsers.parseTimetableJson(
                client.getHtml("/jsxsd/xskb/xskb_list.do?xnxq01id=${java.net.URLEncoder.encode(sem, "UTF-8")}")
            )
            "grade" -> YnufeParsers.parseGradesJson(
                client.getHtml("/jsxsd/kscj/cjcx_list?xsfs=all")
            )
            "exam" -> {
                val typeLabel = when (typeArg) {
                    "期初", "1" -> "期初"
                    "期中", "2" -> "期中"
                    else -> "期末"
                }
                val code = when (typeLabel) {
                    "期初" -> "1"; "期中" -> "2"; else -> "3"
                }
                val html = client.postForm(
                    "/jsxsd/xsks/xsksap_list",
                    mapOf("xnxqid" to sem, "xqlb" to code, "xqlbmc" to typeLabel),
                )
                buildJsonObject {
                    put("exams", YnufeParsers.parseExamsJson(html, term = sem, typeLabel = typeLabel))
                    put("semester", sem)
                    put("examType", typeLabel)
                }
            }
            "announcement" -> buildJsonObject {
                put("announcements", YnufeParsers.parseAnnouncementsJson(
                    client.getHtml("/jsxsd/ggly/ysgg_query")
                ))
            }
            else -> buildJsonObject { put("error", "unknown action $action") }
        }
        buildJsonObject {
            put("action", action)
            put("success", true)
            data.forEach { (k, v) -> put(k, v) }
        }
    } catch (e: YnufeSessionExpired) {
        buildJsonObject {
            put("action", action)
            put("success", false)
            put("need_login", true)
            put("message", "会话在查询途中失效，请先调用 action=login 重新登录")
        }
    } catch (e: Exception) {
        buildJsonObject {
            put("action", action)
            put("success", false)
            put("error", "查询失败: ${e.message}")
        }
    }
}

/**
 * 登录流程：读保存的凭据（或显式传入的），自动 OCR 验证码登录，最多 3 次。
 * 连续失败时把最后一次验证码图存 cache 并返回 captcha_image 让用户手输。
 */
private suspend fun loginFlowJson(
    store: YnufeStore,
    client: YnufeClient,
    context: Context?,
    accountArg: String?,
    passwordArg: String?,
    manualCaptcha: String?,
    saveCredentials: Boolean,
): JsonObject {
    val auth = store.snapshot()
    val account = accountArg ?: auth.account
    val obfuscator = context?.let { CredentialObfuscator(it) }
    val password = passwordArg ?: obfuscator?.deobfuscate(auth.passwordObf).orEmpty()

    if (account.isEmpty() || password.isEmpty()) {
        return buildJsonObject {
            put("success", false)
            put("need_login", true)
            put("has_credentials", auth.hasCredentials)
            put("message", "缺少学号或密码。请引导用户到 设置 → 教务系统账号 保存，或在对话中提供后传入 account/password 参数")
        }
    }

    // 用户手输了验证码：直接试一次
    if (!manualCaptcha.isNullOrBlank()) {
        return doLogin(client, store, obfuscator, account, password, manualCaptcha.trim(), saveCredentials)
    }

    var lastCaptchaBytes: ByteArray? = null
    for (attempt in 1..3) {
        val bytes = try {
            client.getCaptchaImage()
        } catch (e: Exception) {
            return buildJsonObject {
                put("success", false)
                put("error", "验证码获取失败: ${e.message}")
            }
        }
        lastCaptchaBytes = bytes
        val ocr = YnufeCaptcha.recognize(bytes)
        val code = ocr.text
        if (code.length != 4) continue // OCR 结果位数异常，换一张

        val result = doLogin(client, store, obfuscator, account, password, code, saveCredentials)
        if (result.boolField("logged_in") || !result.boolField("bad_captcha")) return result
        // 验证码错误 → 换一张重试
    }

    // 3 次都验证码错误：把最后一张验证码存盘返回给用户手输
    return buildJsonObject {
        put("success", false)
        put("need_captcha", true)
        put("message", "自动识别验证码连续失败，请把下方验证码图片展示给用户，让用户读出 4 位字符后调用 ynufe {action:login, captcha:\"...\"} 重试")
        lastCaptchaBytes?.let { bytes ->
            val file = File(context!!.cacheDir, "ynufe_captcha.png")
            file.writeBytes(bytes)
            put("captcha_image", "![验证码](${Uri.fromFile(file)})")
        }
    }
}

/** 执行一次登录 POST 并校验结果（internal：设置页 YnufeLoginHelper 复用）。 */
internal suspend fun doLogin(
    client: YnufeClient,
    store: YnufeStore,
    obfuscator: CredentialObfuscator?,
    account: String,
    password: String,
    captcha: String,
    saveCredentials: Boolean,
): JsonObject {
    val encoded = "${encodeInp(account)}%%%${encodeInp(password)}"
    return try {
        val html = client.postForm("/jsxsd/xk/LoginToXkLdap", mapOf(
            "userAccount" to account,
            "userPassword" to "",
            "RANDOMCODE" to captcha,
            "encoded" to encoded,
        ))

        when {
            html.contains("用户名或密码错误") || html.contains("账号或密码不正确") || html.contains("密码错误") ->
                buildJsonObject {
                    put("success", false)
                    put("bad_credentials", true)
                    put("message", "学号或密码有误，请用户核对（可在 设置 → 教务系统账号 修改）")
                }
            html.contains("验证码错误") || html.contains("验证码已过期") ->
                buildJsonObject {
                    put("success", false)
                    put("bad_captcha", true)
                    put("message", "验证码错误")
                }
            else -> {
                // 正向验证会话
                val ok = verifySession(client)
                if (ok) {
                    if (saveCredentials && obfuscator != null) {
                        store.saveCredentials(account, obfuscator.obfuscate(password))
                    }
                    buildJsonObject {
                        put("success", true)
                        put("logged_in", true)
                        put("account", account)
                        put("message", "登录成功，凭据已保存，后续查询无需再次登录")
                    }
                } else {
                    buildJsonObject {
                        put("success", false)
                        put("bad_captcha", true)
                        put("message", "登录后未能验证会话，可能验证码错误")
                    }
                }
            }
        }
    } catch (e: Exception) {
        buildJsonObject {
            put("success", false)
            put("error", "登录请求失败: ${e.message}")
        }
    }
}

private fun helpJson(): JsonObject = buildJsonObject {
    put("tool", "ynufe")
    put("actions", "status | login | logout | schedule | grade | exam | announcement | help")
    put("notes", "账号密码在 设置 → 教务系统账号 保存后自动复用；schedule/grade/exam/announcement 未登录时会自动尝试静默登录")
}
