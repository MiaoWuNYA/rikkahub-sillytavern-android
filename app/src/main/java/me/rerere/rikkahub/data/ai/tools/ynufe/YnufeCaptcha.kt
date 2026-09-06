package me.rerere.rikkahub.data.ai.tools.ynufe

import android.graphics.BitmapFactory
import android.util.Base64
import java.util.ArrayDeque

/**
 * 云南财经大学强智教务系统验证码本地 OCR 引擎（移植自 云南财经/src/utils/captchaOcr.ts）。
 * 算法：灰度二值化 + 噪线剥离 -> 槽位列簇切分 4 块 -> 14x36 归一化 -> CCA 去噪
 *       -> 与 311 模板做 Jaccard 相似度匹配 + 若干几何特征判决。
 */
object YnufeCaptcha {
    data class OcrResult(
        val text: String,
        val confidence: Double,
        val isReliable: Boolean,
    )

    private fun decodeBase64Template(b64Str: String): IntArray {
        val binary = Base64.decode(b64Str, Base64.DEFAULT)
        val bits = ArrayList<Boolean>(binary.size * 8)
        for (byte in binary) {
            for (shift in 7 downTo 0) bits.add(((byte.toInt() shr shift) and 1) == 1)
        }
        val rows = IntArray(CAPTCHA_CANVAS_HEIGHT)
        for (y in 0 until CAPTCHA_CANVAS_HEIGHT) {
            var rowVal = 0
            for (x in 0 until CAPTCHA_CANVAS_WIDTH) {
                if (bits[y * CAPTCHA_CANVAS_WIDTH + x]) {
                    rowVal = rowVal or (1 shl (CAPTCHA_CANVAS_WIDTH - 1 - x))
                }
            }
            rows[y] = rowVal
        }
        return rows
    }

    // lazy cache: char -> list of row-masks
    private val templatesCache by lazy {
        PACKED_TEMPLATES.mapValues { (_, list) -> list.map { decodeBase64Template(it) } }
    }

    private fun preprocessPixels(rgba: IntArray, width: Int, height: Int): Array<IntArray> {
        val grid = Array(height) { IntArray(width) }
        // threshold binarize (ignore 2px border + y<10, y>31 noise zone)
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                if (y < 10 || y > 31) continue
                val idx = y * width + x
                val c = rgba[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val gray = 0.299 * r + 0.587 * g + 0.114 * b
                if (gray < 170) grid[y][x] = 1
            }
        }
        // 2 rounds of 1-pixel diagonal noise stripping
        for (round in 0 until 2) {
            val toRemove = ArrayList<Pair<Int, Int>>()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    if (grid[y][x] == 1) {
                        val isDiag1 = ((grid[y - 1][x - 1] == 1 || grid[y + 1][x + 1] == 1) &&
                            grid[y - 1][x] == 0 && grid[y + 1][x] == 0 &&
                            grid[y][x - 1] == 0 && grid[y][x + 1] == 0)
                        val isDiag2 = ((grid[y - 1][x + 1] == 1 || grid[y + 1][x - 1] == 1) &&
                            grid[y - 1][x] == 0 && grid[y + 1][x] == 0 &&
                            grid[y][x - 1] == 0 && grid[y][x + 1] == 0)
                        var neighbors = 0
                        for (dy in -1..1) for (dx in -1..1) {
                            if ((dy != 0 || dx != 0) && grid[y + dy][x + dx] == 1) neighbors++
                        }
                        if (isDiag1 || isDiag2 || neighbors < 1) toRemove.add(y to x)
                    }
                }
            }
            for ((ry, rx) in toRemove) grid[ry][rx] = 0
        }
        return grid
    }

    private fun extractTypographyBlocks(grid: Array<IntArray>, width: Int, height: Int): List<Array<IntArray>> {
        val slotWidth = (width - 4) / 4.0
        val intervals = ArrayList<Pair<Int, Int>>()

        for (i in 0 until 4) {
            val slotSx = (2 + i * slotWidth).toInt()
            val slotEx = (2 + (i + 1) * slotWidth).toInt()

            val searchSx = (slotSx - 3).coerceAtLeast(0)
            val searchEx = (slotEx + 3).coerceAtMost(width - 1)

            // column clusters
            val clusters = ArrayList<Pair<Int, Int>>()
            var inCluster = false
            var curSx = 0
            for (x in searchSx..searchEx) {
                var colCount = 0
                for (y in 0 until height) if (grid[y][x] == 1) colCount++
                if (colCount >= 1 && !inCluster) {
                    inCluster = true
                    curSx = x
                } else if (colCount < 1 && inCluster) {
                    inCluster = false
                    clusters.add(curSx to (x - 1))
                }
            }
            if (inCluster) clusters.add(curSx to searchEx)

            if (clusters.isEmpty()) {
                intervals.add(slotSx to slotEx)
            } else {
                // merge tiny gaps (<=2px broken strokes)
                val merged = ArrayList<Pair<Int, Int>>()
                var curCsx = clusters[0].first
                var curCex = clusters[0].second
                for (cIdx in 1 until clusters.size) {
                    val (csx, cex) = clusters[cIdx]
                    if (csx - curCex <= 2) {
                        curCex = cex
                    } else {
                        merged.add(curCsx to curCex)
                        curCsx = csx
                        curCex = cex
                    }
                }
                merged.add(curCsx to curCex)

                val slotCenter = (slotSx + slotEx) / 2.0
                var bestCluster = merged[0]
                var bestScore = -9999.0
                for ((csx, cex) in merged) {
                    val cCenter = (csx + cex) / 2.0
                    var cMass = 0
                    for (cx in csx..cex) for (cy in 0 until height) if (grid[cy][cx] == 1) cMass++
                    val cDist = Math.abs(cCenter - slotCenter)
                    val score = cMass - cDist * 6
                    if (score > bestScore) {
                        bestScore = score
                        bestCluster = csx to cex
                    }
                }

                var minX = bestCluster.first
                var maxX = bestCluster.second
                if (maxX - minX < 5) {
                    val padNeeded = 6 - (maxX - minX + 1)
                    minX = (minX - padNeeded / 2).coerceAtLeast(0)
                    maxX = (minX + 5).coerceAtMost(width - 1)
                }
                intervals.add(minX to maxX)
            }
        }

        val matrices = ArrayList<Array<IntArray>>()
        for ((minX, maxX) in intervals) {
            val srcW = maxX - minX + 1
            val norm = Array(CAPTCHA_CANVAS_HEIGHT) { IntArray(CAPTCHA_CANVAS_WIDTH) }
            for (y in 2 until Math.min(CAPTCHA_CANVAS_HEIGHT + 2, height - 2)) {
                val ny = y - 2
                for (nx in 0 until CAPTCHA_CANVAS_WIDTH) {
                    val sxMapped = minX + (nx * srcW) / CAPTCHA_CANVAS_WIDTH
                    if (sxMapped >= 0 && sxMapped < width && grid[y][sxMapped] == 1) norm[ny][nx] = 1
                }
            }

            // CCA: keep main body + vertically-aligned dots, remove side orphan noise
            val visited = Array(CAPTCHA_CANVAS_HEIGHT) { BooleanArray(CAPTCHA_CANVAS_WIDTH) }
            val components = ArrayList<MutableList<Pair<Int, Int>>>()
            for (y in 0 until CAPTCHA_CANVAS_HEIGHT) {
                for (x in 0 until CAPTCHA_CANVAS_WIDTH) {
                    if (norm[y][x] == 1 && !visited[y][x]) {
                        val comp = ArrayList<Pair<Int, Int>>()
                        val queue = ArrayDeque<Pair<Int, Int>>()
                        queue.add(y to x)
                        visited[y][x] = true
                        while (queue.isNotEmpty()) {
                            val (cy, cx) = queue.removeFirst()
                            comp.add(cy to cx)
                            for (dy in -1..1) for (dx in -1..1) {
                                val ny = cy + dy
                                val nx = cx + dx
                                if (ny in 0 until CAPTCHA_CANVAS_HEIGHT && nx in 0 until CAPTCHA_CANVAS_WIDTH &&
                                    norm[ny][nx] == 1 && !visited[ny][nx]
                                ) {
                                    visited[ny][nx] = true
                                    queue.add(ny to nx)
                                }
                            }
                        }
                        components.add(comp)
                    }
                }
            }

            if (components.size > 1) {
                components.sortByDescending { it.size }
                val maxComp = components[0]
                val maxCompMinX = maxComp.minOf { it.second }
                val maxCompMaxX = maxComp.maxOf { it.second }
                for (compIdx in 1 until components.size) {
                    val comp = components[compIdx]
                    val compMinX = comp.minOf { it.second }
                    val compMaxX = comp.maxOf { it.second }
                    val isOverlap = !(compMaxX < maxCompMinX - 1 || compMinX > maxCompMaxX + 1)
                    if (!isOverlap) {
                        for ((py, px) in comp) norm[py][px] = 0
                    }
                }
            }

            matrices.add(norm)
        }
        return matrices
    }

    private fun countSetBits(n: Int): Int {
        var count = 0
        var v = n
        while (v > 0) {
            v = v and (v - 1)
            count++
        }
        return count
    }

    private fun calculateSimilarity(mat: Array<IntArray>, templateRows: IntArray): Double {
        var intersection = 0
        var union = 0
        for (y in 0 until CAPTCHA_CANVAS_HEIGHT) {
            var matRowVal = 0
            for (x in 0 until CAPTCHA_CANVAS_WIDTH) {
                if (mat[y][x] == 1) matRowVal = matRowVal or (1 shl (CAPTCHA_CANVAS_WIDTH - 1 - x))
            }
            val tRow = templateRows[y]
            intersection += countSetBits(matRowVal and tRow)
            union += countSetBits(matRowVal or tRow)
        }
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    private fun classifyBlock(mat: Array<IntArray>): Pair<Char, Double> {
        var bestChar = '?'
        var bestScore = -1.0
        for ((ch, tList) in templatesCache) {
            for (tRows in tList) {
                val s = calculateSimilarity(mat, tRows)
                if (s > bestScore) {
                    bestScore = s
                    bestChar = ch
                }
            }
        }

        if (bestChar == 'l') bestChar = '1'

        // geometric decision 1: descender separates p vs n/h/u
        if (bestChar == 'n' || bestChar == 'h' || bestChar == 'p' || bestChar == 'u') {
            var botLeftDescender = 0
            for (y in 28 until CAPTCHA_CANVAS_HEIGHT) for (x in 0..6) if (mat[y][x] == 1) botLeftDescender++
            if (botLeftDescender >= 3) {
                bestChar = 'p'
            } else if (bestChar == 'p' && botLeftDescender == 0) {
                var topArch = 0
                for (y in 15..17) for (x in 0 until CAPTCHA_CANVAS_WIDTH) if (mat[y][x] == 1) topArch++
                bestChar = if (topArch >= 8) 'n' else 'u'
            }
        }

        // geometric decision 2: ascender separates h vs n
        if (bestChar == 'h' || bestChar == 'n') {
            var leftTopPixels = 0
            for (y in 0 until 14) for (x in 2..7) if (mat[y][x] == 1) leftTopPixels++
            bestChar = if (leftTopPixels >= 4) 'h' else 'n'
        }

        // geometric decision 3: among 1 vs i — blank row in 12..15 => i
        if (bestChar == '1' || bestChar == 'i') {
            var hasGap = false
            for (y in 12..15) {
                var rowSum = 0
                for (x in 0 until CAPTCHA_CANVAS_WIDTH) if (mat[y][x] == 1) rowSum++
                if (rowSum == 0) { hasGap = true; break }
            }
            bestChar = if (hasGap) 'i' else '1'
        }

        return bestChar to bestScore
    }

    private fun recognizeCaptchaRgba(rgba: IntArray, width: Int, height: Int): OcrResult {
        val grid = preprocessPixels(rgba, width, height)
        val mats = extractTypographyBlocks(grid, width, height)
        val sb = StringBuilder()
        val confidences = ArrayList<Double>()
        for (mat in mats) {
            val (ch, score) = classifyBlock(mat)
            sb.append(ch)
            confidences.add(score)
        }
        val avg = if (confidences.isNotEmpty()) confidences.average() else 0.0
        val isReliable = confidences.isNotEmpty() && confidences.all { it >= 0.55 } && avg >= 0.70
        return OcrResult(sb.toString(), avg, isReliable)
    }

    /**
     * Recognize a 4-char captcha from raw image bytes.
     */
    fun recognize(bytes: ByteArray): OcrResult {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return OcrResult("", 0.0, false)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        return recognizeCaptchaRgba(pixels, w, h)
    }
}