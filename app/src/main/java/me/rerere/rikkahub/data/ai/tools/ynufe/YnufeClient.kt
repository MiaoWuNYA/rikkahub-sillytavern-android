package me.rerere.rikkahub.data.ai.tools.ynufe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 会话过期专用异常：请求层检测到登录重定向时抛出，防止用登录页 HTML 去解析业务数据。
 */
class YnufeSessionExpired(endpoint: String) : Exception("Session expired on: $endpoint")

/**
 * 云南财经强智教务网通信引擎（移植自 云南财经/src/api/client.ts）。
 *
 * 会话 Cookie 通过 OkHttp CookieJar 与 YnufeStore 双向同步：
 * 每一跳响应（含登录 302 重定向中间响应）的 Set-Cookie 都会被持久化，
 * 强智登录成功后服务端换发新 JSESSIONID 的场景不会丢会话。
 * 这是对齐原 TS（浏览器 Cookie 罐自动管理）行为的关键点。
 */
class YnufeClient(context: Context) {

    companion object {
        private const val BASE_URL = "https://xjwis.ynufe.edu.cn"
        private const val HOST = "xjwis.ynufe.edu.cn"
        private const val TIMEOUT_MS = 15_000L
    }

    private val store = YnufeStore(context)
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl()

    /** 会话 Cookie 与持久化存储的双向桥。 */
    private val sessionCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            runBlocking {
                val snap = store.snapshot()
                var js = snap.jsessionid
                var xs = snap.jsxsd.takeIf { it.isNotEmpty() }
                var changed = false
                for (c in cookies) {
                    when {
                        c.name.equals("JSESSIONID", ignoreCase = true) -> { js = c.value; changed = true }
                        c.name.equals("jsxsd", ignoreCase = true) -> { xs = c.value; changed = true }
                    }
                }
                if (changed) store.saveSession(js, xs)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val a = runBlocking { store.snapshot() }
            val list = ArrayList<Cookie>(2)
            if (a.jsessionid.isNotEmpty()) {
                list += Cookie.Builder()
                    .name("JSESSIONID")
                    .value(a.jsessionid)
                    .domain(HOST)
                    .path("/")
                    .build()
            }
            if (a.jsxsd.isNotEmpty()) {
                list += Cookie.Builder()
                    .name("jsxsd")
                    .value(a.jsxsd)
                    .domain(HOST)
                    .path("/")
                    .build()
            }
            return list
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(sessionCookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun buildHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$BASE_URL/jsxsd/framework/xsMain.jsp",
    )

    private fun checkSessionTimeout(text: String, endpoint: String) {
        if (endpoint.contains("LoginToXkLdap")) return
        val structural = text.contains("sys/login.jsp") || text.contains("LoginToXkLdap") || text.contains("SYSTEM_LOGIN")
        val hasBusiness = text.contains("id=\"dataList\"") || text.contains("id=\"kbtable\"") ||
            text.contains("middletopdwxxcont") || text.contains("id=\"Table1\"")
        val is404 = (text.contains("404 error") || text.contains("404 错误") ||
            text.contains("Request Page Not Found") || text.contains("您请求的页面不存在") ||
            text.contains("HTTP Status 404")) && !hasBusiness
        val phraseOnly = (text.contains("非法访问") || text.contains("请重新登录") || is404) && !hasBusiness
        if (structural || phraseOnly) throw YnufeSessionExpired(endpoint)
    }

    private fun newRequest(endpoint: String, extraHeaders: Map<String, String> = emptyMap()): Request.Builder {
        val rb = Request.Builder()
            .url(baseUrl.resolve(endpoint) ?: baseUrl)
            .headers((buildHeaders() + extraHeaders).toHeaders())
        return rb
    }

    /**
     * GET 页面 HTML。
     */
    suspend fun getHtml(endpoint: String): String = withContext(Dispatchers.IO) {
        val request = newRequest(endpoint).get().build()
        val resp = client.newCall(request).execute()
        try {
            val text = resp.body?.string() ?: ""
            checkSessionTimeout(text, endpoint)
            text
        } finally {
            resp.close()
        }
    }

    /**
     * POST 表单提交。
     */
    suspend fun postForm(endpoint: String, formData: Map<String, String>): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            for ((k, v) in formData) add(k, v)
        }.build()
        val request = newRequest(endpoint).post(body).build()
        val resp = client.newCall(request).execute()
        try {
            val text = resp.body?.string() ?: ""
            checkSessionTimeout(text, endpoint)
            text
        } finally {
            resp.close()
        }
    }

    /**
     * POST form-urlencoded（服务端需要原始字符串 body 时用）。
     */
    suspend fun postUrlEncoded(endpoint: String, urlEncodedBody: String): String = withContext(Dispatchers.IO) {
        val body = urlEncodedBody.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = newRequest(endpoint, mapOf("Content-Type" to "application/x-www-form-urlencoded"))
            .post(body)
            .build()
        val resp = client.newCall(request).execute()
        try {
            val text = resp.body?.string() ?: ""
            checkSessionTimeout(text, endpoint)
            text
        } finally {
            resp.close()
        }
    }

    /**
     * 拉取验证码图片字节流（JSESSIONID 由 CookieJar 自动捕获，验证码与会话绑定）。
     */
    suspend fun getCaptchaImage(): ByteArray = withContext(Dispatchers.IO) {
        val request = newRequest(
            "/jsxsd/verifycode.servlet?t=${System.currentTimeMillis()}",
            mapOf("Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"),
        ).get().build()
        val resp = client.newCall(request).execute()
        try {
            resp.body?.bytes() ?: ByteArray(0)
        } finally {
            resp.close()
        }
    }
}

private fun Map<String, String>.toHeaders() = okhttp3.Headers.Builder().apply {
    for ((k, v) in this@toHeaders) add(k, v)
}.build()
