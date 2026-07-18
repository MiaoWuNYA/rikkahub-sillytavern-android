package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.python.JsBridge

/**
 * mingli 工具 — 命理排盘/抽牌/占卜统一入口。
 *
 * AI 不写执行代码，只调这个工具拿结构化 JSON 数据。
 * 支持: 塔罗 | 雷诺曼 | 八字 | 紫微 | 现代西洋占星 | 传统西洋占星 |
 *       吠陀(3引擎合一) | 人类图 | 灵数卡巴拉 | 奇门三式 | 六爻梅花
 */
fun createMingliTool(context: Context): Tool = Tool(
    name = "mingli",
    description = "命理排盘/抽牌/占卜统一入口。返回结构化JSON数据。" +
        "AI在拿到数据后，调用mingli_guide读取解读模板。" +
        "支持系统: 塔罗 | 雷诺曼 | 八字 | 紫微 | 现代西洋占星 | 传统西洋占星 | " +
        "吠陀 | 人类图 | 灵数卡巴拉 | 奇门三式 | 六爻梅花。" +
        "西洋占星分两种风格: 现代西洋占星(心理/成长取向) vs 传统西洋占星(事件判断取向)",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("system", buildJsonObject {
                    put("type", "string")
                    put("description", "命理系统名: 塔罗/雷诺曼/八字/紫微/" +
                            "现代西洋占星/传统西洋占星/吠陀/人类图/灵数卡巴拉/奇门/六爻梅花。西洋占星分两种:现代西洋占星(心理/成长)vs传统西洋占星(事件/尊贵)")
                })
                put("params", buildJsonObject {
                    put("type", "object")
                    put("description", "系统特定参数JSON。举例:" +
                            " 塔罗: {spread, seed, question_type, kaabalah}" +
                            " 八字: {year, month, day, hour, gender}" +
                            " 雷诺曼: {spread, seed}" +
                            " 紫微: {year, month, day, hour, gender, engine}" +
                            " 现代西洋占星: {year, month, day, hour, tz, lat, lon}" +
                            " 吠陀: {year, month, day, hour, tz, lat, lon, depth}" +
                            " 人类图: {year, month, day, hour, tz}" +
                            " 灵数卡巴拉: {birth_date}" +
                            " 奇门: {year, month, day, hour}" +
                            " 六爻梅花: {method, seed}")
                })
            },
            required = listOf("system")
        )
    },
    execute = { args ->
        val json = args.jsonObject
        val system = json["system"]?.jsonPrimitive?.contentOrNull
            ?: error("system is required")
        val params = json["params"]?.jsonObject ?: buildJsonObject { }

        // 启动 Python (Chaquopy 需要主线程初始化)
        if (!Python.isStarted()) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                Python.start(AndroidPlatform(context))
            }
        }

        val py = Python.getInstance()
        val router = py.getModule("mingli_router")

        // 构建 bridge — 使用轻量 JsBridge 避免 Chaquopy 17 的 Path 类加载问题
        // PythonBridge(Context, AppDatabase, ...) 传过去 Chaquopy 代理不完整
        val bridge = JsBridge()

        val rawResult = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(60_000L) {
                router.callAttr("mingli_run", system, params.toString(), bridge).toString()
            }
        }

        listOf(UIMessagePart.Text(rawResult))
    }
)
