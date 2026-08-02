package me.rerere.rikkahub

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.MacroEngine
import me.rerere.rikkahub.data.ai.transformers.MacroVars
import me.rerere.rikkahub.data.ai.transformers.PlaceholderCtx
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GenerationType
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.TavernCharacterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 宏引擎 2.0 完整语义测试（对齐 SillyTavern Macro 2.0）。
 */
class MacroEngineTest {

    /** 内存版变量存储，用于测试 */
    private class MemVars : MacroVars {
        private val locals = mutableMapOf<String, MutableMap<String, String>>()
        private val globals = mutableMapOf<String, String>()

        override fun get(chatKey: String?, name: String): String? {
            return if (chatKey == null) globals[name] else locals[chatKey]?.get(name)
        }

        override fun set(chatKey: String?, name: String, value: String) {
            if (chatKey == null) globals[name] = value
            else locals.getOrPut(chatKey) { mutableMapOf() }[name] = value
        }

        override fun inc(chatKey: String?, name: String): String {
            val next = (get(chatKey, name)?.toLongOrNull() ?: 0L) + 1
            set(chatKey, name, next.toString())
            return next.toString()
        }

        override fun dec(chatKey: String?, name: String): String {
            val next = (get(chatKey, name)?.toLongOrNull() ?: 0L) - 1
            set(chatKey, name, next.toString())
            return next.toString()
        }

        override fun add(chatKey: String?, name: String, value: String) {
            val current = get(chatKey, name)
            val left = current?.toLongOrNull()
            val right = value.toLongOrNull()
            set(
                chatKey,
                name,
                when {
                    left != null && right != null -> (left + right).toString()
                    current == null -> value
                    else -> current + value
                }
            )
        }

        override fun has(chatKey: String?, name: String): Boolean = get(chatKey, name) != null

        override fun delete(chatKey: String?, name: String) {
            if (chatKey == null) globals.remove(name)
            else locals[chatKey]?.remove(name)
        }
    }

    private fun engine(
        vars: MacroVars = MemVars(),
        legacy: Map<String, me.rerere.rikkahub.data.ai.transformers.PlaceholderInfo> = emptyMap(),
    ) = MacroEngine(legacy, vars)

    private fun ctx(
        assistant: Assistant = Assistant(),
        messages: List<UIMessage> = emptyList(),
        conversationId: Uuid? = null,
        generationType: GenerationType? = null,
        settings: Settings = Settings(),
    ): PlaceholderCtx {
        val ctor = PlaceholderCtx::class.java.declaredConstructors.first { it.parameterCount == 8 }
        ctor.isAccessible = true
        return ctor.newInstance(
            null as Context?,
            null as SettingsStore?,
            settings,
            Model(),
            assistant,
            messages,
            conversationId,
            generationType,
        ) as PlaceholderCtx
    }

    // ---------- 未知宏保留（Pebble 保护） ----------

    @Test
    fun unknownMacrosArePreserved() {
        val e = engine()
        assertEquals("{{ message }}", e.substitute("{{ message }}", ctx()))
        assertEquals("{{role}}", e.substitute("{{role}}", ctx()))
        assertEquals("前后 {{ unknown::x }} 后", e.substitute("前后 {{ unknown::x }} 后", ctx()))
    }

    @Test
    fun unclosedBraceIsPreserved() {
        val e = engine()
        assertEquals("abc {{unclosed", e.substitute("abc {{unclosed", ctx()))
    }

    // ---------- 旧宏兼容 ----------

    @Test
    fun legacyMacrosStillWork() {
        val legacy = mapOf(
            "char" to me.rerere.rikkahub.data.ai.transformers.PlaceholderInfo(
                displayName = {},
                resolver = { it.assistant.name },
            ),
            "cur_date" to me.rerere.rikkahub.data.ai.transformers.PlaceholderInfo(
                displayName = {},
                resolver = { "2026-08-03" },
            ),
        )
        val e = engine(legacy = legacy)
        val c = ctx(assistant = Assistant(name = "阿丽娜"))
        assertEquals("阿丽娜 2026-08-03", e.substitute("{{char}} {{cur_date}}", c))
    }

    // ---------- {{if}} 条件 ----------

    @Test
    fun ifInlineTruthyFalsy() {
        val e = engine()
        assertEquals("A", e.substitute("{{if::true::A{{else}}B}}", ctx()))
        assertEquals("B", e.substitute("{{if::false::A{{else}}B}}", ctx()))
        assertEquals("B", e.substitute("{{if::0::A{{else}}B}}", ctx()))
        assertEquals("B", e.substitute("{{if::off::A{{else}}B}}", ctx()))
        assertEquals("B", e.substitute("{{if::  ::A{{else}}B}}", ctx()))
    }

    @Test
    fun ifBlockWithElse() {
        val e = engine()
        assertEquals("甲", e.substitute("{{if true}}甲{{else}}乙{{/if}}", ctx()))
        assertEquals("乙", e.substitute("{{if false}}甲{{else}}乙{{/if}}", ctx()))
        assertEquals("甲", e.substitute("{{if true}}甲{{/if}}", ctx()))
        assertEquals("", e.substitute("{{if false}}甲{{/if}}", ctx()))
    }

    @Test
    fun ifInlineWithElseMacro() {
        val e = engine()
        assertEquals("B", e.substitute("{{if::false::A{{else}}B}}", ctx()))
    }

    @Test
    fun ifInversion() {
        val e = engine()
        assertEquals("B", e.substitute("{{if::!true::A{{else}}B}}", ctx()))
        assertEquals("A", e.substitute("{{if::!false::A{{else}}B}}", ctx()))
        assertEquals("A", e.substitute("{{if !false}}A{{/if}}", ctx()))
    }

    @Test
    fun ifNestedMacroCondition() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("有", e.substitute("{{setvar::x::5}}{{if {{getvar::x}}::有{{else}}无}}", c))
        assertEquals("无", e.substitute("{{setvar::x::}}{{if {{getvar::x}}::有{{else}}无}}", c))
    }

    @Test
    fun ifComparisonOperators() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("大", e.substitute("{{setvar::n::7}}{{if::{{getvar::n}}>=5::大{{else}}小}}", c))
        assertEquals("小", e.substitute("{{setvar::n::3}}{{if::{{getvar::n}}>=5::大{{else}}小}}", c))
        assertEquals("等", e.substitute("{{setvar::n::5}}{{if::{{getvar::n}}==5::等{{else}}不等}}", c))
        assertEquals("且", e.substitute("{{setvar::a::1}}{{setvar::b::2}}{{if::{{getvar::a}}==1&&{{getvar::b}}==2::且{{else}}或}}", c))
        assertEquals("或", e.substitute("{{setvar::a::1}}{{setvar::b::9}}{{if::{{getvar::a}}==1||{{getvar::b}}==2::或{{else}}无}}", c))
    }

    @Test
    fun ifVariableShorthand() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("在", e.substitute("{{setvar::受伤::true}}{{if .受伤::在{{else}}不在}}", c))
        assertEquals("全局", e.substitute("{{setglobalvar::flag::1}}{{if \$flag::全局{{else}}无}}", c))
    }

    @Test
    fun ifBareMacroNameCondition() {
        val legacy = mapOf(
            "personality" to me.rerere.rikkahub.data.ai.transformers.PlaceholderInfo(
                displayName = {},
                resolver = { "外冷内热" },
            ),
            "emptyPersonality" to me.rerere.rikkahub.data.ai.transformers.PlaceholderInfo(
                displayName = {},
                resolver = { "" },
            ),
        )
        val e = engine(legacy = legacy)
        assertEquals("有性格", e.substitute("{{if personality}}有性格{{/if}}", ctx()))
        assertEquals("", e.substitute("{{if emptyPersonality}}有性格{{/if}}", ctx()))
        // 未注册的名字按官方语义视为非空字符串 → 真
        assertEquals("有性格", e.substitute("{{if nope}}有性格{{/if}}", ctx()))
    }

    @Test
    fun ifLazyEvaluationOnlyChosenBranch() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        // 未选中分支里的 setvar 不应执行
        assertEquals("", e.substitute("{{if false}}A{{setvar::x::1}}{{/if}}{{getvar::x}}", c))
        assertEquals("", e.substitute("{{getvar::x}}", c))
        // 选中分支应执行
        assertEquals("A1", e.substitute("{{if true}}A{{setvar::y::1}}{{/if}}{{getvar::y}}", c))
        assertEquals("1", e.substitute("{{getvar::y}}", c))
    }

    @Test
    fun nestedIfBlocks() {
        val e = engine()
        assertEquals("B", e.substitute("{{if true}}{{if false}}A{{else}}B{{/if}}{{/if}}", ctx()))
        assertEquals("A", e.substitute("{{if true}}{{if true}}A{{else}}B{{/if}}{{/if}}", ctx()))
    }

    // ---------- 变量 ----------

    @Test
    fun variableSetGet() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("", e.substitute("{{getvar::missing}}", c))
        assertEquals("123", e.substitute("{{setvar::a::123}}{{getvar::a}}", c))
    }

    @Test
    fun variableIncDecAdd() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("1", e.substitute("{{incvar::n}}", c))
        assertEquals("2", e.substitute("{{incvar::n}}", c))
        assertEquals("1", e.substitute("{{decvar::n}}", c))
        assertEquals("5", e.substitute("{{addvar::n::5}}{{getvar::n}}", c))
        // 字符串拼接
        assertEquals("ab", e.substitute("{{setvar::s::a}}{{addvar::s::b}}{{getvar::s}}", c))
    }

    @Test
    fun variableHasDelete() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("false", e.substitute("{{hasvar::a}}", c))
        assertEquals("true", e.substitute("{{setvar::a::1}}{{hasvar::a}}", c))
        assertEquals("", e.substitute("{{deletevar::a}}{{getvar::a}}", c))
        assertEquals("false", e.substitute("{{hasvar::a}}", c))
    }

    @Test
    fun globalVsLocalIsolation() {
        val e = engine()
        val c1 = ctx(conversationId = Uuid.random())
        val c2 = ctx(conversationId = Uuid.random())
        e.substitute("{{setvar::local::1}}{{setglobalvar::global::9}}", c1)
        // 另一个会话：local 不可见，global 可见
        assertEquals("", e.substitute("{{getvar::local}}", c2))
        assertEquals("9", e.substitute("{{getglobalvar::global}}", c2))
    }

    @Test
    fun variableAliases() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals("true", e.substitute("{{setvar::a::1}}{{varexists::a}}", c))
        assertEquals("", e.substitute("{{flushvar::a}}{{getvar::a}}", c))
        assertEquals("true", e.substitute("{{setglobalvar::g::1}}{{globalvarexists::g}}", c))
        assertEquals("", e.substitute("{{flushglobalvar::g}}{{getglobalvar::g}}", c))
    }

    // ---------- 作用域与工具宏 ----------

    @Test
    fun scopedTrim() {
        val e = engine()
        assertEquals("abc", e.substitute("{{trim}}  abc  {{/trim}}", ctx()))
    }

    @Test
    fun commentMacros() {
        val e = engine()
        assertEquals("", e.substitute("{{// 这是注释}}", ctx()))
        assertEquals("前后", e.substitute("前{{// 注释}}后", ctx()))
        assertEquals("", e.substitute("{{//}}abc{{///}}", ctx()))
    }

    @Test
    fun utilityMacros() {
        val e = engine()
        assertEquals(" ", e.substitute("{{space}}", ctx()))
        assertEquals("    ", e.substitute("{{space::4}}", ctx()))
        assertEquals("\n", e.substitute("{{newline}}", ctx()))
        assertEquals("", e.substitute("{{noop}}", ctx()))
        assertEquals("cba", e.substitute("{{reverse::abc}}", ctx()))
    }

    @Test
    fun rollMacro() {
        val e = engine()
        repeat(20) {
            val v = e.substitute("{{roll 1d6}}", ctx()).toInt()
            assertTrue(v in 1..6)
        }
        repeat(20) {
            val v = e.substitute("{{roll::2d6+3}}", ctx()).toInt()
            assertTrue(v in 5..15)
        }
    }

    @Test
    fun randomMacro() {
        val e = engine()
        val out = e.substitute("{{random::A::B::C}}", ctx())
        assertTrue(out in listOf("A", "B", "C"))
    }

    @Test
    fun pickIsStable() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        val text = "{{pick::红::绿::蓝}}"
        assertEquals(e.substitute(text, c), e.substitute(text, c))
    }

    // ---------- 环境宏 ----------

    @Test
    fun greetingMacros() {
        val tav = TavernCharacterData(
            firstMessage = "你好，旅人。",
            alternateGreetings = listOf("备选开场一", "备选开场二"),
        )
        val e = engine()
        val c = ctx(assistant = Assistant(tavernData = tav))
        assertEquals("你好，旅人。", e.substitute("{{greeting}}", c))
        assertEquals("你好，旅人。", e.substitute("{{charFirstMessage::0}}", c))
        assertEquals("备选开场一", e.substitute("{{greeting::1}}", c))
        assertEquals("备选开场二", e.substitute("{{greeting::2}}", c))
        assertEquals("", e.substitute("{{greeting::9}}", c))
    }

    @Test
    fun stateMacros() {
        val e = engine()
        assertEquals("100", e.substitute("{{maxResponseTokens}}", ctx(assistant = Assistant(maxTokens = 100))))
        assertEquals("50", e.substitute("{{maxContextTokens}}", ctx(assistant = Assistant(contextMessageLimit = 50))))
        assertEquals("0-1", e.substitute("{{allChatRange}}", ctx(messages = listOf(UIMessage.user("a"), UIMessage.assistant("b")))))
        assertEquals("swipe", e.substitute("{{lastGenerationType}}", ctx(generationType = GenerationType.SWIPE)))
        assertEquals("true", e.substitute("{{isMobile}}", ctx()))
    }

    @Test
    fun timeMacros() {
        val e = engine()
        assertEquals("3小时", e.substitute(
            "{{timeDiff::2023-01-01 12:00:00::2023-01-01 15:00:00}}",
            ctx()
        ))
        assertEquals("1天", e.substitute(
            "{{timeDiff::2023-01-01 12:00:00::2023-01-02 12:00:00}}",
            ctx()
        ))
        assertTrue(e.substitute("{{time}}", ctx()).matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(e.substitute("{{time::UTC+2}}", ctx()).matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun groupMacros() {
        val groupId = Uuid.random()
        val a1 = Assistant(name = "阿丽娜")
        val a2 = Assistant(name = "凯")
        val a3 = Assistant(name = "默者")
        val settings = Settings(
            groupChats = listOf(
                GroupChat(
                    name = "小队",
                    memberIds = listOf(a1.id, a2.id, a3.id),
                    disabledMemberIds = listOf(a3.id),
                )
            )
        )
        val e = engine()
        val c = ctx(assistant = a1, settings = settings)
        // 未禁言成员（官方 groupNotMuted）
        assertEquals("阿丽娜, 凯", e.substitute("{{groupNotMuted}}", c))
        // 除自己外全部成员（官方 notChar）
        assertEquals("凯, 默者", e.substitute("{{notChar}}", c))
    }

    @Test
    fun nestedMacrosInArguments() {
        val e = engine()
        val c = ctx(conversationId = Uuid.random())
        assertEquals(
            "B是5",
            e.substitute(
                "{{setvar::n::5}}{{if::{{getvar::n}}>=3::{{if::{{getvar::n}}==5::B是5{{else}}A}}{{else}}小}}",
                c
            )
        )
    }

    @Test
    fun preserveWhitespaceFlag() {
        val e = engine()
        // # 标志：不 trim 分支结果
        assertEquals("  a  ", e.substitute("{{if::#::true::  a  {{else}}x}}", ctx()))
        assertEquals("a", e.substitute("{{if::true::  a  {{else}}x}}", ctx()))
    }
}
