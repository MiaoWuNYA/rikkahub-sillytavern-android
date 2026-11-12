package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import me.rerere.ui.toast.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import java.io.File

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Pair<Float, Float>? = null,
    freeStyleCropEnabled: Boolean = true
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }
    var preCropFile by remember { mutableStateOf<File?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                cropOutputUri?.let { croppedUri ->
                    onCroppedImageReady(croppedUri)
                }
            }

            UCrop.RESULT_ERROR -> {
                val error = result.data?.let { UCrop.getError(it) }
                Logging.log(
                    "CropLauncher",
                    "crop failed: ${error?.message} | ${error?.stackTraceToString()}"
                )
                toaster.show(
                    "Failed to crop image: ${error?.message ?: "unknown error"}",
                    type = ToastType.Error
                )
            }
        }
        cropOutputUri?.toFile()?.delete()
        cropOutputUri = null
        preCropFile?.delete()
        preCropFile = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        scope.launch {
            // HEIF/HEIC 交给 UCrop 读不到格式，先转成 JPEG 再裁
            val cropSource = withContext(Dispatchers.IO) {
                if (!ImageUtils.isHeifImage(context, sourceUri)) {
                    sourceUri
                } else {
                    val converted = File(context.appTempFolder, "crop_src_${System.currentTimeMillis()}.jpg")
                    preCropFile?.delete()
                    preCropFile = converted
                    if (ImageUtils.convertHeifToJpeg(context, sourceUri, converted)) {
                        Uri.fromFile(converted)
                    } else {
                        converted.delete()
                        preCropFile = null
                        null
                    }
                }
            }
            if (cropSource == null) {
                toaster.show("Failed to decode this image", type = ToastType.Error)
                return@launch
            }

            // 后缀必须与压缩格式一致，否则 UCrop 写文件后校验失败
            val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.png")
            val outputUri = Uri.fromFile(outputFile)
            cropOutputUri = outputUri

            var crop = UCrop.of(cropSource, outputUri).withOptions(UCrop.Options().apply {
                setFreeStyleCropEnabled(freeStyleCropEnabled)
                setAllowedGestures(
                    UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
                )
                setCompressionFormat(android.graphics.Bitmap.CompressFormat.PNG)
            }).withMaxResultSize(4096, 4096)
            aspectRatio?.let { (x, y) ->
                crop = crop.withAspectRatio(x, y)
            }
            val cropIntent = crop.getIntent(context)

            cropActivityLauncher.launch(cropIntent)
        }
    }

    return Pair(cropActivityLauncher, launchCrop)
}
