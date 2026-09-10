package me.rerere.rikkahub.ui.components.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn

private const val TAG = "ColorPickerDialog"
private const val MEMORY_PREFS = "color_picker_memory"
private const val MEMORY_KEY = "saved_colors"
private const val MAX_MEMORY = 20

private fun loadMemoryColors(context: Context): List<Long> {
    return try {
        val prefs = context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(MEMORY_KEY, "") ?: ""
        if (raw.isBlank()) emptyList()
        else raw.split(",").mapNotNull { it.toLongOrNull() }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading memory colors", e)
        emptyList()
    }
}

private fun saveMemoryColors(context: Context, colors: List<Long>) {
    try {
        val prefs = context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(MEMORY_KEY, colors.joinToString(",")).apply()
    } catch (e: Exception) {
        Log.e(TAG, "Error saving memory colors", e)
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Long?,
    defaultColor: Color,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initVal = remember(initialColor) {
        initialColor?.let { it.toComposeColor() } ?: defaultColor
    }
    var hue by remember { mutableFloatStateOf(initVal.toHue()) }
    var sat by remember { mutableFloatStateOf(initVal.toSaturation()) }
    var v by remember { mutableFloatStateOf(initVal.toValue()) }
    var a by remember { mutableFloatStateOf(initVal.alpha) }
    var hex by remember { mutableStateOf(initialColor?.toHexString() ?: "") }

    val cur = remember(hue, sat, v, a) {
        try {
            hsvToColor(hue, sat, v, a)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating color", e)
            defaultColor
        }
    }

    val context = LocalContext.current
    var memoryColors by remember { mutableStateOf(loadMemoryColors(context)) }

    @OptIn(ExperimentalLayoutApi::class)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("颜色选择") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(defaultColor, CircleShape))
                    Text("原色", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text("当前", style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.size(48.dp).background(cur, CircleShape))
                }
                SVPicker(hue = hue, sat = sat, v = v) { ns, nv ->
                    sat = ns; v = nv; hex = hsvToHex(hue, ns, nv, a)
                }
                HuePicker(hue = hue) { nh ->
                    hue = nh; hex = hsvToHex(nh, sat, v, a)
                }
                AlphaPicker(color = hsvToColor(hue, sat, v, 1f), alpha = a) { na ->
                    a = na; hex = hsvToHex(hue, sat, v, na)
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { input ->
                        hex = input
                        try {
                            input.toColorFromHex()?.let { c ->
                                hue = c.toHue(); sat = c.toSaturation(); v = c.toValue(); a = c.alpha
                            }
                        } catch (e: Exception) { Log.e(TAG, "Error parsing hex", e) }
                    },
                    label = { Text("HEX (#RRGGBB 或 #AARRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically
                ) {
                    Text("记忆色", style = MaterialTheme.typography.labelMedium)
                    if (memoryColors.isNotEmpty()) {
                        TextButton(onClick = {
                            memoryColors = emptyList()
                            saveMemoryColors(context, emptyList())
                        }) { Text("清除全部", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    memoryColors.forEach { colorLong ->
                        val memColor = remember(colorLong) {
                            try { colorLong.toComposeColor() } catch (e: Exception) { Log.e(TAG, "Error memory color", e); Color.Gray }
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(memColor)
                                .clickable {
                                    try {
                                        hue = memColor.toHue()
                                        sat = memColor.toSaturation()
                                        v = memColor.toValue()
                                        a = memColor.alpha
                                        hex = colorLong.toHexString()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error applying memory color", e)
                                    }
                                }
                        )
                    }
                    if (memoryColors.size < MAX_MEMORY) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    try {
                                        val newLong = cur.toArgbLong()
                                        if (!memoryColors.contains(newLong)) {
                                            val updated = (listOf(newLong) + memoryColors).take(MAX_MEMORY)
                                            memoryColors = updated
                                            saveMemoryColors(context, updated)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error saving to memory", e)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onConfirm(null); onDismiss() }) { Text("重置") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    try { onConfirm(cur.toArgbLong()) } catch (e: Exception) { Log.e(TAG, "Error toArgbLong", e); onConfirm(null) }
                    onDismiss()
                }) { Text("确认") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SVPicker(hue: Float, sat: Float, v: Float, onColorChanged: (Float, Float) -> Unit) {
    val currentOnColorChanged by rememberUpdatedState(onColorChanged)
    Box(Modifier.fillMaxWidth().height(200.dp).background(Color.White, RoundedCornerShape(8.dp))) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val sw = size.width.toFloat()
                        val sh = size.height.toFloat()
                        currentOnColorChanged(
                            (down.position.x.coerceIn(0f, sw) / sw).fastCoerceIn(0f, 1f),
                            1f - (down.position.y.coerceIn(0f, sh) / sh).fastCoerceIn(0f, 1f)
                        )
                        drag(down.id) { change ->
                            change.consume()
                            currentOnColorChanged(
                                (change.position.x.coerceIn(0f, sw) / sw).fastCoerceIn(0f, 1f),
                                1f - (change.position.y.coerceIn(0f, sh) / sh).fastCoerceIn(0f, 1f)
                            )
                        }
                    }
                }) {
                    val w = size.width; val h = size.height
                    drawRect(hsvToColor(hue, 1f, 1f, 1f))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent), 0f, w))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, h))
                    val cx = sat * w; val cy = (1f - v) * h; val cr = 8.dp.toPx()
                    drawCircle(Color.White, cr, Offset(cx, cy), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, cr - 1.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
                }
    }
}

@Composable
private fun HuePicker(hue: Float, onHueChanged: (Float) -> Unit) {
    val currentOnHueChanged by rememberUpdatedState(onHueChanged)
    val colors = remember { listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red) }
    val trackHeight = 6.dp
    Canvas(Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val sw = size.width.toFloat()
            currentOnHueChanged((down.position.x.coerceIn(0f, sw) / sw * 360f).fastCoerceIn(0f, 360f))
            drag(down.id) { change ->
                change.consume()
                currentOnHueChanged((change.position.x.coerceIn(0f, sw) / sw * 360f).fastCoerceIn(0f, 360f))
            }
        }
    }) {
        val trackY = (size.height - trackHeight.toPx()) / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(colors, 0f, size.width),
            topLeft = Offset(0f, trackY),
            size = Size(size.width, trackHeight.toPx()),
            cornerRadius = CornerRadius(trackHeight.toPx() / 2f)
        )
        val px = (hue / 360f) * size.width
        val ringRadius = 7.dp.toPx()
        drawCircle(Color.White, ringRadius, Offset(px, size.height / 2f))
        drawCircle(hsvToColor(hue, 1f, 1f, 1f), ringRadius - 1.5.dp.toPx(), Offset(px, size.height / 2f))
    }
}

@Composable
private fun AlphaPicker(color: Color, alpha: Float, onAlphaChanged: (Float) -> Unit) {
    val currentOnAlphaChanged by rememberUpdatedState(onAlphaChanged)
    val trackHeight = 6.dp
    Canvas(Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val sw = size.width.toFloat()
            currentOnAlphaChanged((down.position.x.coerceIn(0f, sw) / sw).fastCoerceIn(0f, 1f))
            drag(down.id) { change ->
                change.consume()
                currentOnAlphaChanged((change.position.x.coerceIn(0f, sw) / sw).fastCoerceIn(0f, 1f))
            }
        }
    }) {
        val w = size.width
        val trackY = (size.height - trackHeight.toPx()) / 2f
        val step = 4.dp.toPx()
        var yy = trackY
        while (yy < trackY + trackHeight.toPx()) {
            var xx = 0f
            while (xx < w) {
                val isLight = ((xx / step).toInt() + ((yy - trackY) / step).toInt()) % 2 == 0
                drawRect(if (isLight) Color.White else Color.LightGray, Offset(xx, yy), Size(step, step))
                xx += step
            }
            yy += step
        }
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color), 0f, w),
            topLeft = Offset(0f, trackY),
            size = Size(w, trackHeight.toPx()),
            cornerRadius = CornerRadius(trackHeight.toPx() / 2f)
        )
        val px = alpha * w
        val ringRadius = 7.dp.toPx()
        drawCircle(Color.White, ringRadius, Offset(px, size.height / 2f))
        drawCircle(color.copy(alpha = alpha), ringRadius - 1.5.dp.toPx(), Offset(px, size.height / 2f))
    }
}

private fun hsvToColor(h: Float, s: Float, v: Float, a: Float): Color {
    val hh = (h / 60f) % 6f
    val i = hh.toInt()
    val f = hh - i
    val p = v * (1f - s)
    val q = v * (1f - f * s)
    val t = v * (1f - (1f - f) * s)
    val (r, g, b) = when (i) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    return Color(r, g, b, a)
}

private fun Color.toHue(): Float {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    if (d == 0f) return 0f
    val h = when (max) {
        r -> ((g - b) / d) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return ((h * 60f) + 360f) % 360f
}

private fun Color.toSaturation(): Float {
    val max = maxOf(red, green, blue)
    if (max == 0f) return 0f
    return (max - minOf(red, green, blue)) / max
}

private fun Color.toValue(): Float = maxOf(red, green, blue)

private fun Color.toArgbLong(): Long {
    val ai = (alpha * 255 + 0.5f).toInt().coerceIn(0, 255)
    val ri = (red * 255 + 0.5f).toInt().coerceIn(0, 255)
    val gi = (green * 255 + 0.5f).toInt().coerceIn(0, 255)
    val bi = (blue * 255 + 0.5f).toInt().coerceIn(0, 255)
    return ((ai.toLong() shl 24) or (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong())
}

public fun Long.toComposeColor(): Color {
    val ai = ((this shr 24) and 0xFF).toInt()
    val ri = ((this shr 16) and 0xFF).toInt()
    val gi = ((this shr 8) and 0xFF).toInt()
    val bi = (this and 0xFF).toInt()
    return Color(ri / 255f, gi / 255f, bi / 255f, ai / 255f)
}

private fun Long.toHexString(): String {
    val ai = ((this shr 24) and 0xFF).toInt()
    val ri = ((this shr 16) and 0xFF).toInt()
    val gi = ((this shr 8) and 0xFF).toInt()
    val bi = (this and 0xFF).toInt()
    return if (ai == 255) {
        String.format("#%02X%02X%02X", ri, gi, bi)
    } else {
        String.format("#%02X%02X%02X%02X", ai, ri, gi, bi)
    }
}

private fun hsvToHex(h: Float, s: Float, v: Float, a: Float): String {
    return hsvToColor(h, s, v, a).toArgbLong().toHexString()
}

private fun String.toColorFromHex(): Color? {
    val clean = removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    return try {
        if (clean.length == 6) {
            val ri = clean.substring(0, 2).toInt(16)
            val gi = clean.substring(2, 4).toInt(16)
            val bi = clean.substring(4, 6).toInt(16)
            Color(ri / 255f, gi / 255f, bi / 255f, 1f)
        } else {
            val ai = clean.substring(0, 2).toInt(16)
            val ri = clean.substring(2, 4).toInt(16)
            val gi = clean.substring(4, 6).toInt(16)
            val bi = clean.substring(6, 8).toInt(16)
            Color(ri / 255f, gi / 255f, bi / 255f, ai / 255f)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing hex color: $this", e)
        null
    }
}
