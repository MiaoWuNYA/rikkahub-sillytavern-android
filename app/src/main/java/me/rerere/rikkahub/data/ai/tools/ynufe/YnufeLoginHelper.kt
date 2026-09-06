package me.rerere.rikkahub.data.ai.tools.ynufe

import android.content.Context
import kotlinx.serialization.json.JsonObject

/**
 * 设置页专用的登录辅助：抓验证码 / 单次登录（成功后自动保存凭据与会话）。
 * 与 AI 工具（YnufeTool）复用同一套 doLogin 协议实现。
 */
class YnufeLoginHelper(context: Context) {

    private val client = YnufeClient(context)
    private val store = YnufeStore(context)
    private val obfuscator = CredentialObfuscator(context)

    /** 拉一张新的验证码图片字节流。 */
    suspend fun fetchCaptcha(): ByteArray = client.getCaptchaImage()

    /** 当前保存的凭据是否完整（学号 + 密码混淆串都在）。 */
    suspend fun hasSavedCredentials(): Boolean = store.snapshot().hasCredentials

    /** 解出已保存的明文密码（无保存返回空串）。 */
    suspend fun resolveSavedPassword(): String {
        val auth = store.snapshot()
        return obfuscator.deobfuscate(auth.passwordObf)
    }

    /**
     * 用给定验证码做一次登录尝试。
     * 成功时会保存凭据（saveCredentials=true）并持久化会话 Cookie。
     */
    suspend fun login(
        account: String,
        password: String,
        captcha: String,
        saveCredentials: Boolean = true,
    ): JsonObject = doLogin(client, store, obfuscator, account, password, captcha, saveCredentials)
}
