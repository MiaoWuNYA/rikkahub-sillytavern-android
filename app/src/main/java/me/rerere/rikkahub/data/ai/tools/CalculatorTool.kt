package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun createCalculatorTool(context: Context): Tool = Tool(
    name = "calculator",
    description = "" +
        "Mathematical calculator with 700+ functions. Use this tool for complex or multi-step computations that cannot be done mentally.\n" +
        "Simple arithmetic should be answered directly without calling this tool.\n\n" +
        "Use this tool when you need to:\n" +
        "- Complex math: physics formulas, finance (NPV, IRR, Black-Scholes), statistics (ANOVA, PCA, regression)\n" +
        "- Matrix operations: add, mul, det, inv, eigenvalues\n" +
        "- Calculus: derivative, integral, ODE (RK4), gradient descent\n" +
        "- Number theory: is_prime, factorize, gcd/lcm, CRT\n" +
        "- Astronomy: stellar/coordinate/cosmology calculations\n" +
        "- Physics: kinematics, relativity (Lorentz, time dilation), quantum, orbital mechanics\n" +
        "- Geometry: area/volume for any shape\n" +
        "- Unit conversion, combinatorics, financial calculations\n\n" +
        "When NOT to use:\n" +
        "- Simple arithmetic (2+2, percentages) — answer directly\n" +
        "- Python execution for math (use calculator instead)\n\n" +
        "Args:\n" +
        "- expression: Math expression to evaluate. Supports multi-statement: a=1+2; a*3\n" +
        "- timeout_ms: Max execution time (default: 5000)",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("expression", buildJsonObject {
                    put("type", "string")
                    put("description", "Math expression, unit conversion, or operation to evaluate")
                })
                put("precision", buildJsonObject {
                    put("type", "integer")
                    put("description", "Decimal precision (default: 10). Set to 0 for integer output.")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("auto"); add("deg"); add("rad"); add("frac"); add("exact")
                    })
                    put("description", "Computation mode: auto (default), deg (trig in degrees), rad (trig in radians), frac (fraction output), exact")
                })
            },
            required = listOf("expression"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val expression = obj["expression"]?.jsonPrimitive?.contentOrNull
            ?: error("expression required")
        val precision = obj["precision"]?.jsonPrimitive?.intOrNull ?: 10
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "auto"

        val executor = Executors.newSingleThreadExecutor()
        try {
            // Python must be started on main thread (Chaquopy requirement)
            if (!Python.isStarted()) {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Python.start(AndroidPlatform(context))
                    }
                }
            }

            val future = executor.submit<String> {
                try {
                    val py = Python.getInstance()
                    val calcModule = py.getModule("calculator")
                    val resultJson = calcModule.callAttr("calculate", expression, precision, mode).toString()
                    resultJson
                } catch (e: Exception) {
                    buildJsonObject {
                        put("result", jstr("Error: ${e.message}"))
                        put("type", jstr("error"))
                        put("error", jstr(e.message ?: "Unknown error"))
                    }.toString()
                }
            }
            val resultStr = future.get(30, TimeUnit.SECONDS)
            val result = try {
                Json.parseToJsonElement(resultStr).jsonObject
            } catch (_: Exception) {
                // Python returned raw string fallback
                buildJsonObject {
                    put("result", jstr(resultStr))
                    put("type", jstr("text"))
                }
            }

            // Format the output
            val resultText = result["result"]?.jsonPrimitive?.contentOrNull ?: "No result"
            val resultType = result["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"

            val response = buildJsonObject {
                put("expression", jstr(expression))
                put("result", jstr(resultText))
                put("type", jstr(resultType))
                result["error"]?.jsonPrimitive?.contentOrNull?.let {
                    put("error", jstr(it))
                }
                result["from"]?.jsonPrimitive?.contentOrNull?.let {
                    put("from", jstr(it))
                }
                result["to"]?.jsonPrimitive?.contentOrNull?.let {
                    put("to", jstr(it))
                }
            }
            listOf(UIMessagePart.Text(response.toString()))
        } catch (e: java.util.concurrent.TimeoutException) {
            error("Calculation timed out after 30 seconds")
        } catch (e: Exception) {
            error("Calculation error: ${e.message}")
        } finally {
            executor.shutdownNow()
        }
    },
)

private fun jstr(v: String) = JsonPrimitive(v)
