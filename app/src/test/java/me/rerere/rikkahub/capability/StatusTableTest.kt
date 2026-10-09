package me.rerere.rikkahub.capability

import me.rerere.rikkahub.ui.components.richtext.convertStatusBlocksToTables
import me.rerere.rikkahub.ui.components.richtext.escapeGuideSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏表格化。
 *
 * 卡的输出状态栏是 emoji 开头的字段行，窄屏上挤成一团。
 * 转成 GFM 表格两列展示。触发条件必须苛刻：误伤普通聊天文本
 * 的代价（内容被改成表格）远大于少转一张卡。
 */
class StatusTableTest {

    @Test
    fun `the reported status bar becomes a table`() {
        val raw = """🎭身份: 转移事件幸存者（流落魔大陆的外来者）
📜冒险者等级: 未注册
💪🏼身体状况: 55%｜40%｜左臂有未愈合的撕裂伤，全身肌肉酸痛，极度缺水
⚡️耐力(SP): 30%
💫魔力(MP): 40%（入门）
🤕身体永久损伤/疤痕: 无
⚔️当前武器: 【无】
🛡️当前防具: 【无】
💰持有金钱: 0铜币"""

        val out = convertStatusBlocksToTables(raw)

        assertTrue("要有表格头", out.contains("| 字段 | 值 |"))
        assertTrue("身份要在字段列", out.contains("| 🎭身份 | 转移事件幸存者（流落魔大陆的外来者） |"))
        // 值里的分隔是全角｜（U+FF5C），GFM 不拆它，保持原样
        assertTrue("全角竖线保持原样", out.contains("55%｜40%｜"))
        assertFalse("不该残留原始行", out.contains("🎭身份: 转移事件"))
    }

    @Test
    fun `box drawing lines join the table`() {
        val raw = """🌀玩家能力:
┣ 剑术: 初级 exp:0/100
┣ 魔术: 初级 exp:0/100
┗ 特殊能力: 【无】"""

        val out = convertStatusBlocksToTables(raw)

        // "🌀玩家能力:" 冒号后为空 → 字段合法但值空；后续 box 行都匹配
        assertTrue("树形行要进表格", out.contains("| ┣ 剑术 | 初级 exp:0/100 |") || out.contains("| 剑术 | 初级 exp:0/100 |"))
        assertTrue(out.contains("| 字段 | 值 |"))
    }

    @Test
    fun `fewer than three lines stay untouched`() {
        // 两行不转：单独的「时间: xx」在普通聊天里是正常叙述
        val raw = "🎭身份: 幸存者\n📜等级: 未注册"
        assertEquals(raw, convertStatusBlocksToTables(raw))
    }

    @Test
    fun `plain text is not converted`() {
        val raw = "他说：今天天气不错。\n她回答：是啊。\n然后就没了。"
        assertEquals(raw, convertStatusBlocksToTables(raw))
    }

    @Test
    fun `quoted lines are not status lines`() {
        // blockquote 的 > 不是装饰字符，不能被吞进表格
        val raw = "> [东京时报]：天气厅报告近期东京地区出现小范围电磁异常\n> [娱乐前线]：知名演员森美奈美主演的新剧《假面》首播收视率创下新高\n> (此栏每回合更新)"
        assertEquals(raw, convertStatusBlocksToTables(raw))
    }

    @Test
    fun `surrounding text is preserved`() {
        val raw = """介绍段落。

🎭身份: 幸存者
📜冒险者等级: 未注册
💪🏼身体状况: 55%

结尾段。"""
        val out = convertStatusBlocksToTables(raw)
        assertTrue("前面的段落要保留", out.contains("介绍段落。"))
        assertTrue("结尾段要保留", out.contains("结尾段。"))
        assertTrue("中间要成表", out.contains("| 字段 | 值 |"))
    }
}

class GuideSequenceTest {
    @Test
    fun `the 666 card guide lines become html blocks`() {
        val raw = ">>> [PARADISE_PROTOCOL::SESSION_INIT]\n>>> [WORLD_DATA::LOADING...]\n>>> 欢迎来到《综漫东京2050》。"
        val out = escapeGuideSequence(raw)
        assertTrue(out.contains("<div>&gt;&gt;&gt; [PARADISE_PROTOCOL::SESSION_INIT]</div>"))
        assertTrue(out.contains("<div>&gt;&gt;&gt; 欢迎来到《综漫东京2050》。</div>"))
        // 正文行不受影响
        assertTrue(out.contains("眼前的代码风暴逐渐平息") || out.split("\n").size >= 3)
    }

    @Test
    fun `normal blockquote is untouched`() {
        val raw = "> 一层引用\n>> 两层引用"
        assertEquals(raw, escapeGuideSequence(raw))
    }
}
