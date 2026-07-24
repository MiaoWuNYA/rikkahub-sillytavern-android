package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * mingli_guide 工具 — 读取命理解读模板。
 *
 * AI 在第一次拿到某系统的结构化数据后，调一次本工具获取解读指导。
 * 模板存储在 assets/mingli/{system}.txt，与 JS 引擎相同的 assets 模式。
 */
fun createMingliGuideTool(context: Context): Tool = Tool(
    name = "mingli_guide",
    description = "读取指定命理系统的解读模板/叙事框架/输出规范。" +
        "AI在调完mingli拿到数据后，首次遇到该系统时调一次本工具。" +
        "之后可缓存模板内容，无需再次读取。" +
        "系统名与mingli工具一致。可用系统: 塔罗/雷诺曼/八字/紫微/现代西洋占星/" +
        "传统西洋占星/吠陀/人类图/灵数卡巴拉/奇门遁甲/大六壬/六爻/梅花易数。" +
        "其中西洋占星分两种风格:" +
        "现代西洋占星(心理/成长取向,十大行星+相位+格局+合盘) vs 传统西洋占星(事件判断取向," +
        "本质尊贵+主限向运+阿拉伯点+互容接纳)。" +
        "奇门遁甲与大六壬已分开为独立模板，六爻纳甲与梅花易数也已分开。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("system", buildJsonObject {
                    put("type", "string")
                    put("description", "命理系统名")
                })
            },
            required = listOf("system")
        )
    },
    execute = { args ->
        val system = args.jsonObject["system"]?.jsonPrimitive?.contentOrNull
            ?: error("system is required")

        // 文件名映射: 系统名→assets文件名
        val fileMap = mapOf(
            "塔罗" to "塔罗",
            "tarot" to "塔罗",
            "雷诺曼" to "雷诺曼",
            "lenormand" to "雷诺曼",
            "八字" to "八字",
            "bazi" to "八字",
            "紫微" to "紫微",
            "ziwei" to "紫微",
            "现代西洋占星" to "现代西洋占星",
            "现代占星" to "现代西洋占星",
            "western_astro" to "现代西洋占星",
            "modern_astro" to "现代西洋占星",
            "传统西洋占星" to "传统西洋占星",
            "traditional_astro" to "传统西洋占星",
            "吠陀" to "吠陀",
            "vedic" to "吠陀",
            "人类图" to "人类图",
            "human_design" to "人类图",
            "灵数卡巴拉" to "灵数卡巴拉",
            "kabbalah" to "灵数卡巴拉",
            // 奇门遁甲
            "奇门遁甲" to "奇门遁甲",
            "奇门" to "奇门遁甲",
            "qimen" to "奇门遁甲",
            // 大六壬
            "大六壬" to "大六壬",
            "六壬" to "大六壬",
            "liuren" to "大六壬",
            // 六爻纳甲
            "六爻" to "六爻",
            "六爻纳甲" to "六爻",
            "liuyao" to "六爻",
            // 梅花易数
            "梅花易数" to "梅花易数",
            "梅花" to "梅花易数",
            "meihua" to "梅花易数",
            // 兼容旧名（指向主要体系）
            "六爻梅花" to "六爻",
            "yijing" to "六爻",
            "奇门三式" to "奇门遁甲",
        )

        val fileName = fileMap[system]
            ?: error("未知系统: $system，可用系统: ${fileMap.keys}")

        try {
            val template = context.assets.open("mingli/$fileName.txt")
                .bufferedReader().readText()
            listOf(UIMessagePart.Text(template))
        } catch (e: Exception) {
            error("未找到 $fileName 的解读模板: ${e.message}")
        }
    }
)
