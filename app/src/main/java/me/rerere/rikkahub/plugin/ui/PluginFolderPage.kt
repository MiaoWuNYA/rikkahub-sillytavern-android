package me.rerere.rikkahub.plugin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkahub.R
import org.koin.androidx.compose.koinViewModel

/**
 * 文件夹内插件列表页
 * 显示某个文件夹下的所有插件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginFolderPage(
    folderId: String,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: PluginViewModel = koinViewModel()
) {
    val plugins by viewModel.plugins.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val folder = folders.find { it.id == folderId }
    val folderPlugins = plugins.filter { it.folderId == folderId }

    val importSuccessMessage = stringResource(R.string.plugin_import_success)
    val importFailedTemplate = stringResource(R.string.plugin_import_failed)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importPlugin(it, folderId) }
    }

    LaunchedEffect(importState) {
        when (importState) {
            is PluginViewModel.ImportState.Success -> {
                snackbarHostState.showSnackbar(message = importSuccessMessage)
                viewModel.resetImportState()
            }
            is PluginViewModel.ImportState.Error -> {
                snackbarHostState.showSnackbar(
                    message = importFailedTemplate.format(
                        (importState as PluginViewModel.ImportState.Error).message
                    )
                )
                viewModel.resetImportState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = folder?.name ?: stringResource(R.string.plugin_folder_default),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.plugin_action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch("application/zip") },
                icon = { Icon(HugeIcons.PlusSign, null) },
                text = { Text(stringResource(R.string.plugin_import)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (folderPlugins.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.plugin_folder_page_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.plugin_folder_page_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = folderPlugins, key = { it.manifest.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onClick = { onNavigateToDetail(plugin.manifest.id) },
                            onToggle = { enabled ->
                                viewModel.togglePlugin(plugin.manifest.id, enabled)
                            },
                            onDelete = { viewModel.deletePlugin(plugin.manifest.id) }
                        )
                    }
                }
            }
        }
    }
}
