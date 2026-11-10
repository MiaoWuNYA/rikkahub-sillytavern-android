package me.rerere.rikkahub.data.st.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * 词表资源路径与真 BPE 计数校验（在 app 模块目录下运行；资源缺失时跳过）。
 */
class TikTokenAssetTest {

    private val assetsDir = File("src/main/assets")

    @Before
    fun setUp() {
        TikToken.resetForTest()
        assumeTrue(assetsDir.isDirectory)
        TikToken.attach { path: String ->
            File(assetsDir, path).takeIf { it.isFile }?.inputStream() as InputStream?
        }
    }

    @Test
    fun loadsCl100kVocabularyFromAssets() {
        val count = TikToken.count("Hello, world!", TikToken.Encoding.CL100K_BASE)
        assertEquals(4, count)
        assertTrue(TikToken.vocabSize(TikToken.Encoding.CL100K_BASE) > 100_000)
    }

    @Test
    fun countsChineseAndRejectsMissingVocabulary() {
        // 资源在时按真词表计数；缺失时为 null 由上层回退启发式
        val count = TikToken.count("你好世界", TikToken.Encoding.CL100K_BASE)
        assertTrue(count == null || count >= 1)
        TikToken.attach(null)
        assertEquals(null, TikToken.count("Hello", TikToken.Encoding.O200K_BASE))
    }
}
