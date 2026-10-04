package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocalToolOption 的序列化。
 *
 * 这个测试存在的理由是一次真实事故：新增子类时漏写 `@Serializable`，
 * 编译完全通过、单元测试也全绿，但 release 包一打开助手设置页就崩：
 *
 *   SerializationException: Serializer for subclass 'ShowImage' is not found
 *   in the polymorphic scope of 'LocalToolOption'
 *
 * 漏注解的后果有两个，都很隐蔽：
 *   1. 编译器不生成 $serializer，运行时查不到，直接抛异常；
 *   2. 序列名退化成 Class.simpleName（报错里是 'ShowImage' 而不是
 *      @SerialName 声明的 'show_image'），从报错信息里看不出真正原因。
 *
 * 所以这里不检查「有没有写注解」这种表面事实，而是真的把每个子类
 * 序列化一遍——写漏了就会在这里失败，而不是等到用户装上 release 包。
 */
class LocalToolOptionSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 全部子类。新增枚举时这里也要加，否则覆盖不到。 */
    private val allOptions: List<LocalToolOption> = listOf(
        LocalToolOption.JavascriptEngine,
        LocalToolOption.TimeInfo,
        LocalToolOption.Clipboard,
        LocalToolOption.Tts,
        LocalToolOption.AskUser,
        LocalToolOption.PresentFile,
        LocalToolOption.PythonEngine,
        LocalToolOption.FileTools,
        LocalToolOption.ShellTools,
        LocalToolOption.DatabaseQuery,
        LocalToolOption.TaskTools,
        LocalToolOption.Calculator,
        LocalToolOption.WorkerTools,
        LocalToolOption.TeammateTools,
        LocalToolOption.SendMessage,
        LocalToolOption.ScreenTime,
        LocalToolOption.Calendar,
        LocalToolOption.DeviceToolbox,
        LocalToolOption.LifeCompanion,
        LocalToolOption.CoupleSpace,
        LocalToolOption.ChartDisplay,
        LocalToolOption.DiffText,
        LocalToolOption.ShowImage,
        LocalToolOption.CodeCheck,
    )

    @Test
    fun `every option survives a serialize round trip`() {
        allOptions.forEach { option ->
            // 崩溃点就在这里：没写 @Serializable 的子类会抛
            // Serializer for subclass '...' is not found
            val encoded = json.encodeToString(LocalToolOption.serializer(), option)
            val decoded = json.decodeFromString(LocalToolOption.serializer(), encoded)
            assertEquals(
                "${option::class.simpleName} 反序列化后不相等，" +
                    "检查该子类是否标注了 @Serializable 与 @SerialName",
                option,
                decoded,
            )
        }
    }

    @Test
    fun `serialized form uses the declared SerialName`() {
        // 漏 @Serializable 时会退化成 Class.simpleName，这里把两者区分开
        val cases = mapOf(
            LocalToolOption.ShowImage to "show_image",
            LocalToolOption.CodeCheck to "check_code",
            LocalToolOption.ChartDisplay to "chart_display",
            LocalToolOption.DiffText to "diff_text",
            LocalToolOption.PythonEngine to "python_engine",
        )
        cases.forEach { (option, expected) ->
            val encoded = json.encodeToString(LocalToolOption.serializer(), option)
            // 多态序列化会包一层 {"type":"..."}，这里比对的是 type 的值
            assertTrue(
                "@SerialName 声明未生效，实际序列化为 $encoded（期望 type=$expected）",
                encoded.contains("\"type\":\"$expected\"") ||
                    encoded.trim('"') == expected,
            )
        }
    }

    @Test
    fun `a list of options round trips`() {
        // Assistant.localTools 就是这个形状，崩溃栈里正是这条路径
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(LocalToolOption.serializer()),
            allOptions,
        )
        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LocalToolOption.serializer()),
            encoded,
        )
        assertEquals(allOptions.size, decoded.size)
        assertTrue("列表顺序应保持", decoded == allOptions)
    }
}
