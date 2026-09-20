package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本项目的版本号是 `2.5.4fix9` 这种带补丁序号的写法，不是纯 SemVer。
 * 之前那套解析器把 patch 段的 `4fix9` 用 toIntOrNull() 读成 null 再当 0，
 * 于是 2.5.4fix9 和 2.5.4 判成相等，更新提示永远不弹——这些用例就是钉住这个回归。
 */
class VersionTest {

    private fun assertNewer(newer: String, older: String) {
        assertTrue(
            "$newer 应该比 $older 新",
            Version(newer) > Version(older),
        )
        assertTrue(
            "$older 应该比 $newer 旧",
            Version(older) < Version(newer),
        )
    }

    @Test
    fun `fix suffix orders after the plain release`() {
        assertNewer("2.5.4fix1", "2.5.4")
        assertNewer("2.5.4fix9", "2.5.4")
    }

    @Test
    fun `fix suffix compares numerically not lexically`() {
        // 字典序会把 fix10 排到 fix9 前面，数值比较才是对的
        assertNewer("2.5.4fix10", "2.5.4fix9")
        assertNewer("2.5.4fix9", "2.5.4fix8")
        assertNewer("2.5.4fix13", "2.5.4fix9")
    }

    @Test
    fun `next patch release beats any fix of the previous one`() {
        assertNewer("2.5.5", "2.5.4fix99")
        assertNewer("2.6", "2.5.4fix9")
    }

    @Test
    fun `identical versions compare equal`() {
        assertEquals(0, Version("2.5.4fix9").compareTo(Version("2.5.4fix9")))
        assertEquals(0, Version("2.5.4").compareTo(Version("2.5.4")))
    }

    @Test
    fun `leading v and build metadata are ignored`() {
        assertEquals(0, Version("v2.5.4fix9").compareTo(Version("2.5.4fix9")))
        assertEquals(0, Version("2.5.4fix9+build.7").compareTo(Version("2.5.4fix9")))
    }

    @Test
    fun `prerelease ranks below the release`() {
        assertNewer("2.5.4", "2.5.4-alpha")
        assertNewer("2.5.4-alpha.1", "2.5.4-alpha")
        assertNewer("2.5.4-beta", "2.5.4-alpha")
    }

    @Test
    fun `plain semver still works`() {
        assertNewer("2.5.10", "2.5.9")
        assertNewer("2.6.0", "2.5.99")
        assertNewer("3.0.0", "2.99.99")
    }

    @Test
    fun `the shipped version is not mistaken for an update to itself`() {
        assertEquals(0, Version("2.5.4fix9").compareTo(Version("2.5.4fix9")))
    }
}
