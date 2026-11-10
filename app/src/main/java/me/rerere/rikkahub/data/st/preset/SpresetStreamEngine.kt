package me.rerere.rikkahub.data.st.preset

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.StreamChunk
import java.util.UUID

/**
 * OutputPreprocessing 流式执行器。
 *
 * - 在同一个持久 QuickJS runtime 内逐 TextDelta 调用 outputPreprocessing 脚本；
 * - 通过返回的 output 做前缀差量，只发新增文本；state 在整条流内持久；
 * - consumeToolCalls=true 时吞掉 ToolCallStart/Delta/End，Finish 前调用 toolCallFormatter
 *   把 calls 注入为 TextStart/Delta/End；
 * - 任一脚本异常都尽量回退到原始 chunk，绝不抛到生成流程。
 */
internal object SpresetStreamEngine {
    private const val OUTPUT_FN = "__rikkahub_stream_output"
    private const val FORMATTER_FN = "__rikkahub_stream_formatter"

    fun process(input: Flow<StreamChunk>, config: StSpresetConfig): Flow<StreamChunk> = channelFlow {
        if (!config.enabled) {
            input.collect { send(it) }
            return@channelFlow
        }
        val preprocessing = config.outputPreprocessing
        val outputScriptActive = preprocessing.enabled && preprocessing.script.isNotBlank()
        val consumeToolCalls = preprocessing.enabled && preprocessing.consumeToolCalls
        val formatterConfigured = consumeToolCalls &&
            preprocessing.toolCallFormatter.enabled &&
            preprocessing.toolCallFormatter.script.isNotBlank()

        if (!outputScriptActive && !consumeToolCalls) {
            input.collect { send(it) }
            return@channelFlow
        }

        var consumedAny = false
        var outputRuntimeOk = outputScriptActive
        var formatterRuntimeOk = formatterConfigured
        val outputTextId = "spreset-text-${UUID.randomUUID()}"
        val formattedTextId = "spreset-tools-${UUID.randomUUID()}"
        val rawBuffer = StringBuilder()
        val sentText = StringBuilder()
        var stateJson = "{}"
        var textStarted = false
        var textEnded = false
        var finishSeen = false
        val toolCalls = LinkedHashMap<String, MutableToolCall>()
        val cachedToolChunks = mutableListOf<StreamChunk>()

        suspend fun endOutputText() {
            if (textStarted && !textEnded) {
                send(StreamChunk.TextEnd(outputTextId))
                textEnded = true
            }
        }

        suspend fun emitOutputSuffix(desired: String) {
            if (desired.isEmpty()) return
            val alreadySent = sentText.toString()
            if (desired.startsWith(alreadySent)) {
                val suffix = desired.substring(alreadySent.length)
                if (suffix.isNotEmpty()) {
                    if (!textStarted) {
                        send(StreamChunk.TextStart(outputTextId))
                        textStarted = true
                    }
                    send(StreamChunk.TextDelta(outputTextId, suffix))
                    sentText.append(suffix)
                }
                return
            }

            // output 回退（脚本 hold）时不做删除；只在存在真正新增尾部时增量发送。
            val common = commonPrefixLength(alreadySent, desired)
            if (desired.length > common) {
                val suffix = desired.substring(common)
                if (!textStarted) {
                    send(StreamChunk.TextStart(outputTextId))
                    textStarted = true
                }
                send(StreamChunk.TextDelta(outputTextId, suffix))
                sentText.append(suffix)
            }
        }

        suspend fun QuickJs.finalizeOutputStream() {
            if (!outputRuntimeOk) return
            val argsJson = buildJsonObject {
                put("buffer", rawBuffer.toString())
                put("final", true)
                put("state", parseJsonElementOrNull(stateJson) ?: JsonObject(emptyMap()))
            }.toString()
            try {
                val raw = callSpresetFunction(OUTPUT_FN, argsJson) ?: return
                val processed = parseStreamProcessedResult(raw, rawBuffer.toString(), stateJson) ?: return
                stateJson = processed.stateJson
                emitOutputSuffix(processed.resolvedFinalOutput())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                outputRuntimeOk = false
            }
        }

        suspend fun emitFormattedText(text: String) {
            if (text.isEmpty()) return
            send(StreamChunk.TextStart(formattedTextId))
            send(StreamChunk.TextDelta(formattedTextId, text))
            send(StreamChunk.TextEnd(formattedTextId))
        }

        suspend fun replayCachedToolChunks() {
            cachedToolChunks.forEach { send(it) }
        }

        suspend fun QuickJs.flushPendingToolCalls() {
            if (!consumeToolCalls) return
            if (toolCalls.isEmpty()) {
                if (cachedToolChunks.isNotEmpty()) replayCachedToolChunks()
                return
            }
            var formatted: String? = null
            if (formatterRuntimeOk) {
                val callsJson = buildCallsJson(toolCalls.values).toString()
                try {
                    val argsJson = buildJsonObject {
                        put("calls", parseJsonElementOrNull(callsJson) ?: buildJsonArray { })
                    }.toString()
                    formatted = callSpresetFunction(FORMATTER_FN, argsJson)
                        ?.let(::normalizeStreamScriptString)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    formatterRuntimeOk = false
                }
            }
            if (formatted != null) emitFormattedText(formatted) else replayCachedToolChunks()
        }

        val completed = runCatching {
            quickJs(Dispatchers.Default) {
                memoryLimit = SPRESET_JS_MEMORY_LIMIT_BYTES
                maxStackSize = SPRESET_JS_MAX_STACK_BYTES
                evaluationTimeoutMillis = SPRESET_JS_TIMEOUT_MS
                installSpresetConsole()

                if (outputRuntimeOk) {
                    try {
                        installSpresetFunction(OUTPUT_FN, preprocessing.script)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        outputRuntimeOk = false
                    }
                }
                if (formatterRuntimeOk) {
                    try {
                        installSpresetFunction(FORMATTER_FN, preprocessing.toolCallFormatter.script)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        formatterRuntimeOk = false
                    }
                }

                input.collect { chunk ->
                    consumedAny = true
                    when (chunk) {
                        is StreamChunk.TextStart -> {
                            if (!outputRuntimeOk) send(chunk)
                        }

                        is StreamChunk.TextDelta -> {
                            if (outputRuntimeOk) {
                                rawBuffer.append(chunk.text)
                                val argsJson = buildJsonObject {
                                    put("buffer", rawBuffer.toString())
                                    put("final", false)
                                    put("state", parseJsonElementOrNull(stateJson) ?: JsonObject(emptyMap()))
                                }.toString()
                                try {
                                    val raw = callSpresetFunction(OUTPUT_FN, argsJson)
                                    if (raw != null) {
                                        val processed = parseStreamProcessedResult(
                                            raw,
                                            rawBuffer.toString(),
                                            stateJson,
                                        )
                                        if (processed != null) {
                                            stateJson = processed.stateJson
                                            emitOutputSuffix(processed.output)
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    outputRuntimeOk = false
                                    send(chunk)
                                }
                            } else {
                                send(chunk)
                            }
                        }

                        is StreamChunk.TextEnd -> {
                            if (!outputRuntimeOk) send(chunk)
                        }

                        is StreamChunk.ToolCallStart -> {
                            if (consumeToolCalls) {
                                val call = toolCalls.getOrPut(chunk.id) { MutableToolCall(chunk.id) }
                                if (chunk.toolName.isNotBlank()) call.name = chunk.toolName
                                cachedToolChunks += chunk
                            } else {
                                send(chunk)
                            }
                        }

                        is StreamChunk.ToolCallDelta -> {
                            if (consumeToolCalls) {
                                val call = toolCalls.getOrPut(chunk.id) { MutableToolCall(chunk.id) }
                                call.name += chunk.toolNameDelta
                                call.arguments.append(chunk.inputDelta)
                                cachedToolChunks += chunk
                            } else {
                                send(chunk)
                            }
                        }

                        is StreamChunk.ToolCallEnd -> {
                            if (consumeToolCalls) {
                                cachedToolChunks += chunk
                            } else {
                                send(chunk)
                            }
                        }

                        is StreamChunk.ToolCallOutput -> {
                            if (consumeToolCalls) {
                                cachedToolChunks += chunk
                            } else {
                                send(chunk)
                            }
                        }

                        is StreamChunk.Finish -> {
                            if (outputRuntimeOk) finalizeOutputStream()
                            endOutputText()
                            flushPendingToolCalls()
                            finishSeen = true
                            send(chunk)
                        }

                        else -> send(chunk)
                    }
                }
                if (!finishSeen) {
                    // 部分 provider 正常结束但不发 Finish：必须在这里补 final / end / formatter，
                    // 否则最后一段文本和 consumeToolCalls 的格式化结果会丢。不要伪造 Finish。
                    if (outputRuntimeOk) finalizeOutputStream()
                    endOutputText()
                    flushPendingToolCalls()
                }
            }
        }

        val failure = completed.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (completed.isFailure && !consumedAny) {
            // 运行时创建即失败、尚未消费 input：完整回退。
            input.collect { send(it) }
        } else if (failure != null) {
            // 已消费部分流后 runtime 异常：无法安全重放，交给上层重试/记录，避免静默截断。
            throw failure
        }
    }

    private data class MutableToolCall(
        val id: String,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private fun buildCallsJson(calls: Collection<MutableToolCall>) = buildJsonArray {
        calls.forEach { call ->
            add(buildJsonObject {
                put("id", call.id)
                put("name", call.name)
                put("arguments", parseArgumentsElement(call.arguments.toString()))
            })
        }
    }

    private fun parseStreamProcessedResult(
        raw: String,
        buffer: String,
        stateJson: String,
    ): Processed? {
        val element = parseJsonElementOrNull(raw)
        if (element is JsonObject) {
            val outputElement = element.findPresetElement("output", "text", "value", "content")
            val holdElement = element.findPresetElement("hold", "remainder", "pending")
            val stateElement = element.findPresetElement("state", "stateJson", "state_json")
            val output = when {
                outputElement != null -> outputElement.stringValue().orEmpty()
                else -> buffer
            }
            val hold = holdElement.stringValue().orEmpty()
            return Processed(
                output = output,
                hold = hold,
                stateJson = stateElement?.toString() ?: stateJson,
            )
        }
        if (element is JsonPrimitive && element.isString) {
            return Processed(output = element.contentOrNull.orEmpty(), stateJson = stateJson)
        }
        if (raw.isEmpty()) return Processed(output = "", stateJson = stateJson)
        return Processed(output = raw, stateJson = stateJson)
    }

    private fun normalizeStreamScriptString(raw: String): String {
        if (raw.isEmpty()) return ""
        val element = parseJsonElementOrNull(raw) ?: return raw
        return when (element) {
            is JsonPrimitive -> if (element.isString) element.contentOrNull.orEmpty() else element.toString()
            else -> raw
        }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }
}
