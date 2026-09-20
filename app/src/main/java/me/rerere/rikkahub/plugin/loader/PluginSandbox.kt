package me.rerere.rikkahub.plugin.loader

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.plugin.data.PluginDataStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件沙箱
 * 使用 QuickJS 在隔离环境中执行插件代码
 *
 * 安全底线（移植自 Tumin）：
 * - 所有 QuickJS 操作必须在 PluginLoader 的单线程 dispatcher 上执行
 * - nativeFetch / nativeHttp 均受 manifest.allowedHosts 域名白名单约束（[FetchPolicy]）
 * - 插件只能访问显式注入的桥接（fetch / http / image / console / dataStore），
 *   无法反射调用宿主 Java 代码
 *
 * 裁剪：移除了 Tumin 的 memoryBank / musicPlayer 桥接（本仓库无对应服务）。
 */
class PluginSandbox(
    private val okHttpClient: OkHttpClient,
    private val dataStore: PluginDataStore? = null
) {

    companion object {
        private const val TAG = "PluginSandbox"
        private const val FETCH_TIMEOUT_SECONDS = 15L
        private const val HTTP_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
        private const val COOKIE_PREFIX = "__cookie__:"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 插件允许访问的网络域名白名单。
     * 由宿主在加载插件时根据 manifest.allowedHosts 注入。
     * 空列表表示禁止所有外部网络请求。
     */
    var allowedHosts: List<String> = emptyList()

    // QuickJS 上下文
    private var quickJSContext: QuickJSContext? = null

    // 导出的函数名称列表
    private val exportedFunctionNames = mutableSetOf<String>()

    /**
     * 初始化沙箱
     */
    fun initialize() {
        if (quickJSContext != null) return

        Log.d(TAG, "Initializing QuickJS sandbox")
        quickJSContext = QuickJSContext.create().apply {

            // 设置控制台
            setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) { Log.d(TAG, "[Plugin] $info") }
                override fun info(info: String?) { Log.i(TAG, "[Plugin] $info") }
                override fun warn(info: String?) { Log.w(TAG, "[Plugin] $info") }
                override fun error(info: String?) { Log.e(TAG, "[Plugin] $info") }
            })

            // 注入全局对象、polyfill、桥接变量
            evaluate("""
// TextEncoder polyfill - UTF-8 encoding
function TextEncoder() {}
TextEncoder.prototype.encode = function(str) {
    str = str || '';
    var bytes = [];
    for (var i = 0; i < str.length; ) {
        var codePoint = str.codePointAt(i);
        if (codePoint < 0x80) {
            bytes.push(codePoint);
        } else if (codePoint < 0x800) {
            bytes.push(0xC0 | (codePoint >> 6));
            bytes.push(0x80 | (codePoint & 0x3F));
        } else if (codePoint < 0x10000) {
            bytes.push(0xE0 | (codePoint >> 12));
            bytes.push(0x80 | ((codePoint >> 6) & 0x3F));
            bytes.push(0x80 | (codePoint & 0x3F));
        } else {
            bytes.push(0xF0 | (codePoint >> 18));
            bytes.push(0x80 | ((codePoint >> 12) & 0x3F));
            bytes.push(0x80 | ((codePoint >> 6) & 0x3F));
            bytes.push(0x80 | (codePoint & 0x3F));
        }
        i += codePoint > 0xFFFF ? 2 : 1;
    }
    return new Uint8Array(bytes);
};

// TextDecoder polyfill - UTF-8 decoding
function TextDecoder(encoding) {
    this.encoding = encoding || 'utf-8';
    this.fatal = false;
    this.ignoreBOM = false;
}
TextDecoder.prototype.decode = function(input) {
    if (!input) return '';
    var bytes;
    if (input instanceof Uint8Array) {
        bytes = input;
    } else if (input instanceof ArrayBuffer) {
        bytes = new Uint8Array(input);
    } else {
        return '';
    }
    var result = '';
    var i = 0;
    while (i < bytes.length) {
        var byte1 = bytes[i++];
        if (byte1 < 0x80) {
            result += String.fromCodePoint(byte1);
        } else if ((byte1 & 0xE0) === 0xC0) {
            var byte2 = bytes[i++];
            result += String.fromCodePoint(((byte1 & 0x1F) << 6) | (byte2 & 0x3F));
        } else if ((byte1 & 0xF0) === 0xE0) {
            var byte2 = bytes[i++];
            var byte3 = bytes[i++];
            result += String.fromCodePoint(((byte1 & 0x0F) << 12) | ((byte2 & 0x3F) << 6) | (byte3 & 0x3F));
        } else if ((byte1 & 0xF8) === 0xF0) {
            var byte2 = bytes[i++];
            var byte3 = bytes[i++];
            var byte4 = bytes[i++];
            result += String.fromCodePoint(((byte1 & 0x07) << 18) | ((byte2 & 0x3F) << 12) | ((byte3 & 0x3F) << 6) | (byte4 & 0x3F));
        }
    }
    return result;
};

// btoa polyfill
var btoa = function(str) {
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var result = '';
    for (var i = 0; i < str.length; i += 3) {
        var b1 = str.charCodeAt(i);
        var b2 = (i + 1 < str.length) ? str.charCodeAt(i + 1) : 0;
        var b3 = (i + 2 < str.length) ? str.charCodeAt(i + 2) : 0;
        result += chars[(b1 >> 2) & 0x3F];
        result += chars[((b1 << 4) | (b2 >> 4)) & 0x3F];
        result += (i + 1 < str.length) ? chars[((b2 << 2) | (b3 >> 6)) & 0x3F] : '=';
        result += (i + 2 < str.length) ? chars[b3 & 0x3F] : '=';
    }
    return result;
};

// atob polyfill
var atob = function(str) {
    str = str.replace(/\s/g, '');
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var result = '';
    for (var i = 0; i < str.length; i += 4) {
        var c1 = chars.indexOf(str[i]);
        var c2 = chars.indexOf(str[i + 1]);
        var c3 = chars.indexOf(str[i + 2]);
        var c4 = chars.indexOf(str[i + 3]);
        result += String.fromCharCode((c1 << 2) | (c2 >> 4));
        if (c3 !== -1) result += String.fromCharCode(((c2 << 4) | (c3 >> 2)) & 0xFF);
        if (c4 !== -1) result += String.fromCharCode(((c3 << 6) | c4) & 0xFF);
    }
    return result;
};

var exports = {};

// 原生桥接变量（由 Android 注入）
var __nativeFetch = null;
var __httpBridge = null;
var __imageBridge = null;
var __dataStoreBridge = null;

// dataStore 桥接对象 - 插件可直接调用
var dataStore = {
    set: function(key, value) {
        if (!__dataStoreBridge) throw new Error('dataStore bridge not available');
        var r = JSON.parse(__dataStoreBridge('set', JSON.stringify({key: key, value: value})));
        if (!r.success) throw new Error(r.error || 'dataStore.set failed: ' + key);
        return true;
    },
    get: function(key) {
        if (!__dataStoreBridge) return null;
        var r = JSON.parse(__dataStoreBridge('get', JSON.stringify({key: key})));
        if (!r.success) return null;
        return r.value;
    },
    del: function(key) {
        if (!__dataStoreBridge) return false;
        var r = JSON.parse(__dataStoreBridge('delete', JSON.stringify({key: key})));
        return r.success === true;
    },
    list: function(prefix) {
        if (!__dataStoreBridge) return [];
        var r = JSON.parse(__dataStoreBridge('list', JSON.stringify({prefix: prefix || ''})));
        if (!r.success) return [];
        return r.keys || [];
    }
};

// fetch 同步包装
function fetch(url, options) {
    if (!__nativeFetch) {
        throw new Error('fetch is not available: native fetch not injected');
    }
    var optsJson = options ? JSON.stringify(options) : '{}';
    var resultJson = __nativeFetch(url, optsJson);
    var result = JSON.parse(resultJson);
    if (!result.success) {
        throw new Error(result.error || 'fetch failed');
    }
    return {
        ok: result.ok,
        status: result.status,
        headers: result.headers,
        body: result.body,
        text: function() { return result.body; },
        json: function() { return JSON.parse(result.body); }
    };
}

// ---------------------------------------------------------------------------
// 会话式 HTTP（http.*）
//
// 与上面的裸 fetch 的区别：这一个自带 Cookie 罐，且能拿到二进制（bytes / base64）。
// 教务、论坛这类需要「先拿验证码和 JSESSIONID，再带着它登录，登录时服务端还会
// 换发新的会话 ID」的场景，裸 fetch 做不了——每一跳响应的 Set-Cookie 都得留住。
//
//   var r = http.get('https://x/login');          // 自动带上之前存的 cookie
//   http.postForm('https://x/login', {a: 1});     // form-urlencoded
//   http.postJson('https://x/api', {a: 1});
//   http.cookies('https://x');                    // 查看当前 cookie（调试用）
//   http.clearCookies();
//
// 返回对象与 fetch 同形，另加 bytes()（Uint8Array）与 base64()。
// ---------------------------------------------------------------------------
function __httpCall(method, url, options) {
    if (!__httpBridge) throw new Error('http is not available: native bridge not injected');
    options = options || {};
    var payload = {
        method: method,
        url: url,
        headers: options.headers || {},
        body: options.body === undefined || options.body === null ? null : String(options.body),
        contentType: options.contentType || null,
        timeoutMs: options.timeoutMs || 0
    };
    var result = JSON.parse(__httpBridge('request', JSON.stringify(payload)));
    if (!result.success) throw new Error(result.error || 'http request failed');

    var bytesCache = null;
    return {
        ok: result.ok,
        status: result.status,
        url: result.url,
        redirected: result.redirected,
        headers: result.headers,
        body: result.body,
        text: function() { return result.body; },
        json: function() { return JSON.parse(result.body); },
        bytes: function() {
            if (bytesCache) return bytesCache;
            var bin = atob(result.base64 || '');
            var out = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 0xFF;
            bytesCache = out;
            return out;
        },
        base64: function() { return result.base64 || ''; }
    };
}

function __formEncode(data) {
    var parts = [];
    for (var k in data) {
        if (!Object.prototype.hasOwnProperty.call(data, k)) continue;
        if (data[k] === undefined || data[k] === null) continue;
        parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(data[k])));
    }
    return parts.join('&');
}

var http = {
    request: function(method, url, options) { return __httpCall(method, url, options); },
    get: function(url, options) { return __httpCall('GET', url, options); },
    post: function(url, body, options) {
        options = options || {};
        options.body = body;
        return __httpCall('POST', url, options);
    },
    put: function(url, body, options) {
        options = options || {};
        options.body = body;
        return __httpCall('PUT', url, options);
    },
    delete: function(url, options) { return __httpCall('DELETE', url, options); },
    head: function(url, options) { return __httpCall('HEAD', url, options); },
    postForm: function(url, data, options) {
        options = options || {};
        options.body = __formEncode(data);
        options.contentType = options.contentType || 'application/x-www-form-urlencoded; charset=utf-8';
        return __httpCall('POST', url, options);
    },
    postJson: function(url, data, options) {
        options = options || {};
        options.body = typeof data === 'string' ? data : JSON.stringify(data);
        options.contentType = options.contentType || 'application/json; charset=utf-8';
        return __httpCall('POST', url, options);
    },
    cookies: function(url) {
        if (!__httpBridge) return {};
        var r = JSON.parse(__httpBridge('cookies', JSON.stringify({url: url || null})));
        return r.success ? (r.cookies || {}) : {};
    },
    clearCookies: function() {
        if (!__httpBridge) return false;
        return JSON.parse(__httpBridge('clearCookies', '{}')).success === true;
    }
};

// ---------------------------------------------------------------------------
// 图像解码（image.*）
//
// 沙箱里没有 canvas，验证码识别这类要逐像素算的活儿必须让宿主解码。
//   var img = image.decode(bytes);  // {width, height, pixels: Uint8ClampedArray}
// pixels 是 RGBA 顺序，每 4 字节一个像素——与浏览器 canvas.getImageData 一致，
// 便于把现成的 canvas 图像算法直接搬进来。
// ---------------------------------------------------------------------------
var image = {
    decode: function(data) {
        if (!__imageBridge) throw new Error('image is not available: native bridge not injected');
        var b64 = '';
        if (typeof data === 'string') {
            b64 = data;
        } else if (data && typeof data.base64 === 'function') {
            b64 = data.base64();
        } else if (data && data.length !== undefined) {
            // Uint8Array / Array：逐字节转 base64（避免 apply 栈溢出，分块处理）
            var chars = '';
            var chunk = 8192;
            for (var i = 0; i < data.length; i += chunk) {
                chars += String.fromCharCode.apply(null, Array.prototype.slice.call(data, i, i + chunk));
            }
            b64 = btoa(chars);
        } else {
            throw new Error('image.decode expects Uint8Array, base64 string, or an http response');
        }
        var r = JSON.parse(__imageBridge('decode', JSON.stringify({base64: b64})));
        if (!r.success) throw new Error(r.error || 'image decode failed');
        var bin = atob(r.rgba);
        var pixels = new Uint8ClampedArray(bin.length);
        for (var j = 0; j < bin.length; j++) pixels[j] = bin.charCodeAt(j) & 0xFF;
        return { width: r.width, height: r.height, pixels: pixels };
    }
};
""".trimIndent())

            // 注入原生 fetch
            getGlobalObject().setProperty("__nativeFetch", JSCallFunction { args ->
                val url = args[0] as? String ?: ""
                val optionsJson = args[1] as? String ?: "{}"
                try {
                    nativeFetch(url, optionsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Native fetch error: url=$url", e)
                    """{"success":false,"error":${escapeJson(e.message ?: "Unknown error")}}"""
                }
            })

            // 注入会话式 HTTP 桥接
            getGlobalObject().setProperty("__httpBridge", JSCallFunction { args ->
                val action = args[0] as? String ?: ""
                val paramsJson = args[1] as? String ?: "{}"
                try {
                    nativeHttpBridge(action, paramsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "HTTP bridge error: action=$action", e)
                    """{"success":false,"error":${escapeJson(e.message ?: "Unknown error")}}"""
                }
            })

            // 注入图像解码桥接
            getGlobalObject().setProperty("__imageBridge", JSCallFunction { args ->
                val action = args[0] as? String ?: ""
                val paramsJson = args[1] as? String ?: "{}"
                try {
                    nativeImageBridge(action, paramsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Image bridge error: action=$action", e)
                    """{"success":false,"error":${escapeJson(e.message ?: "Unknown error")}}"""
                }
            })

            // 注入 PluginDataStore 桥接
            getGlobalObject().setProperty("__dataStoreBridge", JSCallFunction { args ->
                val action = args[0] as? String ?: ""
                val paramsJson = args[1] as? String ?: "{}"
                try {
                    nativeDataStoreBridge(action, paramsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "DataStore bridge error: action=$action, params=$paramsJson", e)
                    """{"success":false,"error":${escapeJson(e.message ?: "Unknown error")}}"""
                }
            })

        }

        Log.d(TAG, "QuickJS sandbox initialized")
    }

    /**
     * 使用 OkHttp 执行同步 HTTP 请求
     */
    private fun nativeFetch(url: String, optionsJson: String): String {
        Log.d(TAG, "nativeFetch: $url")
        return try {
            // 域名白名单检查（fail-closed）
            if (!FetchPolicy.isUrlAllowed(url, allowedHosts)) {
                val host = runCatching { java.net.URL(url).host }.getOrDefault(url)
                Log.w(TAG, "nativeFetch blocked: host='$host' not in allowedHosts=$allowedHosts")
                return """{"success":false,"error":"Network request to '$host' is not allowed. Please add it to manifest.allowedHosts."}"""
            }

            val options = json.parseToJsonElement(optionsJson) as? JsonObject ?: JsonObject(emptyMap())
            val method = (options["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
            val headers = options["headers"] as? JsonObject
            val body = options["body"] as? JsonPrimitive

            val requestBuilder = Request.Builder().url(url)

            headers?.forEach { (key, value) ->
                val headerValue = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                requestBuilder.addHeader(key, headerValue)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    val requestBody = okhttp3.RequestBody.create(null, body?.contentOrNull ?: "")
                    requestBuilder.post(requestBody)
                }
                "PUT" -> {
                    val requestBody = okhttp3.RequestBody.create(null, body?.contentOrNull ?: "")
                    requestBuilder.put(requestBody)
                }
                "DELETE" -> {
                    val requestBody = body?.contentOrNull?.let { okhttp3.RequestBody.create(null, it) }
                    if (requestBody != null) requestBuilder.delete(requestBody) else requestBuilder.delete()
                }
                else -> requestBuilder.get()
            }

            val fetchClient = okHttpClient.newBuilder()
                .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val response = fetchClient.newCall(requestBuilder.build()).execute()
            response.use {
                val responseBody = it.body?.string() ?: ""
                val statusCode = it.code
                val responseHeaders = it.headers

                val headersJson = responseHeaders.names().associateWith { name ->
                    responseHeaders.values(name).joinToString(", ")
                }

                buildString {
                    append("{\"success\":true,")
                    append("\"status\":$statusCode,")
                    append("\"ok\":${statusCode in 200..299},")
                    append("\"headers\":${json.encodeToString(JsonObject.serializer(), JsonObject(headersJson.mapValues { JsonPrimitive(it.value) }))},")
                    append("\"body\":${json.encodeToString(JsonPrimitive(responseBody))}")
                    append("}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "nativeFetch failed: url=$url", e)
            """{"success":false,"error":${escapeJson(e.message ?: "Unknown error")}}"""
        }
    }

    // ======================= 会话式 HTTP =======================

    /**
     * 插件 Cookie 存储：内存 + PluginDataStore 双写。
     *
     * 只订阅会话类 cookie（无过期时间或未过期）。持久化是因为插件进程随时可能被
     * 系统回收，登录态却没理由因此作废——用户不该每次打开应用都重登一次。
     * 命名空间由 PluginDataStore 自身保证（每个插件一个 SharedPreferences）。
     */
    private inner class PluginCookieStore {
        private val cache = mutableMapOf<String, MutableMap<String, Cookie>>()
        private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            val store = dataStore ?: return
            store.listData().filter { it.startsWith(COOKIE_PREFIX) }.forEach { key ->
                runCatching {
                    val saved = json.decodeFromString(SavedCookie.serializer(), store.getData(key) ?: return@forEach)
                    if (saved.expiresAt > 0 && saved.expiresAt <= System.currentTimeMillis()) return@forEach
                    val cookie = Cookie.Builder()
                        .name(saved.name)
                        .value(saved.value)
                        .domain(saved.domain)
                        .path(saved.path)
                        .apply { if (saved.secure) secure() }
                        .apply { if (saved.httpOnly) httpOnly() }
                        .build()
                    cache.getOrPut(saved.domain) { mutableMapOf() }[saved.name] = cookie
                }
            }
        }

        fun cookiesFor(url: HttpUrl): List<Cookie> {
            ensureLoaded()
            return cache.values.flatMap { it.values }.filter { it.matches(url) }
        }

        fun save(url: HttpUrl, cookies: List<Cookie>) {
            ensureLoaded()
            if (cookies.isEmpty()) return
            val store = dataStore
            for (cookie in cookies) {
                val domain = cookie.domain.ifEmpty { url.host }
                // 服务端用 Max-Age=0 表示删除，别把它存回来
                if (cookie.expiresAt <= 0 && cookie.value.isEmpty()) {
                    cache[domain]?.remove(cookie.name)
                    store?.deleteData("$COOKIE_PREFIX$domain.${cookie.name}")
                    continue
                }
                cache.getOrPut(domain) { mutableMapOf() }[cookie.name] = cookie
                store?.setData(
                    "$COOKIE_PREFIX$domain.${cookie.name}",
                    json.encodeToString(
                        SavedCookie.serializer(),
                        SavedCookie(
                            name = cookie.name,
                            value = cookie.value,
                            domain = domain,
                            path = cookie.path.ifEmpty { "/" },
                            expiresAt = cookie.expiresAt,
                            secure = cookie.secure,
                            httpOnly = cookie.httpOnly,
                        )
                    )
                )
            }
        }

        fun all(): Map<String, String> {
            ensureLoaded()
            return cache.values.flatMap { it.values }.associate { it.name to it.value }
        }

        fun clear() {
            ensureLoaded()
            cache.clear()
            dataStore?.listData()
                ?.filter { it.startsWith(COOKIE_PREFIX) }
                ?.forEach { dataStore.deleteData(it) }
        }
    }

    private val cookieStore by lazy { PluginCookieStore() }

    private val httpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
                    cookieStore.save(url, cookies)

                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    cookieStore.cookiesFor(url)
            })
            // 登录流程靠 302 换发会话，不自动跟随的话拿不到新 cookie
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Serializable
    private data class SavedCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
    )

    /**
     * 会话式 HTTP 桥接。与 nativeFetch 的两点不同：
     * 1. 带 Cookie 罐（见 [PluginCookieStore]），跨调用保持登录态
     * 2. 返回 base64 原文，插件可解成二进制（验证码图片等）
     *
     * 白名单约束与 fetch 完全一致，失败一律 fail-closed。
     */
    private fun nativeHttpBridge(action: String, paramsJson: String): String {
        return when (action) {
            "cookies" -> {
                val cookies = cookieStore.all()
                val obj = buildJsonObject { cookies.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
                """{"success":true,"cookies":${json.encodeToString(JsonObject.serializer(), obj)}}"""
            }
            "clearCookies" -> {
                cookieStore.clear()
                """{"success":true}"""
            }
            "request" -> {
                val params = json.parseToJsonElement(paramsJson) as? JsonObject
                    ?: return """{"success":false,"error":"invalid request params"}"""
                val url = (params["url"] as? JsonPrimitive)?.contentOrNull
                    ?: return """{"success":false,"error":"url is required"}"""
                if (!FetchPolicy.isUrlAllowed(url, allowedHosts)) {
                    val host = runCatching { java.net.URL(url).host }.getOrDefault(url)
                    Log.w(TAG, "http blocked: host='$host' not in allowedHosts=$allowedHosts")
                    return """{"success":false,"error":"Network request to '$host' is not allowed. Please add it to manifest.allowedHosts."}"""
                }
                runCatching { performHttpRequest(params) }
                    .getOrElse { """{"success":false,"error":${escapeJson(it.message ?: "Unknown error")}}""" }
            }
            else -> """{"success":false,"error":"unknown action: $action"}"""
        }
    }

    private fun performHttpRequest(params: JsonObject): String {
        val method = (params["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val bodyText = (params["body"] as? JsonPrimitive)?.contentOrNull
        val contentType = (params["contentType"] as? JsonPrimitive)?.contentOrNull
        val timeoutMs = (params["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

        val builder = Request.Builder().url(url)
        (params["headers"] as? JsonObject)?.forEach { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { builder.addHeader(key, it) }
        }

        val requestBody = bodyText?.toRequestBody(
            (contentType ?: DEFAULT_FORM_CONTENT_TYPE).toMediaType()
        )
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(requestBody ?: "".toRequestBody(null))
            "PUT" -> builder.put(requestBody ?: "".toRequestBody(null))
            "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(null))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> builder.get()
        }

        val client = if (timeoutMs > 0) {
            httpClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            httpClient
        }

        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            // 二进制内容（图片）当字符串读会变成乱码，所以正文按 UTF-8 解、原文另附 base64
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            val headersJson = buildJsonObject {
                response.headers.names().forEach { name ->
                    put(name, JsonPrimitive(response.headers.values(name).joinToString(", ")))
                }
            }
            return buildString {
                append("{\"success\":true,")
                append("\"status\":${response.code},")
                append("\"ok\":${response.code in 200..299},")
                append("\"redirected\":${response.priorResponse != null},")
                append("\"url\":${escapeJson(response.request.url.toString())},")
                append("\"headers\":${json.encodeToString(JsonObject.serializer(), headersJson)},")
                append("\"body\":${escapeJson(text)},")
                append("\"base64\":${escapeJson(Base64.encodeToString(bytes, Base64.NO_WRAP))}")
                append("}")
            }
        }
    }

    // ======================= 图像解码 =======================

    /**
     * 图像解码桥接：把 PNG/JPEG 字节解成 RGBA 像素（与 canvas.getImageData 同序），
     * 让插件能在 JS 里做逐像素处理（验证码识别等）。
     */
    private fun nativeImageBridge(action: String, paramsJson: String): String {
        if (action != "decode") return """{"success":false,"error":"unknown action: $action"}"""
        val params = json.parseToJsonElement(paramsJson) as? JsonObject
            ?: return """{"success":false,"error":"invalid params"}"""
        val b64 = (params["base64"] as? JsonPrimitive)?.contentOrNull
            ?: return """{"success":false,"error":"base64 is required"}"""

        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }
            .getOrElse { return """{"success":false,"error":"invalid base64: ${escapeJson(it.message ?: "")}"}""" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return """{"success":false,"error":"unsupported or corrupt image data"}"""

        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            // ARGB_8888 的 int 是 A<<24|R<<16|G<<8|B，转成 canvas 的 RGBA 字节序
            val rgba = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                val c = pixels[i]
                val o = i * 4
                rgba[o] = ((c shr 16) and 0xFF).toByte()
                rgba[o + 1] = ((c shr 8) and 0xFF).toByte()
                rgba[o + 2] = (c and 0xFF).toByte()
                rgba[o + 3] = ((c shr 24) and 0xFF).toByte()
            }
            """{"success":true,"width":$w,"height":$h,"rgba":${escapeJson(Base64.encodeToString(rgba, Base64.NO_WRAP))}}"""
        } finally {
            // Bitmap 不是 Closeable，只能手动回收（验证码图小，但插件可能循环解码）
            bitmap.recycle()
        }
    }

    /**
     * PluginDataStore 桥接 - 由 JS 插件调用，同步操作数据存储
     */
    private fun nativeDataStoreBridge(action: String, paramsJson: String): String {
        val store = dataStore
        if (store == null) {
            Log.w(TAG, "DataStore bridge called but dataStore is null, action=$action")
            return """{"success":false,"error":"dataStore not available for this plugin"}"""
        }
        return try {
            val params = org.json.JSONObject(paramsJson)
            when (action) {
                "set" -> {
                    val key = params.optString("key", "")
                    val value = params.optString("value", "")
                    if (key.isBlank()) return """{"success":false,"error":"key is required"}"""
                    store.setData(key, value)
                    """{"success":true}"""
                }
                "get" -> {
                    val key = params.optString("key", "")
                    if (key.isBlank()) return """{"success":false,"error":"key is required"}"""
                    val value = store.getData(key)
                    if (value != null) {
                        """{"success":true,"value":${escapeJson(value)}}"""
                    } else {
                        """{"success":false,"error":"key not found: $key"}"""
                    }
                }
                "delete" -> {
                    val key = params.optString("key", "")
                    if (key.isBlank()) return """{"success":false,"error":"key is required"}"""
                    store.deleteData(key)
                    """{"success":true}"""
                }
                "list" -> {
                    val prefix = params.optString("prefix", "")
                    val keys = store.listData().filter { it.startsWith(prefix) }
                    val keysJson = keys.joinToString(",", "[", "]") { escapeJson(it) }
                    """{"success":true,"keys":$keysJson}"""
                }
                else -> {
                    Log.w(TAG, "DataStore bridge unknown action: $action")
                    """{"success":false,"error":"unknown action: $action"}"""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DataStore bridge action='$action' failed, params=$paramsJson", e)
            """{"success":false,"error":${escapeJson(e.message ?: "Unknown error")}}"""
        }
    }

    /**
     * 执行 JS 文件
     */
    fun evaluateFile(file: File) {
        val jsContext = quickJSContext ?: throw IllegalStateException("Sandbox not initialized")
        Log.d(TAG, "Evaluating JS file: ${file.name}")

        var code = file.readText()

        // QuickJS wrapper 未启用协程支持，把 async/await 预处理为同步代码
        val asyncRegex = Regex("""\basync\s+function\b""")
        if (asyncRegex.containsMatchIn(code)) {
            Log.d(TAG, "Preprocessing: converting async functions to sync functions")
            code = asyncRegex.replace(code, "function")
        }

        val awaitRegex = Regex("""\bawait\s+""")
        if (awaitRegex.containsMatchIn(code)) {
            Log.d(TAG, "Preprocessing: removing await keywords")
            code = awaitRegex.replace(code, "")
        }

        jsContext.evaluate(code, file.name)

        // 收集 exports 的函数名
        try {
            val keysResult = jsContext.evaluate("JSON.stringify(Object.keys(exports))")
            when (keysResult) {
                is String -> {
                    try {
                        val parsed = json.parseToJsonElement(keysResult)
                        if (parsed is JsonArray) {
                            parsed.forEach { element ->
                                (element as? JsonPrimitive)?.contentOrNull?.let { key ->
                                    exportedFunctionNames.add(key)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        keysResult.removeSurrounding("[", "]")
                            .split(",")
                            .map { it.trim().removeSurrounding("\"") }
                            .filter { it.isNotEmpty() }
                            .forEach { exportedFunctionNames.add(it) }
                    }
                }
                else -> {
                    Log.w(TAG, "Unexpected keys result type: ${keysResult?.javaClass?.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Object.keys(exports)", e)
        }

        Log.d(TAG, "Exported functions: $exportedFunctionNames")
    }

    /**
     * 调用导出的函数
     * 注意：必须在 QuickJS 线程上调用（通过 PluginLoader 的 pluginDispatcher）
     */
    fun callFunction(name: String, params: JsonElement): JsonElement {
        val jsContext = quickJSContext ?: throw IllegalStateException("Sandbox not initialized")

        if (!exportedFunctionNames.contains(name)) {
            throw IllegalArgumentException("Function '$name' not found in exports. Available: $exportedFunctionNames")
        }

        Log.d(TAG, "Calling function: $name with params: $params")

        return try {
            val paramsJson = json.encodeToString(JsonElement.serializer(), params)

            val callCode = """
(function() {
    try {
        var __ret = exports['$name']($paramsJson);
        if (__ret && typeof __ret.then === 'function') {
            var __resolved = null;
            var __rejected = null;
            __ret.then(function(v) { __resolved = v; }).catch(function(e) { __rejected = e; });
            if (__rejected) {
                return JSON.stringify({success: false, error: __rejected.message || String(__rejected)});
            }
            return JSON.stringify(__resolved);
        }
        return JSON.stringify(__ret);
    } catch(e) {
        return JSON.stringify({success: false, error: e.message || String(e)});
    }
})()
""".trimIndent()

            val result = jsContext.evaluate(callCode)
            Log.d(TAG, "Function $name raw result: $result")

            when (result) {
                is String -> {
                    try { json.parseToJsonElement(result) }
                    catch (_: Exception) { JsonPrimitive(result) }
                }
                is Number -> JsonPrimitive(result)
                is Boolean -> JsonPrimitive(result)
                is QuickJSObject -> {
                    val str = result.stringify()
                    try { json.parseToJsonElement(str) }
                    catch (_: Exception) { JsonPrimitive(str) }
                }
                null -> JsonNull
                else -> JsonPrimitive(result.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call function '$name'", e)
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 转义 JSON 字符串
     * 使用 kotlinx.serialization 的 JsonPrimitive 序列化器，完整、正确、符合 JSON 规范地转义
     */
    private fun escapeJson(str: String): String {
        return json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(str))
    }

    /**
     * 注入配置
     */
    fun injectConfig(config: Map<String, JsonElement>) {
        val jsContext = quickJSContext ?: return
        val configJson = json.encodeToString(JsonObject.serializer(), JsonObject(config))
        jsContext.evaluate("var config = $configJson;")
    }

    fun hasFunction(name: String): Boolean = exportedFunctionNames.contains(name)

    fun getExportedFunctionNames(): Set<String> = exportedFunctionNames.toSet()

    fun destroy() {
        exportedFunctionNames.clear()
        quickJSContext?.destroy()
        quickJSContext = null
        Log.d(TAG, "Sandbox destroyed")
    }
}
