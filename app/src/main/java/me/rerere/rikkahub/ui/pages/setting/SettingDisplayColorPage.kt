package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.SillyTavernTheme
import me.rerere.rikkahub.data.model.applyTo
import me.rerere.rikkahub.data.model.parseSillyTavernTheme
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ColorPickerDialog
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun SettingDisplayColorPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    // 图片导入：拷贝到应用私有目录并存文件 URI，避免 content URI 权限失效
    @Composable
    fun rememberImageImporter(onImported: (String) -> Unit): ManagedActivityResultLauncher<String, Uri?> {
        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { importThemeImage(context, uri) }
                }.onSuccess { path -> onImported(path) }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showGlobalTextColorPicker by remember { mutableStateOf(false) }
    var showUserBubbleColorPicker by remember { mutableStateOf(false) }
    var showAssistantBubbleColorPicker by remember { mutableStateOf(false) }
    var showThinkingBubbleColorPicker by remember { mutableStateOf(false) }
    var showChatBackgroundColorPicker by remember { mutableStateOf(false) }
    var showPrimaryColorPicker by remember { mutableStateOf(false) }
    var showInputFieldColorPicker by remember { mutableStateOf(false) }

    val drawerImagePicker = rememberImageImporter { path ->
        updateDisplaySetting(displaySetting.copy(drawerBackgroundPath = path))
    }
    val userBubbleImagePicker = rememberImageImporter { path ->
        updateDisplaySetting(displaySetting.copy(userBubbleImagePath = path))
    }
    val assistantBubbleImagePicker = rememberImageImporter { path ->
        updateDisplaySetting(displaySetting.copy(assistantBubbleImagePath = path))
    }

    // 酒馆（SillyTavern）主题导入：选 JSON → 解析 → 确认对话框预览覆盖项 → 应用
    val toaster = LocalToaster.current
    var pendingTheme by remember { mutableStateOf<SillyTavernTheme?>(null) }
    val themePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
                parseSillyTavernTheme(text)
            }.onSuccess { theme ->
                pendingTheme = theme
            }.onFailure { error ->
                toaster.show("主题解析失败：${error.message.orEmpty()}", type = ToastType.Error)
            }
        }
    }

    if (showGlobalTextColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.globalTextColor,
            defaultColor = MaterialTheme.colorScheme.onBackground,
            onConfirm = { updateDisplaySetting(displaySetting.copy(globalTextColor = it)) },
            onDismiss = { showGlobalTextColorPicker = false }
        )
    }
    if (showUserBubbleColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.userBubbleColor,
            defaultColor = MaterialTheme.colorScheme.primaryContainer,
            onConfirm = { updateDisplaySetting(displaySetting.copy(userBubbleColor = it)) },
            onDismiss = { showUserBubbleColorPicker = false }
        )
    }
    if (showAssistantBubbleColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.assistantBubbleColor,
            defaultColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onConfirm = { updateDisplaySetting(displaySetting.copy(assistantBubbleColor = it)) },
            onDismiss = { showAssistantBubbleColorPicker = false }
        )
    }
    if (showThinkingBubbleColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.thinkingBubbleColor,
            defaultColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onConfirm = { updateDisplaySetting(displaySetting.copy(thinkingBubbleColor = it)) },
            onDismiss = { showThinkingBubbleColorPicker = false }
        )
    }
    if (showChatBackgroundColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.chatBackgroundColor,
            defaultColor = MaterialTheme.colorScheme.background,
            onConfirm = { updateDisplaySetting(displaySetting.copy(chatBackgroundColor = it)) },
            onDismiss = { showChatBackgroundColorPicker = false }
        )
    }
    if (showPrimaryColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.primaryColor,
            defaultColor = MaterialTheme.colorScheme.primary,
            onConfirm = { updateDisplaySetting(displaySetting.copy(primaryColor = it)) },
            onDismiss = { showPrimaryColorPicker = false }
        )
    }
    if (showInputFieldColorPicker) {
        ColorPickerDialog(
            initialColor = displaySetting.inputFieldColor,
            defaultColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onConfirm = { updateDisplaySetting(displaySetting.copy(inputFieldColor = it)) },
            onDismiss = { showInputFieldColorPicker = false }
        )
    }

    pendingTheme?.let { theme ->
        val changeLabels = themeChangeLabels(theme, displaySetting)
        AlertDialog(
            onDismissRequest = { pendingTheme = null },
            title = { Text("应用酒馆主题") },
            text = {
                Text(
                    buildString {
                        append("主题「${theme.name ?: "未命名"}」将覆盖以下外观设置：\n")
                        if (changeLabels.isEmpty()) {
                            append("\n未能识别出可应用的字段（应用后设置不会有变化）")
                        } else {
                            changeLabels.forEach { label -> append("\n· $label") }
                        }
                        append("\n\n其余显示设置保持不变，自定义 CSS 不会被导入")
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    updateDisplaySetting(theme.applyTo(displaySetting))
                    toaster.show(
                        "已应用主题：${theme.name ?: "未命名"}；未能识别的字段已忽略",
                        type = ToastType.Success
                    )
                    pendingTheme = null
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTheme = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("聊天外观自定义") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("import") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("导入") },
                ) {
                    item(
                        headlineContent = { Text("导入酒馆（SillyTavern）主题") },
                        supportingContent = { Text("选择酒馆主题 JSON，应用其配色与字号设置") },
                        trailingContent = {
                            TextButton(onClick = { themePickerLauncher.launch("application/json") }) {
                                Text("导入")
                            }
                        },
                    )
                }
            }

            item("colors") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("颜色自定义") },
                ) {
                    item(
                        headlineContent = { Text("主色调（按钮/链接）") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.primaryColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.primary,
                                onPick = { showPrimaryColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(primaryColor = null)) },
                                resetEnabled = displaySetting.primaryColor != null,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("全局字体颜色") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.globalTextColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.onBackground,
                                onPick = { showGlobalTextColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(globalTextColor = null)) },
                                resetEnabled = displaySetting.globalTextColor != null,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("用户气泡颜色") },
                        supportingContent = { Text("自定义用户消息气泡背景色") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.userBubbleColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.primaryContainer,
                                onPick = { showUserBubbleColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(userBubbleColor = null)) },
                                resetEnabled = displaySetting.userBubbleColor != null,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("AI气泡颜色") },
                        supportingContent = { Text("自定义AI消息气泡背景色") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.assistantBubbleColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                onPick = { showAssistantBubbleColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(assistantBubbleColor = null)) },
                                resetEnabled = displaySetting.assistantBubbleColor != null,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("思维链气泡颜色") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.thinkingBubbleColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                onPick = { showThinkingBubbleColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(thinkingBubbleColor = null)) },
                                resetEnabled = displaySetting.thinkingBubbleColor != null,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("聊天背景色") },
                        supportingContent = { Text("助手设置里有背景图时图片优先") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.chatBackgroundColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.background,
                                onPick = { showChatBackgroundColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(chatBackgroundColor = null)) },
                                resetEnabled = displaySetting.chatBackgroundColor != null,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("输入框背景颜色") },
                        trailingContent = {
                            ColorItemTrailing(
                                color = displaySetting.inputFieldColor?.toComposeColor()
                                    ?: MaterialTheme.colorScheme.surfaceContainerLow,
                                onPick = { showInputFieldColorPicker = true },
                                onReset = { updateDisplaySetting(displaySetting.copy(inputFieldColor = null)) },
                                resetEnabled = displaySetting.inputFieldColor != null,
                            )
                        },
                    )
                }
            }

            item("bubbles") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("气泡") },
                ) {
                    item(
                        headlineContent = { Text("气泡不透明度") },
                        supportingContent = { Text("${(displaySetting.bubbleOpacity * 100).toInt()}%") },
                        trailingContent = {
                            Slider(
                                value = displaySetting.bubbleOpacity,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleOpacity = it.coerceIn(0.1f, 1f)))
                                },
                                valueRange = 0.1f..1f,
                                modifier = Modifier.width(160.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("气泡圆角") },
                        supportingContent = { Text("${displaySetting.bubbleCornerRadius.toInt()} dp") },
                        trailingContent = {
                            Slider(
                                value = displaySetting.bubbleCornerRadius,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleCornerRadius = it.coerceIn(0f, 28f)))
                                },
                                valueRange = 0f..28f,
                                modifier = Modifier.width(160.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("用户气泡背景图") },
                        supportingContent = {
                            Text(
                                if (displaySetting.userBubbleImagePath.isBlank()) "未设置"
                                else "已设置"
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { userBubbleImagePicker.launch("image/*") }) { Text("选择") }
                                if (displaySetting.userBubbleImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting(displaySetting.copy(userBubbleImagePath = ""))
                                    }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("AI气泡背景图") },
                        supportingContent = {
                            Text(
                                if (displaySetting.assistantBubbleImagePath.isBlank()) "未设置"
                                else "已设置"
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { assistantBubbleImagePicker.launch("image/*") }) { Text("选择") }
                                if (displaySetting.assistantBubbleImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting(displaySetting.copy(assistantBubbleImagePath = ""))
                                    }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("气泡背景图叠加颜色遮罩") },
                        supportingContent = { Text("开启后图片上叠加原气泡颜色；关闭则纯图片") },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.bubbleImageOverlayEnabled,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleImageOverlayEnabled = it))
                                },
                            )
                        },
                    )
                }
            }

            item("backgrounds") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("背景") },
                ) {
                    item(
                        headlineContent = { Text("抽屉（侧边栏）背景图") },
                        supportingContent = {
                            Text(if (displaySetting.drawerBackgroundPath.isBlank()) "未设置" else "已设置")
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { drawerImagePicker.launch("image/*") }) { Text("选择") }
                                if (displaySetting.drawerBackgroundPath.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting(displaySetting.copy(drawerBackgroundPath = ""))
                                    }) { Text("重置") }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 计算酒馆主题将覆盖的设置项名称（用于导入前预览） */
private fun themeChangeLabels(theme: SillyTavernTheme, base: DisplaySetting): List<String> {
    val patched = theme.applyTo(base)
    val labels = mutableListOf<String>()
    if (patched.globalTextColor != base.globalTextColor) labels.add("全局字体颜色")
    if (patched.chatBackgroundColor != base.chatBackgroundColor) labels.add("聊天背景色")
    if (patched.userBubbleColor != base.userBubbleColor) labels.add("用户气泡颜色")
    if (patched.assistantBubbleColor != base.assistantBubbleColor) labels.add("AI气泡颜色")
    if (patched.quoteColor != base.quoteColor) labels.add("引用颜色")
    if (patched.italicsColor != base.italicsColor) labels.add("斜体颜色")
    if (patched.fontSizeRatio != base.fontSizeRatio) labels.add("字号比例")
    return labels
}

@Composable
private fun ColorItemTrailing(
    color: Color,
    onPick: () -> Unit,
    onReset: () -> Unit,
    resetEnabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )
        TextButton(onClick = onPick) { Text("自定义") }
        if (resetEnabled) {
            TextButton(onClick = onReset) { Text("重置") }
        }
    }
}

/** 把选中的图片拷贝到应用私有目录，返回可长期使用的文件 URI 字符串 */
private fun importThemeImage(context: Context, uri: Uri): String {
    val imageDir = File(context.filesDir, "images/theme").apply { mkdirs() }
    val displayName = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
    val extension = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: "png"
    val targetFile = File(imageDir, "theme_img_${System.currentTimeMillis()}.$extension")
    context.contentResolver.openInputStream(uri)?.use { input ->
        targetFile.outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取所选图片")
    return Uri.fromFile(targetFile).toString()
}
