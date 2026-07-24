package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 *       吠陀(3引擎合一) | 人类图 | 灵数卡巴拉 | 奇门(含大六壬) | 六爻(含梅花易数)
 */
fun createMingliTool(context: Context): Tool = Tool(
    name = "mingli",
    description = "命理排盘/抽牌/占卜统一入口。返回结构化JSON数据。" +
        "AI在拿到数据后，调用mingli_guide读取解读模板。" +
        "支持系统: 塔罗 | 雷诺曼 | 八字 | 紫微 | 现代西洋占星 | 传统西洋占星 | " +
        "吠陀 | 人类图 | 灵数卡巴拉 | 奇门(含大六壬) | 六爻(含梅花易数)。" +
        "西洋占星分两种风格: 现代西洋占星(心理/成长取向) vs 传统西洋占星(事件判断取向)",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("system", buildJsonObject {
                    put("type", "string")
                    put("description", "命理系统名(支持别名→主名): 塔罗/韦特→塔罗 | 雷诺曼→雷诺曼 | 八字/四柱/生辰→八字 | 紫微/紫薇/紫微斗数→紫微 | 星座/西洋占星→现代西洋占星 | 古典占星/卜卦/horary→传统西洋占星 | 印度占星/jyotish→吠陀 | 人类图→人类图 | 生命灵数/卡巴拉→灵数卡巴拉 | 奇门遁甲/奇门三式→奇门 | 大六壬/六壬→奇门(需配合feature=liuren) | 六爻→六爻纳甲(六爻梅花系统) | 梅花易数→梅花易数(六爻梅花系统) | 易经/周易→六爻梅花" +
                            "现代西洋占星/传统西洋占星/吠陀/人类图/灵数卡巴拉/奇门(含大六壬)/六爻梅花。西洋占星分两种:现代西洋占星(心理/成长)vs传统西洋占星(事件/尊贵)")
                })
                put("params", buildJsonObject {
                    put("type", "object")
                    put("description", "系统特定参数JSON。 cards=手动指定牌面(可选, 传则跳过随机抽牌), 正逆位: {id, reversed}。" +
                            " seed=随机种子(返回时自动生成, 传回可精确复盘该手牌)。" +
                            " 塔罗: {spread, seed, question_type, cards}" +
                            " 雷诺曼: {spread, seed, cards}" +
                            " 八字: {year, month, day, hour, minute?, gender, feature?=shengxiao|luohou|all}" +
                            " 紫微: {year, month, day, hour, minute?, gender, engine}" +
                            " 现代西洋占星: {year, month, day, hour, minute?, tz, lat, lon}" +
                            " 传统西洋占星: {year, month, day, hour, minute?, tz_offset, lat, lon}" +
                            " 吠陀: {year, month, day, hour, minute?, tz, lat, lon}" +
                            " 人类图: {year, month, day, hour, minute?, tz, gene_keys, transits}" +
                            " 灵数卡巴拉: {year, month, day, word, feature=numerology|gematria|odu|tarot|tree|all}" +
                            " 奇门(含大六壬): {year, month, day, hour, minute?, feature=qimen|liuren|all} (大六壬需feature=liuren)" +
                            " 六爻梅花(六爻与梅花易数模板已分开, system='六爻'→六爻模板, '梅花易数'→梅花易数模板): {method=time|dayan|manual|coin|number|now, seed, year, month, day, feature}")
                })
            },
            required = listOf("system")
        )
    },
    execute = { args ->
        val json = args.jsonObject
        val system = json["system"]?.jsonPrimitive?.contentOrNull
            ?: error("system is required")
        val params = json["params"]?.let { p ->
            if (p is JsonPrimitive) {
                // AI sometimes passes params as a string (JsonLiteral) — parse first
                Json.parseToJsonElement(p.content).jsonObject
            } else {
                p.jsonObject
            }
        } ?: buildJsonObject { }

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
