package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

private const val SUB_AGENT_MAX_STEPS = 24
private const val SUB_AGENT_OUTPUT_LIMIT = 12_000
private const val SUB_AGENT_CHECK_WAIT_LIMIT = 30

/**
 * 子代理工具：派活即返回，子代理在自己的协程里跑完，结果回传由宿主回调处理。
 */
fun createSubAgentTool(
    hub: SubAgentHub,
    scope: CoroutineScope,
    conversationId: String,
    onFinished: (SubAgentHub.Run) -> Unit,
    generationLoop: GenerationLoop,
    settings: Settings,
    assistant: Assistant,
    model: Model,
    memories: List<me.rerere.rikkahub.data.model.MemoryFile>,
    inputTransformers: List<InputMessageTransformer>,
    outputTransformers: List<OutputMessageTransformer>,
    localTools: LocalTools,
): Tool = Tool(
    name = "delegate_agent",
    description = "Delegate a self-contained subtask to a background sub-agent and return immediately. " +
        "The sub-agent runs an independent generation with the given prompt (no conversation history, it never " +
        "sees this chat), may use local tools (files/search/fetch/calculator), and returns only its final answer. " +
        "The run keeps working after this turn ends and its answer is delivered back into this conversation " +
        "automatically; use check_subagent with the returned run id if you need it sooner. Prefer this over doing " +
        "long research or heavy tool chains inline.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "Complete, self-contained instructions for the sub-agent")
                }
                putJsonObject("assistant_id") {
                    put("type", "string")
                    put("description", "Optional assistant UUID whose persona/system prompt the sub-agent uses; defaults to the current assistant")
                }
                putJsonObject("max_steps") {
                    put("type", "integer")
                    put("description", "Optional tool-loop budget for the sub-agent (1-24, default 24)")
                }
            },
            required = listOf("prompt"),
        )
    },
    execute = { args ->
        val obj = args as? JsonObject
            ?: return@Tool listOf(UIMessagePart.Text("delegate_agent error: invalid arguments"))
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@Tool listOf(UIMessagePart.Text("delegate_agent error: prompt is required"))
        val assistantId = obj["assistant_id"]?.jsonPrimitive?.contentOrNull
        val subAssistant = settings.assistants.find { it.id.toString() == assistantId } ?: assistant
        val maxSteps = (obj["max_steps"]?.jsonPrimitive?.intOrNull ?: SUB_AGENT_MAX_STEPS)
            .coerceIn(1, SUB_AGENT_MAX_STEPS)

        val run = hub.register(conversationId, subAssistant.name, prompt)
        scope.launch {
            try {
                hub.finish(
                    run,
                    runSubAgent(
                        generationLoop = generationLoop,
                        settings = settings,
                        model = model,
                        assistant = subAssistant,
                        prompt = prompt,
                        maxSteps = maxSteps,
                        memories = memories,
                        inputTransformers = inputTransformers,
                        outputTransformers = outputTransformers,
                        localTools = localTools,
                    ),
                )
            } catch (e: CancellationException) {
                hub.fail(run, "cancelled")
                throw e
            } catch (e: Throwable) {
                hub.fail(run, e.message ?: e.javaClass.simpleName)
            } finally {
                onFinished(run)
            }
        }

        listOf(
            UIMessagePart.Text(
                "Sub-agent started in the background, run_id=${run.id}. Everything else in this turn continues " +
                    "normally. Its answer will be appended to this conversation as a system message as soon as it " +
                    "finishes; call check_subagent with run_id=${run.id} if you want to collect it earlier."
            )
        )
    },
)

/**
 * 取回后台子代理的结果：不带 run_id 时列出本对话的运行，带 run_id 时可按秒等待其完成。
 */
fun createSubAgentCheckTool(hub: SubAgentHub, conversationId: String): Tool = Tool(
    name = "check_subagent",
    description = "Check background sub-agents started by delegate_agent. Without run_id it lists the runs of " +
        "this conversation; with run_id it returns that run's state and answer, waiting up to wait_seconds " +
        "(0-30) for it to finish. Call it before answering when a delegated result matters.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("run_id") {
                    put("type", "string")
                    put("description", "Run id returned by delegate_agent; omit to list all runs of this conversation")
                }
                putJsonObject("wait_seconds") {
                    put("type", "integer")
                    put("description", "Wait up to this many seconds for a still-running run (0-30, default 0)")
                }
            },
        )
    },
    execute = { args ->
        val obj = args as? JsonObject
        val runId = obj?.get("run_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val waitSeconds = (obj?.get("wait_seconds")?.jsonPrimitive?.intOrNull ?: 0)
            .coerceIn(0, SUB_AGENT_CHECK_WAIT_LIMIT)

        if (runId == null) {
            val runs = hub.recent(conversationId)
            val text = if (runs.isEmpty()) {
                "No sub-agent runs in this conversation."
            } else {
                runs.joinToString("\n\n") { run ->
                    run.picked = true
                    run.resultText()
                }
            }
            return@Tool listOf(UIMessagePart.Text(text))
        }

        val run = hub.get(runId)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    "Unknown run id $runId. Known runs: " +
                        hub.recent(conversationId).joinToString(", ") { "${it.id}(${it.state.name.lowercase()})" }
                            .ifBlank { "none" }
                )
            )

        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (run.state == SubAgentHub.State.RUNNING && System.currentTimeMillis() < deadline) {
            delay(300)
        }
        run.picked = true
        listOf(UIMessagePart.Text(run.resultText()))
    },
)

private suspend fun runSubAgent(
    generationLoop: GenerationLoop,
    settings: Settings,
    model: Model,
    assistant: Assistant,
    prompt: String,
    maxSteps: Int,
    memories: List<me.rerere.rikkahub.data.model.MemoryFile>,
    inputTransformers: List<InputMessageTransformer>,
    outputTransformers: List<OutputMessageTransformer>,
    localTools: LocalTools,
): String {
    val subTools = localTools.getTools(assistant.localTools)
    val finalMessages = generationLoop.generateText(
        settings = settings,
        model = model,
        messages = listOf(UIMessage.user(prompt)),
        assistant = assistant,
        memories = memories,
        tools = subTools,
        maxSteps = maxSteps,
        inputTransformers = inputTransformers,
        outputTransformers = outputTransformers,
    ).filterIsInstance<GenerationChunk.Messages>().last().messages
    return finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }
        ?.take(SUB_AGENT_OUTPUT_LIMIT)
        ?.ifBlank { null }
        ?: "Sub-agent finished without producing a text answer."
}

/** 回传主线的统一文本：列表与单条查询共用。 */
fun SubAgentHub.Run.resultText(): String = buildString {
    append("[sub-agent ").append(id).append("] task: ").append(prompt.take(300).replace('\n', ' ')).append('\n')
    when (state) {
        SubAgentHub.State.DONE -> append("status: finished\nresult:\n").append(answer.orEmpty())
        SubAgentHub.State.FAILED -> append("status: failed\nreason: ").append(error.orEmpty())
        SubAgentHub.State.RUNNING -> append("status: still running")
    }
}
