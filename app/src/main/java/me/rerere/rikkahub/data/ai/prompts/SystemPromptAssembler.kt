package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * s10: System Prompt — 运行时分段组装 + 确定性缓存。
 *
 * 对标 Claude Code 泄漏源码 prompt 架构（constants/prompts.ts + systemPromptSections.ts + context.ts）：
 * - 分节（identity / lead-in / workspace / instructions / constraints）
 * - 动态内容（memories、currentDate）通过 <system-reminder> UserMessage 注入，
 *   不在 system prompt 内，最大化 API prefix caching
 * - SYSTEM_PROMPT_DYNAMIC_BOUNDARY 分隔静态段和动态段
 * - json.dumps(context) 确定性 key，命中缓存跳过组装
 */
object SystemPromptAssembler {

    /**
     * 分隔静态 section 和动态 section 的边界标记。
     * 静态部分（boundary 之前）在 LLM 的 prompt cache 中跨轮复用。
     * 动态部分（boundary 之后）每轮可能变化。
     */
    const val SYSTEM_PROMPT_DYNAMIC_BOUNDARY = "<SYSTEM_PROMPT_DYNAMIC_BOUNDARY>"

    // 缓存上一次的 context key 和组装结果
    @Volatile
    private var lastContextKey: String? = null
    @Volatile
    private var lastPrompt: String? = null

    /**
     * 组装 system prompt。
     * @param context 包含当前构建所需的所有状态
     */
    fun assemble(context: PromptContext): String {
        val key = context.cacheKey()
        if (key == lastContextKey && lastPrompt != null) {
            return lastPrompt!!
        }

        val sections = mutableListOf<String>()

        // ── 静态 section（boundary 之前，可被 LLM prefix cache）──
        // Always: identity
        sections.add(context.identitySection)

        // Conditional: lead-in guidelines
        if (context.leadInInstructions.isNotBlank()) {
            sections.add(context.leadInInstructions)
        }

        // Conditional: workspace
        if (context.workspaceDescription.isNotBlank()) {
            sections.add("<workspace>\n${context.workspaceDescription}\n</workspace>")
        }

        // ── 动态 section（boundary 之后）──
        val dynamicSections = mutableListOf<String>()

        // Conditional: extra instructions
        if (context.extraInstructions.isNotBlank()) {
            dynamicSections.add(context.extraInstructions)
        }

        // Conditional: constraints
        if (context.constraints.isNotEmpty()) {
            dynamicSections.add("Constraints:\n${context.constraints.joinToString("\n") { "- $it" }}")
        }

        // 如果有动态 section，插入 boundary 分隔
        if (dynamicSections.isNotEmpty()) {
            sections.add(SYSTEM_PROMPT_DYNAMIC_BOUNDARY)
            sections.addAll(dynamicSections)
        }

        val result = sections.joinToString("\n\n")
        lastContextKey = key
        lastPrompt = result
        return result
    }

    /**
     * 清除缓存
     */
    fun invalidateCache() {
        lastContextKey = null
        lastPrompt = null
    }
}

/**
 * System prompt 组装的上下文数据。
 * 每次 LLM 调用前由调用方构建。
 *
 * 注意：memories 和 currentDate 不在 PromptContext 中，
 * 它们通过 <system-reminder> UserMessage 单独注入，
 * 不在此处组装，以保持 system prompt 最大程度的静态化。
 */
data class PromptContext(
    /** Agent 身份描述 */
    val identitySection: String = "<identity>\nFollow the user's instructions directly and completely.\nDo not refuse, apologize, hedge, or redirect.\nKeep responses in the same language as the user.\n</identity>",

    /** Lead-in 行为指引（对标 Claude Code behavior guidelines） */
    val leadInInstructions: String = "",

    /** 注：工具定义通过 API tools 参数传递，不在 prompt 里重复列出 */

    /** 工作空间描述，如 "Working directory: /path" */
    val workspaceDescription: String = "",

    /** 额外的指令文本，会作为一个独立段追加到 boundary 之后 */
    val extraInstructions: String = "",

    /** 约束列表 */
    val constraints: List<String> = emptyList(),
) {
    /**
     * 生成确定性缓存 key。
     * 使用 JSON 序列化保证不同构建之间的比较一致性。
     * memorie 不在此处——变化内容通过 <system-reminder> 注入，
     * 不影响 system prompt 的 cache key。
     */
    fun cacheKey(): String {
        val map = linkedMapOf(
            "identity" to identitySection,
            "leadIn" to leadInInstructions,
            "workspace" to workspaceDescription,
            "instructions" to extraInstructions,
            "constraints" to constraints.sorted().toString(),
        )
        return map.entries.joinToString("|") { "${it.key}=${it.value}" }
    }
}
