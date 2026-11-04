package me.rerere.rikkahub.browser

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

internal object LoginDetector {

    data class Status(
        val loggedIn: Boolean?,
        val captcha: Boolean,
        val nick: String,
    )

    suspend fun detect(page: BrowserPage): Status {
        val raw = runCatching { page.evaluate(SnapshotScript.LOGIN_PROBE) }.getOrNull()
            ?: return Status(null, false, "")
        val obj = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return Status(null, false, "")
        val hasCookie = obj["hasCookie"]?.jsonPrimitive?.booleanOrNull == true
        val hasLoginForm = obj["hasLoginForm"]?.jsonPrimitive?.booleanOrNull == true
        val onLoginHost = obj["onLoginHost"]?.jsonPrimitive?.booleanOrNull == true
        val captcha = obj["captcha"]?.jsonPrimitive?.booleanOrNull == true
        val nick = obj["nick"]?.jsonPrimitive?.content.orEmpty()
        val loggedIn = !onLoginHost && !hasLoginForm && hasCookie
        return Status(loggedIn, captcha, nick)
    }
}
