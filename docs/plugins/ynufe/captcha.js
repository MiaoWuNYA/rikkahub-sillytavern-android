// 云南财经强智教务验证码本地 OCR。
//
// 从原 Java 实现（YnufeCaptcha.kt）原样搬来，算法一字未改：
// 灰度二值化 + 噪线剥离 -> 槽位列簇切分 4 块 -> 14x36 归一化 -> CCA 去噪
// -> 与 311 张字模做 Jaccard 相似度匹配 + 若干几何特征判决。
//
// 沙箱里没有 canvas，像素由宿主的 image.decode 桥接给出（RGBA，与 canvas.getImageData 同序），
// 所以这里只负责纯计算，不做任何图像解码。

var __templatesCache = null;

function decodeBase64Template(b64Str) {
  var binary = atob(b64Str);
  var bits = [];
  for (var i = 0; i < binary.length; i++) {
    var byte = binary.charCodeAt(i);
    for (var shift = 7; shift >= 0; shift--) {
      bits.push((byte >> shift) & 1);
    }
  }

  var rows = [];
  for (var y = 0; y < CAPTCHA_CANVAS_HEIGHT; y++) {
    var rowVal = 0;
    for (var x = 0; x < CAPTCHA_CANVAS_WIDTH; x++) {
      if (bits[y * CAPTCHA_CANVAS_WIDTH + x] === 1) {
        rowVal |= (1 << (CAPTCHA_CANVAS_WIDTH - 1 - x));
      }
    }
    rows.push(rowVal);
  }
  return rows;
}

// 字模解压很贵（311 × 504 位），只在首次识别时做一次
function getTemplates() {
  if (__templatesCache) return __templatesCache;
  var cache = {};
  for (var ch in PACKED_TEMPLATES) {
    if (!Object.prototype.hasOwnProperty.call(PACKED_TEMPLATES, ch)) continue;
    var list = PACKED_TEMPLATES[ch];
    var decoded = [];
    for (var i = 0; i < list.length; i++) decoded.push(decodeBase64Template(list[i]));
    cache[ch] = decoded;
  }
  __templatesCache = cache;
  return cache;
}

// 灰度二值化 + 2 轮 1 像素对角噪线剥离
function preprocessPixels(rgbaData, width, height) {
  var grid = [];
  for (var i = 0; i < height; i++) {
    var row = [];
    for (var j = 0; j < width; j++) row.push(0);
    grid.push(row);
  }

  // 阈值二值化：忽略最外 2 像素边框，以及 y<10 / y>31 的纯噪点区
  for (var y = 2; y < height - 2; y++) {
    if (y < 10 || y > 31) continue;
    for (var x = 2; x < width - 2; x++) {
      var idx = (y * width + x) * 4;
      var gray = 0.299 * rgbaData[idx] + 0.587 * rgbaData[idx + 1] + 0.114 * rgbaData[idx + 2];
      if (gray < 170) grid[y][x] = 1;
    }
  }

  for (var round = 0; round < 2; round++) {
    var toRemove = [];
    for (var yy = 1; yy < height - 1; yy++) {
      for (var xx = 1; xx < width - 1; xx++) {
        if (grid[yy][xx] !== 1) continue;
        var isDiag1 = (grid[yy - 1][xx - 1] === 1 || grid[yy + 1][xx + 1] === 1) &&
          grid[yy - 1][xx] === 0 && grid[yy + 1][xx] === 0 &&
          grid[yy][xx - 1] === 0 && grid[yy][xx + 1] === 0;
        var isDiag2 = (grid[yy - 1][xx + 1] === 1 || grid[yy + 1][xx - 1] === 1) &&
          grid[yy - 1][xx] === 0 && grid[yy + 1][xx] === 0 &&
          grid[yy][xx - 1] === 0 && grid[yy][xx + 1] === 0;

        var neighbors = 0;
        for (var dy = -1; dy <= 1; dy++) {
          for (var dx = -1; dx <= 1; dx++) {
            if ((dy !== 0 || dx !== 0) && grid[yy + dy][xx + dx] === 1) neighbors++;
          }
        }
        if (isDiag1 || isDiag2 || neighbors < 1) toRemove.push([yy, xx]);
      }
    }
    for (var k = 0; k < toRemove.length; k++) grid[toRemove[k][0]][toRemove[k][1]] = 0;
  }

  return grid;
}

// 槽位列簇智能切分 4 个字符并归一化到 14x36
function extractTypographyBlocks(grid, width, height) {
  var slotWidth = (width - 4) / 4.0;
  var intervals = [];

  for (var i = 0; i < 4; i++) {
    var slotSx = Math.floor(2 + i * slotWidth);
    var slotEx = Math.floor(2 + (i + 1) * slotWidth);
    var searchSx = Math.max(0, slotSx - 3);
    var searchEx = Math.min(width - 1, slotEx + 3);

    var clusters = [];
    var inCluster = false;
    var curSx = 0;

    for (var x = searchSx; x <= searchEx; x++) {
      var colCount = 0;
      for (var y = 0; y < height; y++) {
        if (grid[y][x] === 1) colCount++;
      }
      if (colCount >= 1 && !inCluster) {
        inCluster = true;
        curSx = x;
      } else if (colCount < 1 && inCluster) {
        inCluster = false;
        clusters.push([curSx, x - 1]);
      }
    }
    if (inCluster) clusters.push([curSx, searchEx]);

    if (clusters.length === 0) {
      intervals.push([slotSx, slotEx]);
      continue;
    }

    // 合并 <=2 像素的细微断笔
    var merged = [];
    var curCsx = clusters[0][0];
    var curCex = clusters[0][1];
    for (var cIdx = 1; cIdx < clusters.length; cIdx++) {
      var csx = clusters[cIdx][0];
      var cex = clusters[cIdx][1];
      if (csx - curCex <= 2) {
        curCex = cex;
      } else {
        merged.push([curCsx, curCex]);
        curCsx = csx;
        curCex = cex;
      }
    }
    merged.push([curCsx, curCex]);

    // 取「笔画质量 - 偏离槽位中心」得分最高的簇
    var slotCenter = (slotSx + slotEx) / 2.0;
    var bestCluster = merged[0];
    var bestScore = -9999;
    for (var mIdx = 0; mIdx < merged.length; mIdx++) {
      var mc = merged[mIdx];
      var cCenter = (mc[0] + mc[1]) / 2.0;
      var cMass = 0;
      for (var cx = mc[0]; cx <= mc[1]; cx++) {
        for (var cy = 0; cy < height; cy++) {
          if (grid[cy][cx] === 1) cMass++;
        }
      }
      var score = cMass - Math.abs(cCenter - slotCenter) * 6;
      if (score > bestScore) {
        bestScore = score;
        bestCluster = mc;
      }
    }

    var minX = bestCluster[0];
    var maxX = bestCluster[1];
    if (maxX - minX < 5) {
      var padNeeded = 6 - (maxX - minX + 1);
      minX = Math.max(0, minX - Math.floor(padNeeded / 2));
      maxX = Math.min(width - 1, minX + 5);
    }
    intervals.push([minX, maxX]);
  }

  var matrices = [];
  for (var bi = 0; bi < intervals.length; bi++) {
    var bMinX = intervals[bi][0];
    var bMaxX = intervals[bi][1];
    var srcW = bMaxX - bMinX + 1;

    var norm = [];
    for (var ny0 = 0; ny0 < CAPTCHA_CANVAS_HEIGHT; ny0++) {
      var nr = [];
      for (var nx0 = 0; nx0 < CAPTCHA_CANVAS_WIDTH; nx0++) nr.push(0);
      norm.push(nr);
    }

    for (var gy = 2; gy < Math.min(CAPTCHA_CANVAS_HEIGHT + 2, height - 2); gy++) {
      var nry = gy - 2;
      for (var nx = 0; nx < CAPTCHA_CANVAS_WIDTH; nx++) {
        var sxMapped = bMinX + Math.floor((nx * srcW) / CAPTCHA_CANVAS_WIDTH);
        if (sxMapped >= 0 && sxMapped < width && grid[gy][sxMapped] === 1) norm[nry][nx] = 1;
      }
    }

    // CCA：保留主体与垂直对齐的圆点（i/j），剔除侧边孤立噪点
    var visited = [];
    for (var vy = 0; vy < CAPTCHA_CANVAS_HEIGHT; vy++) {
      var vr = [];
      for (var vx = 0; vx < CAPTCHA_CANVAS_WIDTH; vx++) vr.push(false);
      visited.push(vr);
    }

    var components = [];
    for (var cy2 = 0; cy2 < CAPTCHA_CANVAS_HEIGHT; cy2++) {
      for (var cx2 = 0; cx2 < CAPTCHA_CANVAS_WIDTH; cx2++) {
        if (norm[cy2][cx2] !== 1 || visited[cy2][cx2]) continue;
        var comp = [];
        var queue = [[cy2, cx2]];
        visited[cy2][cx2] = true;
        while (queue.length > 0) {
          var cur = queue.shift();
          comp.push(cur);
          for (var ddy = -1; ddy <= 1; ddy++) {
            for (var ddx = -1; ddx <= 1; ddx++) {
              var ncy = cur[0] + ddy;
              var ncx = cur[1] + ddx;
              if (ncy >= 0 && ncy < CAPTCHA_CANVAS_HEIGHT && ncx >= 0 && ncx < CAPTCHA_CANVAS_WIDTH &&
                norm[ncy][ncx] === 1 && !visited[ncy][ncx]) {
                visited[ncy][ncx] = true;
                queue.push([ncy, ncx]);
              }
            }
          }
        }
        components.push(comp);
      }
    }

    if (components.length > 1) {
      components.sort(function (a, b) { return b.length - a.length; });
      var maxComp = components[0];
      var maxCompMinX = maxComp[0][1];
      var maxCompMaxX = maxComp[0][1];
      for (var mi = 0; mi < maxComp.length; mi++) {
        if (maxComp[mi][1] < maxCompMinX) maxCompMinX = maxComp[mi][1];
        if (maxComp[mi][1] > maxCompMaxX) maxCompMaxX = maxComp[mi][1];
      }

      for (var ci = 1; ci < components.length; ci++) {
        var c = components[ci];
        var compMinX = c[0][1];
        var compMaxX = c[0][1];
        for (var pi = 0; pi < c.length; pi++) {
          if (c[pi][1] < compMinX) compMinX = c[pi][1];
          if (c[pi][1] > compMaxX) compMaxX = c[pi][1];
        }
        // 水平投影区间与主体重叠（字符上方的竖线、横梁、圆点）就保留
        var isOverlap = !(compMaxX < maxCompMinX - 1 || compMinX > maxCompMaxX + 1);
        if (!isOverlap) {
          for (var qi = 0; qi < c.length; qi++) norm[c[qi][0]][c[qi][1]] = 0;
        }
      }
    }

    matrices.push(norm);
  }

  return matrices;
}

function countSetBits(n) {
  var count = 0;
  var val = n;
  while (val > 0) {
    val &= (val - 1);
    count++;
  }
  return count;
}

function calculateSimilarity(mat, templateRows) {
  var intersection = 0;
  var union = 0;
  for (var y = 0; y < CAPTCHA_CANVAS_HEIGHT; y++) {
    var matRowVal = 0;
    for (var x = 0; x < CAPTCHA_CANVAS_WIDTH; x++) {
      if (mat[y][x] === 1) matRowVal |= (1 << (CAPTCHA_CANVAS_WIDTH - 1 - x));
    }
    var tRow = templateRows[y];
    intersection += countSetBits(matRowVal & tRow);
    union += countSetBits(matRowVal | tRow);
  }
  return union > 0 ? intersection / union : 0;
}

function classifyBlock(mat) {
  var bestChar = '?';
  var bestScore = -1;
  var all = getTemplates();

  for (var ch in all) {
    if (!Object.prototype.hasOwnProperty.call(all, ch)) continue;
    var tList = all[ch];
    for (var ti = 0; ti < tList.length; ti++) {
      var s = calculateSimilarity(mat, tList[ti]);
      if (s > bestScore) {
        bestScore = s;
        bestChar = ch;
      }
    }
  }

  // 强智验证码的竖直竖线统一算作数字 1
  if (bestChar === 'l') bestChar = '1';

  // 判决 1：降部区分 p 与 n/h/u（p 在 y>=28 处有坚实降部立柱）
  if (bestChar === 'n' || bestChar === 'h' || bestChar === 'p' || bestChar === 'u') {
    var botLeftDescender = 0;
    for (var y1 = 28; y1 < CAPTCHA_CANVAS_HEIGHT; y1++) {
      for (var x1 = 0; x1 <= 6; x1++) {
        if (mat[y1][x1] === 1) botLeftDescender++;
      }
    }
    if (botLeftDescender >= 3) {
      bestChar = 'p';
    } else if (bestChar === 'p' && botLeftDescender === 0) {
      var topArch = 0;
      for (var y2 = 15; y2 <= 17; y2++) {
        for (var x2 = 0; x2 < CAPTCHA_CANVAS_WIDTH; x2++) {
          if (mat[y2][x2] === 1) topArch++;
        }
      }
      bestChar = topArch >= 8 ? 'n' : 'u';
    }
  }

  // 判决 2：升部区分 h 与 n（只看左侧主立柱 x in [2,7]，避开斜向噪线）
  if (bestChar === 'h' || bestChar === 'n') {
    var leftTopPixels = 0;
    for (var y3 = 0; y3 < 14; y3++) {
      for (var x3 = 2; x3 <= 7; x3++) {
        if (mat[y3][x3] === 1) leftTopPixels++;
      }
    }
    bestChar = leftTopPixels >= 4 ? 'h' : 'n';
  }

  // 判决 3：i 在 y=12..15 有整行空白（分隔圆点与身躯），1 则一贯到底
  if (bestChar === '1' || bestChar === 'i') {
    var hasGap = false;
    for (var y4 = 12; y4 <= 15; y4++) {
      var rowSum = 0;
      for (var x4 = 0; x4 < CAPTCHA_CANVAS_WIDTH; x4++) {
        if (mat[y4][x4] === 1) rowSum++;
      }
      if (rowSum === 0) { hasGap = true; break; }
    }
    bestChar = hasGap ? 'i' : '1';
  }

  return [bestChar, bestScore];
}

/**
 * 识别 RGBA 像素里的验证码。
 *
 * rgba 可以是 Uint8ClampedArray（image.decode 的 pixels）、Uint8Array 或普通数组。
 */
function recognizeCaptchaRgba(rgba, width, height) {
  var grid = preprocessPixels(rgba, width, height);
  var mats = extractTypographyBlocks(grid, width, height);

  var text = '';
  var charConfidences = [];
  for (var i = 0; i < mats.length; i++) {
    var r = classifyBlock(mats[i]);
    text += r[0];
    charConfidences.push(r[1]);
  }

  var sum = 0;
  for (var j = 0; j < charConfidences.length; j++) sum += charConfidences[j];
  var avg = charConfidences.length > 0 ? sum / charConfidences.length : 0;

  var reliable = charConfidences.length > 0;
  for (var k = 0; k < charConfidences.length; k++) {
    if (charConfidences[k] < 0.55) reliable = false;
  }
  if (avg < 0.70) reliable = false;

  return {
    text: text,
    confidence: avg,
    charConfidences: charConfidences,
    isReliable: reliable
  };
}

/**
 * 从 base64 图片（或 image.decode 的结果）识别验证码。
 */
function recognizeCaptchaImage(source) {
  var img = image.decode(source);
  return recognizeCaptchaRgba(img.pixels, img.width, img.height);
}
