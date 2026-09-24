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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.SillyTavernTheme
import me.rerere.rikkahub.data.model.ThemeFont
import me.rerere.rikkahub.data.model.applyTo
import me.rerere.rikkahub.data.model.extractBackgroundImageUrl
import me.rerere.rikkahub.data.model.extractThemeFont
import me.rerere.rikkahub.data.model.parseSillyTavernTheme
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ColorPickerDialog
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.util.concurrent.TimeUnit

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
    val chatBackgroundImagePicker = rememberImageImporter { path ->
        deleteThemeFileIfOwned(context, displaySetting.chatBackgroundImagePath, path)
        updateDisplaySetting(displaySetting.copy(chatBackgroundImagePath = path))
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
                toaster.show(
                    context.getString(R.string.setting_display_st_theme_parse_failed, error.message.orEmpty()),
                    type = ToastType.Error
                )
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
            title = { Text(stringResource(R.string.setting_display_apply_st_theme)) },
            text = {
                Text(
                    buildString {
                        append(
                            stringResource(
                                R.string.setting_display_st_theme_will_override,
                                theme.name ?: stringResource(R.string.setting_display_st_theme_unnamed)
                            )
                        )
                        if (changeLabels.isEmpty()) {
                            append(stringResource(R.string.setting_display_st_theme_no_fields))
                        } else {
                            changeLabels.forEach { label -> append("\n· ${stringResource(label)}") }
                        }
                        append(stringResource(R.string.setting_display_st_theme_unchanged_note))
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingTheme = null
                    scope.launch {
                        var patched = theme.applyTo(displaySetting)
                        var bgNote = ""
                        // 背景图：从 custom_css 提取地址并下载到应用私有目录
                        val bgUrl = extractBackgroundImageUrl(theme.customCss)
                        if (bgUrl != null) {
                            runCatching {
                                withContext(Dispatchers.IO) { storeThemeBackground(context, bgUrl) }
                            }.onSuccess { path ->
                                deleteThemeFileIfOwned(context, displaySetting.chatBackgroundImagePath, path)
                                patched = patched.copy(chatBackgroundImagePath = path)
                            }.onFailure {
                                bgNote = context.getString(
                                    R.string.setting_display_st_theme_bg_note_failed,
                                    it.message ?: context.getString(R.string.setting_display_st_theme_unknown_error)
                                )
                            }
                        }
                        // 主题字体：@font-face 里的 ttf/otf 自动下载并设为聊天字体
                        val themeFont = extractThemeFont(theme.customCss)
                        if (themeFont != null) {
                            runCatching {
                                withContext(Dispatchers.IO) { storeThemeFont(context, themeFont) }
                            }.onSuccess { relativePath ->
                                deleteThemeFontIfOwned(context, displaySetting.chatCustomFontPath, relativePath)
                                patched = patched.copy(
                                    chatFontFamily = ChatFontFamily.CUSTOM,
                                    chatCustomFontPath = relativePath,
                                    chatCustomFontName = themeFont.family
                                        ?: context.getString(R.string.setting_display_st_theme_default_font_name),
                                )
                            }.onFailure {
                                bgNote += context.getString(
                                    R.string.setting_display_st_theme_font_note_failed,
                                    it.message ?: context.getString(R.string.setting_display_st_theme_unknown_error)
                                )
                            }
                        }
                        updateDisplaySetting(patched)
                        toaster.show(
                            context.getString(
                                R.string.setting_display_st_theme_applied_toast,
                                theme.name ?: context.getString(R.string.setting_display_st_theme_unnamed),
                                bgNote
                            ),
                            type = if (bgNote.isEmpty()) ToastType.Success else ToastType.Warning
                        )
                    }
                }) { Text(stringResource(R.string.setting_display_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTheme = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_display_title)) },
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
                    title = { Text(stringResource(R.string.setting_display_import_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_import_st_theme)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_import_st_theme_desc))
                        },
                        trailingContent = {
                            TextButton(onClick = { themePickerLauncher.launch("application/json") }) {
                                Text(stringResource(R.string.setting_display_import_button))
                            }
                        },
                    )
                }
            }

            item("colors") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_display_colors_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_primary_color)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_global_text_color)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_user_bubble_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_user_bubble_color_desc)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_assistant_bubble_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_assistant_bubble_color_desc)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_thinking_bubble_color)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_chat_bg_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_chat_bg_color_desc)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_input_bg_color)) },
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
                    title = { Text(stringResource(R.string.setting_display_bubbles_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_bubble_opacity)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_bubble_corner_radius)) },
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
                        headlineContent = { Text(stringResource(R.string.setting_display_user_bubble_image)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.userBubbleImagePath.isBlank()) stringResource(R.string.setting_display_not_set)
                                else stringResource(R.string.setting_display_set)
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { userBubbleImagePicker.launch("image/*") }) { Text(stringResource(R.string.setting_display_choose)) }
                                if (displaySetting.userBubbleImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting(displaySetting.copy(userBubbleImagePath = ""))
                                    }) { Text(stringResource(R.string.setting_display_reset)) }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_assistant_bubble_image)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.assistantBubbleImagePath.isBlank()) stringResource(R.string.setting_display_not_set)
                                else stringResource(R.string.setting_display_set)
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { assistantBubbleImagePicker.launch("image/*") }) { Text(stringResource(R.string.setting_display_choose)) }
                                if (displaySetting.assistantBubbleImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting(displaySetting.copy(assistantBubbleImagePath = ""))
                                    }) { Text(stringResource(R.string.setting_display_reset)) }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_bubble_image_overlay)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_bubble_image_overlay_desc)) },
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
                    title = { Text(stringResource(R.string.setting_display_background_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_chat_bg_image)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.chatBackgroundImagePath.isBlank()) stringResource(R.string.setting_display_chat_bg_image_not_set)
                                else stringResource(R.string.setting_display_set)
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { chatBackgroundImagePicker.launch("image/*") }) { Text(stringResource(R.string.setting_display_choose)) }
                                if (displaySetting.chatBackgroundImagePath.isNotBlank()) {
                                    TextButton(onClick = {
                                        deleteThemeFileIfOwned(context, displaySetting.chatBackgroundImagePath, null)
                                        updateDisplaySetting(displaySetting.copy(chatBackgroundImagePath = ""))
                                    }) { Text(stringResource(R.string.setting_display_reset)) }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_drawer_bg_image)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.drawerBackgroundPath.isBlank()) stringResource(R.string.setting_display_not_set)
                                else stringResource(R.string.setting_display_set)
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { drawerImagePicker.launch("image/*") }) { Text(stringResource(R.string.setting_display_choose)) }
                                if (displaySetting.drawerBackgroundPath.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting(displaySetting.copy(drawerBackgroundPath = ""))
                                    }) { Text(stringResource(R.string.setting_display_reset)) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 计算酒馆主题将覆盖的设置项名称（用于导入前预览），返回字符串资源 ID */
private fun themeChangeLabels(theme: SillyTavernTheme, base: DisplaySetting): List<Int> {
    val patched = theme.applyTo(base)
    val labels = mutableListOf<Int>()
    if (patched.globalTextColor != base.globalTextColor) labels.add(R.string.setting_display_st_theme_label_global_text_color)
    if (patched.chatBackgroundColor != base.chatBackgroundColor) labels.add(R.string.setting_display_st_theme_label_chat_bg_color)
    if (patched.userBubbleColor != base.userBubbleColor) labels.add(R.string.setting_display_st_theme_label_user_bubble_color)
    if (patched.assistantBubbleColor != base.assistantBubbleColor) labels.add(R.string.setting_display_st_theme_label_assistant_bubble_color)
    if (patched.quoteColor != base.quoteColor) labels.add(R.string.setting_display_st_theme_label_quote_color)
    if (patched.italicsColor != base.italicsColor) labels.add(R.string.setting_display_st_theme_label_italics_color)
    if (patched.fontSizeRatio != base.fontSizeRatio) labels.add(R.string.setting_display_st_theme_label_font_size_ratio)
    if (patched.showAssistantBubble != base.showAssistantBubble) labels.add(R.string.setting_display_st_theme_label_assistant_bubble_display)
    if (patched.bubbleCornerRadius != base.bubbleCornerRadius) labels.add(R.string.setting_display_st_theme_label_bubble_corner_radius)
    if (extractBackgroundImageUrl(theme.customCss) != null) labels.add(R.string.setting_display_st_theme_label_chat_bg_image_auto)
    if (extractThemeFont(theme.customCss) != null) labels.add(R.string.setting_display_st_theme_label_theme_font_auto)
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
        TextButton(onClick = onPick) { Text(stringResource(R.string.setting_display_custom)) }
        if (resetEnabled) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.setting_display_reset)) }
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

/** 酒馆主题远程资源抓取结果 */
private class ThemeFileBytes(val bytes: ByteArray, val extension: String)

/** 下载 http(s) URL / 解码 data URI，扩展名优先取 URL 路径上的，其次 Content-Type / MIME */
private fun fetchThemeFile(url: String): ThemeFileBytes {
    if (url.startsWith("data:", ignoreCase = true)) {
        val marker = ";base64,"
        val idx = url.indexOf(marker, ignoreCase = true)
        require(idx > 0) { "不支持的 data URI" }
        val mime = url.substringAfter("data:", "").substringBefore(";").lowercase()
        val bytes = android.util.Base64.decode(url.substring(idx + marker.length), android.util.Base64.DEFAULT)
        val extension = mime.substringAfter('/', "").filter { it.isLetterOrDigit() }.takeIf { it.length in 2..5 } ?: ""
        return ThemeFileBytes(bytes, extension)
    }
    require(url.startsWith("http://") || url.startsWith("https://")) { "不支持的资源地址" }
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android) rikkahub-theme-import")
        .build()
    client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val bytes = response.body.bytes()
        val urlExt = url.substringBefore('?').substringAfterLast('/')
            .substringAfterLast('.', "").filter { it.isLetterOrDigit() }
        val extension = urlExt.takeIf { it.length in 2..5 }
            ?: response.header("Content-Type")
                ?.substringAfter('/')?.substringBefore(';')
                ?.filter { it.isLetterOrDigit() }
                ?.takeIf { it.length in 2..5 }
            ?: ""
        return ThemeFileBytes(bytes, extension)
    }
}

/** 把酒馆主题里的背景图（http URL 或 data URI）保存到应用私有目录，返回文件 URI */
private fun storeThemeBackground(context: Context, url: String): String {
    val fetched = fetchThemeFile(url)
    check(fetched.bytes.size <= 25_000_000) { "背景图过大（>25MB）" }
    val imageDir = File(context.filesDir, "images/theme").apply { mkdirs() }
    val targetFile = File(imageDir, "theme_bg_${System.currentTimeMillis()}.${fetched.extension.ifBlank { "png" }}")
    targetFile.writeBytes(fetched.bytes)
    return Uri.fromFile(targetFile).toString()
}

/** 把 @font-face 里的主题字体下载为应用聊天字体，返回 filesDir 相对路径（并验证可被 Android 加载） */
private fun storeThemeFont(context: Context, font: ThemeFont): String {
    val fetched = fetchThemeFile(font.url)
    check(fetched.bytes.size <= 30_000_000) { "字体文件过大（>30MB）" }
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "theme_font_${System.currentTimeMillis()}.${fetched.extension.ifBlank { "ttf" }}")
    targetFile.writeBytes(fetched.bytes)
    runCatching { android.graphics.Typeface.createFromFile(targetFile) }
        .onFailure {
            targetFile.delete()
            throw IllegalArgumentException("字体文件无法被系统加载", it)
        }
    return "${FileFolders.FONTS}/${targetFile.name}"
}

/** 删除被替换/重置的旧主题背景文件（仅限应用私有 images/theme 目录内的文件） */
private fun deleteThemeFileIfOwned(context: Context, path: String?, keep: String?) {
    if (path.isNullOrBlank() || path == keep) return
    val owned = File(context.filesDir, "images/theme").absolutePath
    val file = Uri.parse(path).path?.let(::File) ?: return
    if (file.absolutePath.startsWith(owned)) file.delete()
}

/** 删除被替换的旧主题字体（只动本功能写入的 theme_font_* 文件，不碰用户手动导入的字体） */
private fun deleteThemeFontIfOwned(context: Context, relativePath: String?, keep: String?) {
    if (relativePath.isNullOrBlank() || relativePath == keep) return
    if (!File(relativePath).name.startsWith("theme_font_")) return
    File(context.filesDir, relativePath).takeIf { it.isFile }?.delete()
}
