package me.rerere.rikkahub.ui.pages.extensions

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Append-only evidence for this round; earlier screenshots remain in their original directories. */
internal fun captureLorebookEditorEvidence(name: String, captured: Bitmap? = null) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    SystemClock.sleep(150)
    val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "worldbook-editor-round7").apply { mkdirs() }
    val bitmap = captured ?: checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    var file = File(directory, "$name.png")
    var index = 0
    while (file.exists()) file = File(directory, "$name-${++index}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
