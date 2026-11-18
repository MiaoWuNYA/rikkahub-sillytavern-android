package me.rerere.rikkahub.ui.pages.extensions

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun captureLorebookRepairEvidence(name: String, captured: Bitmap? = null) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    SystemClock.sleep(150)
    val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "worldbook-editor-round8").apply { mkdirs() }
    var output = File(directory, "$name.png")
    var index = 0
    while (output.exists()) output = File(directory, "$name-${++index}.png")
    output.outputStream().use { (captured ?: checkNotNull(instrumentation.uiAutomation.takeScreenshot())).compress(Bitmap.CompressFormat.PNG, 100, it) }
}
