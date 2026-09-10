package me.rerere.rikkahub.plugin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.plugin.model.PluginConfigField
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginToolDefinition
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 插件详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailPage(
    pluginId: String,
    onNavigateBack: () -> Unit,
    viewModel: PluginViewModel = koinViewModel()
) {
    val plugin = viewModel.getPlugin(pluginId)

    if (plugin == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.plugin_detail_not_found))
        }
        return
    }

    var configValues by remember(pluginId) {
        mutableStateOf(plugin.config.toMutableMap())
    }

    LaunchedEffect(plugin.config) {
        configValues = plugin.config.toMutableMap()
    }

    val folders by viewModel.folders.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugin_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = HugeIcons.ArrowLeft01,
                            contentDescription = stringResource(R.string.plugin_action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            PluginInfoSection(plugin)

            Spacer(modifier = Modifier.height(24.dp))

            // 文件夹归属选择器
            FolderSelector(
                currentFolderId = plugin.folderId,
                folders = folders,
                onSelect = { folderId ->
                    viewModel.movePluginToFolder(pluginId, folderId)
                }
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (plugin.manifest.tools.isNotEmpty()) {
                Text(text = stringResource(R.string.plugin_detail_tools), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                plugin.manifest.tools.forEach { tool -> ToolItem(tool) }
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (plugin.manifest.config.isNotEmpty()) {
                Text(text = stringResource(R.string.plugin_detail_config), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                plugin.manifest.config.forEach { field ->
                    ConfigField(
                        field = field,
                        value = configValues[field.name],
                        onValueChange = { value ->
                            configValues = configValues.toMutableMap().apply {
                                if (value != null) put(field.name, value) else remove(field.name)
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.updatePluginConfig(pluginId, configValues) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = HugeIcons.CheckmarkCircle01, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.plugin_detail_save_config))
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            Text(text = stringResource(R.string.plugin_detail_path), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = plugin.directory.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 文件夹归属选择器
 * 用于将当前插件移动到不同文件夹
 */
@Composable
private fun FolderSelector(
    currentFolderId: String?,
    folders: List<PluginFolder>,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentFolder = folders.find { it.id == currentFolderId }
    val displayText = currentFolder?.name ?: stringResource(R.string.plugin_folder_ungrouped)

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { },
            readOnly = true,
            label = { Text(stringResource(R.string.plugin_detail_folder)) },
            supportingText = { Text(stringResource(R.string.plugin_detail_folder_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Folder01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plugin_folder_ungrouped)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name) },
                    onClick = {
                        onSelect(folder.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PluginInfoSection(plugin: PluginInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = plugin.manifest.icon,
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column {
            Text(text = plugin.manifest.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "v${plugin.manifest.version} · ${plugin.manifest.author}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = plugin.manifest.description, style = MaterialTheme.typography.bodyLarge)
    if (plugin.loadError != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.plugin_load_error, plugin.loadError),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ToolItem(tool: PluginToolDefinition) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = tool.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = tool.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (tool.parameters.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.plugin_detail_tool_params,
                    tool.parameters.joinToString { it.name }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConfigField(
    field: PluginConfigField,
    value: JsonElement?,
    onValueChange: (JsonElement?) -> Unit
) {
    when (field.type) {
        "boolean" -> BooleanConfigField(field, value, onValueChange)
        "select" -> SelectConfigField(field, value, onValueChange)
        "password" -> StringConfigField(field, value, onValueChange, isPassword = true)
        "model" -> ModelConfigField(field, value, onValueChange)
        else -> StringConfigField(field, value, onValueChange)
    }
}

@Composable
private fun StringConfigField(
    field: PluginConfigField,
    value: JsonElement?,
    onValueChange: (JsonElement?) -> Unit,
    isPassword: Boolean = false
) {
    var textValue by remember(field.name) {
        val v = value as? JsonPrimitive
        val d = field.default as? JsonPrimitive
        mutableStateOf(v?.contentOrNull ?: d?.contentOrNull ?: "")
    }
    OutlinedTextField(
        value = textValue,
        onValueChange = {
            textValue = it
            onValueChange(if (it.isEmpty()) null else JsonPrimitive(it))
        },
        label = { Text(field.label) },
        placeholder = { Text(field.placeholder ?: "") },
        supportingText = field.description?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun BooleanConfigField(
    field: PluginConfigField,
    value: JsonElement?,
    onValueChange: (JsonElement?) -> Unit
) {
    var checked by remember(field.name) {
        val v = value as? JsonPrimitive
        val d = field.default as? JsonPrimitive
        mutableStateOf(v?.booleanOrNull ?: d?.booleanOrNull ?: false)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = field.label, style = MaterialTheme.typography.bodyLarge)
            if (field.description != null) {
                Text(
                    text = field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = { checked = it; onValueChange(JsonPrimitive(it)) })
    }
}

@Composable
private fun SelectConfigField(
    field: PluginConfigField,
    value: JsonElement?,
    onValueChange: (JsonElement?) -> Unit
) {
    var selectedValue by remember(field.name) {
        val v = value as? JsonPrimitive
        val d = field.default as? JsonPrimitive
        mutableStateOf(v?.contentOrNull ?: d?.contentOrNull ?: field.options?.firstOrNull()?.value ?: "")
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = field.label, style = MaterialTheme.typography.bodyLarge)
        if (field.description != null) {
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        val optionsText = field.options?.joinToString(", ") { it.label } ?: ""
        OutlinedTextField(
            value = field.options?.find { it.value == selectedValue }?.label ?: selectedValue,
            onValueChange = { },
            readOnly = true,
            supportingText = { Text(optionsText) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModelConfigField(
    field: PluginConfigField,
    value: JsonElement?,
    onValueChange: (JsonElement?) -> Unit
) {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val providers = settings.providers

    // 解析当前选中的模型 ID
    val currentModelId = remember(value) {
        val v = value as? JsonPrimitive
        val modelIdStr = v?.contentOrNull
        try {
            modelIdStr?.let { Uuid.parse(it) }
        } catch (_: Exception) {
            null
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = field.label, style = MaterialTheme.typography.bodyLarge)
        if (field.description != null) {
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        ModelSelector(
            modelId = currentModelId,
            providers = providers,
            type = ModelType.CHAT,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { model ->
                // 保存选中的模型 ID 为字符串
                onValueChange(JsonPrimitive(model.id.toString()))
            }
        )
    }
}
