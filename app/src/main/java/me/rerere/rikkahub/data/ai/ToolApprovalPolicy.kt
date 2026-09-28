package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState

/**
 * 必须由用户当场交互才能完成的工具（HITL，human-in-the-loop）。
 *
 * ask_user 只是把问题抛给界面，真正的内容由问答 UI 收集；它的 execute 直接抛错。
 * 一旦被"自动批准所有工具"当成普通工具放行，工具会被立即执行并报错，
 * 问答 UI（依赖 approvalState == Pending）永远不会出现——表现为 ask_user 完全不可用。
 */
val HITL_TOOL_NAMES = setOf("ask_user")

/**
 * 该工具此刻是否必须挂起等待用户，而不是自动执行。
 *
 * 优先于 `autoApproveAllTools`：自动批准只应作用于普通工具，
 * HITL 工具的语义就是"停下来问用户"，任何设置都不该跳过这一步。
 */
fun mustWaitForUser(toolName: String, approvalState: ToolApprovalState): Boolean =
    toolName in HITL_TOOL_NAMES && approvalState is ToolApprovalState.Auto
