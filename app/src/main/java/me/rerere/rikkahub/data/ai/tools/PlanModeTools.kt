package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.PermissionMode
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

object PlanModeState {
    @Volatile
    var isInPlanMode: Boolean = false
    @Volatile
    var effectiveMode: PermissionMode = PermissionMode.DANGER_FULL_ACCESS

    fun enterPlan() {
        isInPlanMode = true
        effectiveMode = PermissionMode.READ_ONLY
    }

    fun exitPlan(restoreMode: PermissionMode = PermissionMode.WORKSPACE_WRITE) {
        isInPlanMode = false
        effectiveMode = restoreMode
    }
}

fun createPlanModeTools(): List<Tool> = listOf(
    Tool(
        name = "plan_mode",
        description = "Switch between planning mode (read-only) and execution mode. Use action=enter to create a plan, action=exit to start executing.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("enter"); add("exit") })
                        put("description", "enter=planning mode (read-only), exit=execution mode")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: error("action required")
            when (action) {
                "enter" -> {
                    PlanModeState.enterPlan()
                    listOf(UIMessagePart.Text("[进入计划模式] 当前仅允许只读操作，不能修改文件或执行危险命令。"))
                }
                "exit" -> {
                    PlanModeState.exitPlan()
                    listOf(UIMessagePart.Text("[退出计划模式] 开始执行计划..."))
                }
                else -> error("Unknown action: $action")
            }
        },
    ),
)
