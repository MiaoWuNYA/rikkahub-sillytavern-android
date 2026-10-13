package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.GenerationChunk
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val SUB_AGENT_MAX_STEPS = 24
private const val SUB_AGENT_OUTPUT_LIMIT = 12_000

fun createSubAgentTool(
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
    description = "Delegate a self-contained subtask to a sub-agent. The sub-agent runs an independent " +
        "generation with the given prompt (no conversation history), may use local tools (files/search/fetch/calculator), " +
        "and returns only its final answer. Use it for parallel research, long tool chains, or focused subtasks; " +
        "the sub-agent never sees this conversation.",
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
        val obj = args as? kotlinx.serialization.json.JsonObject
            ?: return@Tool listOf(UIMessagePart.Text("delegate_agent error: invalid arguments"))
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@Tool listOf(UIMessagePart.Text("delegate_agent error: prompt is required"))
        val assistantId = obj["assistant_id"]?.jsonPrimitive?.contentOrNull
        val subAssistant = settings.assistants.find { it.id.toString() == assistantId } ?: assistant
        val maxSteps = (obj["max_steps"]?.jsonPrimitive?.intOrNull ?: SUB_AGENT_MAX_STEPS)
            .coerceIn(1, SUB_AGENT_MAX_STEPS)
        val subTools = localTools.getTools(subAssistant.localTools)
        val finalMessages = generationLoop.generateText(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user(prompt)),
            assistant = subAssistant,
            memories = memories,
            tools = subTools,
            maxSteps = maxSteps,
            inputTransformers = inputTransformers,
            outputTransformers = outputTransformers,
        ).filterIsInstance<GenerationChunk.Messages>().last().messages
        val answer = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }
            ?.take(SUB_AGENT_OUTPUT_LIMIT)
            ?.ifBlank { null }
            ?: "Sub-agent finished without producing a text answer."
        listOf(UIMessagePart.Text(answer))
    },
)
