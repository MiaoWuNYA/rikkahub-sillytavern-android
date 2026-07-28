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
 *       吠陀(3引擎合一) | 人类图 | 灵数卡巴拉 | 奇门(含大六壬) | 六爻(含梅花易数) |
 *       深度古典占星(基于stellium组件化引擎)
 */
fun createMingliTool(context: Context): Tool = Tool(
    name = "mingli",
    description = "命理排盘/抽牌/占卜统一入口。返回结构化JSON数据。" +
        "【强制】AI拿到数据后必须调mingli_guide读取该系统的解读模板，然后严格按模板逐条解读，不得跳过模板或自行发挥。" +
        "【例外：用户显式指令优先】如果用户明确提供了替代的解读框架或解读指令(如用户技能中已定义解读规则、或用户明确说了“不用模板”、“按我说的解读”等)，" +
        "则以用户的显式指令为准，禁止调mingli_guide，直接解读即可。AI应自行判断：用户给的是“怎么解读”的方法指令(跳过模板)，还是普通对话(仍走模板)。" +
        "【强制利用全部数据】返回数据结构中的所有字段都必须被AI使用。模板是解读主线框架，但不是数据过滤器——" +
        "每个字段都要在解读中找到对应的使用位置，不得因为模板未明确提及就跳过任何字段。AI应主动将数据中的每个字段融入解读。" +
        "支持系统: 塔罗 | 雷诺曼 | 八字 | 紫微 | 现代西洋占星 | 传统西洋占星 | " +
        "吠陀 | 人类图 | 灵数卡巴拉 | 奇门(含大六壬) | 六爻(含梅花易数) | 深度古典占星(基于stellium组件引擎)。" +
        "西洋占星分两种风格: 现代西洋占星(心理/成长取向) vs 传统西洋占星(事件判断/卜卦取向)。" +
        "深度古典占星(stellium)是组件化的深度引擎，支持Hellenistic/Medieval占星全栈(尊贵/互容/阿拉伯点/Firdaria/ZR/主限推运/卜卦等)",
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
                            " 八字: {year, month, day, hour, minute?, gender, feature?=bazi|shengxiao|luohou|all}" +
                            " 紫微: {year, month, day, hour, minute?, gender, engine?=iztro}" +
                            " 现代西洋占星: {year, month, day, hour, minute?, tz, lat, lon, partner_year?, partner_month?, partner_day?, partner_hour?, partner_minute?, partner_tz?=IANA, partner_lat?, partner_lon?}" +
                            " 传统西洋占星: {year, month, day, hour, minute?, tz_offset, lat, lon}" +
                            " 吠陀: {year, month, day, hour, minute?, tz=IANA时区/数字偏移, lat?, lon?}" +
                            " 人类图: {year, month, day, hour, minute?, tz, gene_keys?=false(bool), transits?=false(bool)}" +
                            " 灵数卡巴拉: {year, month, day, word?, feature=numerology|gematria|odu|tarot|tree|all}" +
                            " 奇门(含大六壬): {year, month, day, hour?, minute?, feature=qimen|liuren|all} (大六壬需feature=liuren)" +
                            " (大六壬年命: birth_year?, birth_month?, birth_day? (以上均为公历), gender? (填\"男\"或\"女\"))" +
                            " 注: 大六壬年命需要AI主动向用户询问出生公历年月日和性别(男/女); 不要猜测; 不提供则跳过年命;" +
                            " 六爻梅花(六爻与梅花易数模板已分开, system='六爻'→六爻模板, '梅花易数'→梅花易数模板): " +
                            "{method=time(默认,Python时间起卦,不可复盘)/" +
                            "js_time(JS梅花易数时间起卦,不可复盘)/" +
                            "dayan(JS大衍筮法,真随机,不可复盘)/" +
                            "lueshifa(JS略筮法,真随机,不可复盘)/" +
                            "three_number(JS三数起卦, seed为三位数字拼接,如seed=868→(8,6,8),可复盘)/" +
                            "number_array(JS数组起卦,需传numbers=[n1,n2,...,nN], hour自动,同seed同结果,可复盘)/" +
                            "manual或manual_input(手动输爻,需传yao参数)/" +
                            "coin(Python硬币法,同seed同结果,可复盘)/" +
                            "number(Python均匀随机,同seed同结果,可复盘), " +
                            "seed(传回可复盘受种子控制的method), year, month, day, hour?, feature, " +
                            "yao(manual/manual_input时传6位6789字符串), " +
                            "numbers(number_array时传数组)}" +
                            " 深度古典占星(stellium/hellenistic): " +
                            "{year, month, day, hour, minute?, tz?=IANA时区, lat?, lon?, " +
                            "house_system?=placidus|whole_sign|equal|koch|regiomontanus|porphyry|campanus" +
                            " | ═══ 关系合盘(传partner_year触发) ═══ " +
                            "partner_year?, partner_month?, partner_day?, partner_hour?, partner_minute?, partner_tz?, partner_lat?, partner_lon?" +
                            " → 返回 Synastry(双星交叉+宫位覆盖) + Composite(组合中点) + Davison(时空盘)" +
                            " | ═══ 行运(传transit_date触发) ═══ " +
                            "transit_date?=YYYY-MM-DD, transit_hour?" +
                            " → 返回 Transit(指定日期过境盘，外行星与本命交叉相位)" +
                            " | ═══ 行运预报(传transit_forecast_months触发) ═══ " +
                            "transit_forecast_months?=数字(如6)" +
                            " → 返回 TransitForecast(未来N个月外行星换座/停滞日期列表，中长期趋势)" +
                            " | ═══ 返照盘(传return_year触发) ═══ " +
                            "return_year?=数字(如2026)" +
                            " → 返回 SolarReturn+LunarReturn+SaturnReturn+JupiterReturn(年度主题判断)" +
                            " | ═══ 推运(传progression_age或progression_date触发) ═══ " +
                            "progression_age?=年龄, progression_date?=YYYY-MM-DD" +
                            " → 返回 Progression(次限) + ArcDirection(太阳弧) + PrimaryDirections(主限推运/行军)" +
                            " | ═══ 行星回归(传crossings_start+crossings_end触发) ═══ " +
                            "crossings_start?=YYYY-MM-DD, crossings_end?=YYYY-MM-DD" +
                            " → 返回 PlanetaryCrossings(时间段内行运行星走到本命行星位置的具体日期，精确触发点)}")
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
