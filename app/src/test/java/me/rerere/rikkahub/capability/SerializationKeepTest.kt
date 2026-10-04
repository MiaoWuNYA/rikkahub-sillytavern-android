package me.rerere.rikkahub.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R8 keep 规则：kotlinx.serialization 的 sealed 多态。
 *
 * 踩过的坑：`LocalToolOption` 是 @Serializable 的 sealed class，子类通过
 * 编译器生成的多态序列化器按 Class 查表注册。这条通路对 R8 完全不可见——
 * 没有任何直接的 Kotlin 代码引用那些子类，于是 R8 判定它们不可达并删掉。
 *
 * 症状是最难查的那一类：
 *   - debug 包一切正常（不跑 R8）
 *   - release 包一打开助手设置页就闪退，报
 *     Serializer for subclass 'LocalToolOption$ShowImage' is not found
 *   - APK 里类名看起来都在，只有 usage.txt 会列出真正被删的两个
 *
 * 这个测试锁住规则本身，避免以后有人「清理」proguard 文件时又踩一遍。
 */
class SerializationKeepTest {

    private val roots: List<File> by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile)
            .filterNotNull().distinct()
    }

    private fun read(rel: String): String {
        for (root in roots) {
            for (c in listOf(File(root, rel), File(root, "app/$rel"))) {
                if (c.exists()) return c.readText()
            }
        }
        return ""
    }

    private fun proguard(): String = read("proguard-rules.pro")

    @Test
    fun `sealed LocalToolOption hierarchy is kept`() {
        val s = proguard()
        assertTrue(
            "必须显式保留 LocalToolOption 及其所有子类，否则 release 包会闪退",
            s.contains("LocalToolOption") && s.contains("LocalToolOption\$*"),
        )
    }

    @Test
    fun `keep rule must not allow shrinking`() {
        val s = proguard()
        // allowshrinking 等于「没被引用就删」——而 sealed 子类恰恰是
        // 看起来没被引用的那种。实测加了这个修饰符之后
        // ShowImage / CodeCheck 立刻又被删掉。
        val idx = s.indexOf("LocalToolOption")
        val before = s.substring(maxOf(0, idx - 200), idx)
        assertFalse(
            "LocalToolOption 的 keep 规则不能带 allowshrinking",
            before.contains("allowshrinking") && before.lastIndexOf("allowshrinking") > before.lastIndexOf("-keep"),
        )
    }

    @Test
    fun `serializable classes keep their generated serializer`() {
        val s = proguard()
        assertTrue("应保留 @Serializable 类型",
            s.contains("@kotlinx.serialization.Serializable"))
        assertTrue("应保留编译器生成的 \$serializer",
            s.contains("\$\$serializer"))
        assertTrue("应保留 Companion 与 serializer() 入口",
            s.contains("Companion") && s.contains("KSerializer serializer"))
    }

    @Test
    fun `serialization annotations survive`() {
        val s = proguard()
        // @SerialName 的值是运行时查表的键，注解被剥掉就找不到子类
        assertTrue("必须保留运行时注解",
            s.contains("RuntimeVisibleAnnotations"))
    }

    @Test
    fun `every LocalToolOption subclass is covered by the keep rule`() {
        val localTools = read(
            "src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt"
        )
        assertTrue("找不到 LocalTools.kt", localTools.isNotEmpty())
        assertTrue("LocalToolOption 应为 sealed class",
            localTools.contains("sealed class LocalToolOption"))
        // 通配符规则覆盖所有子类，有它就不用逐个列举
        assertTrue("通配符应覆盖全部子类", proguard().contains("LocalToolOption\$*"))
    }

    @Test
    fun `every sealed subclass declares Serializable`() {
        val localTools = read(
            "src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt"
        )
        // 漏 @Serializable 的后果：编译器不生成 $serializer，运行时抛
        //   Serializer for subclass 'ShowImage' is not found
        // 而且序列名会退化成 Class.simpleName，报错里看不出真正原因。
        // 编译不会报错、别的测试也全绿，只有装上 release 包才炸。
        val regex = Regex(
            "((?:@\\w+(?:\\([^)]*\\))?\\s*|/\\*\\*.*?\\*/\\s*|//[^\\n]*\\n\\s*)*)" +
                "data object (\\w+) : LocalToolOption\\(\\)",
            RegexOption.DOT_MATCHES_ALL,
        )
        val missing = regex.findAll(localTools)
            .filter { !it.groupValues[1].contains("@Serializable") }
            .map { it.groupValues[2] }
            .toList()
        assertTrue(
            "这些子类缺少 @Serializable，装上 release 包会闪退：" +
                missing.joinToString() + "（逐个补上注解）",
            missing.isEmpty(),
        )
    }
}
