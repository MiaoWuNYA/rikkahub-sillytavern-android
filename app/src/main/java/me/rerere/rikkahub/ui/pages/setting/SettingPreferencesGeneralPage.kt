package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import me.rerere.ui.toast.ToastType
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import me.rerere.hugeicons.HugeIcons
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import android.os.Build
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import androidx.compose.material3.AlertDialog
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.remote.toProxyOrNull
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.Icon
import me.rerere.hugeicons.stroke.ArrowRight01
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.foundation.layout.size

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
            SettingPreferencesGeneralContent(vm = vm, contentPadding = contentPadding)
        }
}

@Composable
internal fun SettingPreferencesGeneralContent(vm: SettingVM, contentPadding: PaddingValues) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var ttsPlaybackSpeed by remember(settings.defaultTTSPlaybackSpeed) {
        mutableFloatStateOf(settings.defaultTTSPlaybackSpeed)
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { it.copy(displaySetting = setting) }
    }

    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    val httpClient = koinInject<OkHttpClient>()
    var userAgent by remember(settings.networkSetting.userAgent) {
        mutableStateOf(settings.networkSetting.userAgent)
    }
    var proxyUrl by remember(settings.networkSetting.proxyUrl) {
        mutableStateOf(settings.networkSetting.proxyUrl)
    }
    var proxyUsername by remember(settings.networkSetting.proxyUsername) {
        mutableStateOf(settings.networkSetting.proxyUsername)
    }
    var proxyPassword by remember(settings.networkSetting.proxyPassword) {
        mutableStateOf(settings.networkSetting.proxyPassword)
    }
    var proxyUrlDraft by remember { mutableStateOf("") }
    var proxyUsernameDraft by remember { mutableStateOf("") }
    var proxyPasswordDraft by remember { mutableStateOf("") }
    var proxyPasswordVisible by remember { mutableStateOf(false) }
    var proxyDialogVisible by remember { mutableStateOf(false) }
    val defaultUserAgent = "RikkaHub-Android/${BuildConfig.VERSION_NAME}"
    val proxyUrlInvalid = proxyUrlDraft.isNotBlank() && proxyUrlDraft.toProxyOrNull() == null
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    var proxyTesting by remember { mutableStateOf(false) }

    fun updateUserAgent(value: String) {
        userAgent = value
        vm.updateSettings {
            it.copy(
                networkSetting = it.networkSetting.copy(userAgent = value),
            )
        }
    }

    fun saveProxy() {
        vm.updateSettings {
            it.copy(
                networkSetting = it.networkSetting.copy(
                    proxyUrl = proxyUrlDraft,
                    proxyUsername = proxyUsernameDraft,
                    proxyPassword = proxyPasswordDraft,
                ),
            )
        }
    }

    fun resetProxy() {
        proxyUrlDraft = ""
        proxyUsernameDraft = ""
        proxyPasswordDraft = ""
    }

    fun testProxy() {
        val proxy = proxyUrl.toProxyOrNull() ?: return
        if (proxyTesting) return
        proxyTesting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val testClient = httpClient.newBuilder()
                        .proxy(proxy)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                    testClient.newCall(
                        Request.Builder()
                            .url(PROXY_TEST_URL)
                            .head()
                            .build()
                    ).execute().use { response ->
                        if (response.code != 204) {
                            throw IOException("HTTP ${response.code} ${response.message}")
                        }
                    }
                }
            }
            result.onSuccess {
                toaster.show(
                    resources.getString(R.string.backup_page_connection_success),
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                toaster.show(
                    resources.getString(
                        R.string.backup_page_connection_failed,
                        error.message.orEmpty(),
                    ),
                    type = ToastType.Error,
                )
            }
            proxyTesting = false
        }
    }

    if (proxyDialogVisible) {
        AlertDialog(
            onDismissRequest = { proxyDialogVisible = false },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_proxy))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = proxyUrlDraft,
                        onValueChange = { proxyUrlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy))
                        },
                        placeholder = { Text("http://127.0.0.1:7890") },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (proxyUrlInvalid) {
                                        R.string.setting_page_preferences_network_proxy_invalid
                                    } else {
                                        R.string.setting_page_preferences_network_proxy_desc
                                    }
                                )
                            )
                        },
                        isError = proxyUrlInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyUsernameDraft,
                        onValueChange = { proxyUsernameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_username)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyPasswordDraft,
                        onValueChange = { proxyPasswordDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_password)) },
                        visualTransformation = if (proxyPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { proxyPasswordVisible = !proxyPasswordVisible },
                            ) {
                                Icon(
                                    imageVector = if (proxyPasswordVisible) {
                                        HugeIcons.ViewOff
                                    } else {
                                        HugeIcons.View
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = ::resetProxy,
                        modifier = Modifier.align(Alignment.End),
                        enabled = proxyUrlDraft.isNotEmpty() ||
                            proxyUsernameDraft.isNotEmpty() ||
                            proxyPasswordDraft.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveProxy()
                        proxyDialogVisible = false
                    },
                    enabled = !proxyUrlInvalid,
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup {
                    switchItem(
                        checked = createNewConversationOnStart,
                        onCheckedChange = { createNewConversationOnStart = it },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                    )
                    switchItem(
                        checked = displaySetting.sendOnEnter,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(sendOnEnter = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.showMessageJumper,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showMessageJumper = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                    )
                    if (displaySetting.showMessageJumper) {
                        switchItem(
                            checked = displaySetting.messageJumperOnLeft,
                            onCheckedChange = { updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                        )
                    }
                    switchItem(
                        checked = displaySetting.enableAutoScroll,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableAutoScroll = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.skipCropImage,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(skipCropImage = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                    )
                    switchItem(
                        checked = displaySetting.pasteLongTextAsFile,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                            trailingContent = { CardGroupValue("${displaySetting.pasteLongTextThreshold}") },
                        ) {
                            Slider(
                                value = displaySetting.pasteLongTextThreshold.toFloat(),
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                },
                                valueRange = 100f..10000f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.enableVolumeKeyScroll,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                            trailingContent = { CardGroupValue("${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%") },
                        ) {
                            Slider(
                                value = displaySetting.volumeKeyScrollRatio,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                },
                                valueRange = 0.25f..1.0f,
                                steps = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_preferences_notification)) },
                ) {
                    item(
                        onClick = {
                            selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                            showUpdatePauseDialog = true
                        },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = {
                            Text(
                                if (updateChecksEnabled) {
                                    stringResource(R.string.setting_update_reminder_enabled)
                                } else {
                                    stringResource(
                                        R.string.setting_update_reminder_paused_until,
                                        Instant.ofEpochMilli(displaySetting.updateCheckDisabledUntilEpochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    switchItem(
                        checked = displaySetting.enableNotificationOnMessageGeneration,
                        onCheckedChange = {
                            if (it && !permissionState.allPermissionsGranted) {
                                permissionState.requestPermissions()
                            }
                            updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                        },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        switchItem(
                            checked = displaySetting.enableLiveUpdateNotification,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_preferences_network)) },
                ) {
                    switchItem(
                        checked = settings.networkSetting.enableAutoRetry,
                        onCheckedChange = { enabled ->
                            vm.updateSettings {
                                it.copy(
                                    networkSetting = it.networkSetting.copy(
                                        enableAutoRetry = enabled,
                                    ),
                                )
                            }
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_auto_retry_desc))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_auto_retry))
                        },
                    )
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_network_user_agent)) },
                    ) {
                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = ::updateUserAgent,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(defaultUserAgent) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        R.string.setting_page_preferences_network_user_agent_desc,
                                        defaultUserAgent,
                                    )
                                )
                            },
                            singleLine = true,
                        )
                        TextButton(
                            onClick = { updateUserAgent("") },
                            modifier = Modifier.align(Alignment.End),
                            enabled = userAgent.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.setting_model_page_reset_to_default))
                        }
                    }
                    item(
                        onClick = {
                            proxyUrlDraft = proxyUrl
                            proxyUsernameDraft = proxyUsername
                            proxyPasswordDraft = proxyPassword
                            proxyPasswordVisible = false
                            proxyDialogVisible = true
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy))
                        },
                        supportingContent = {
                            Text(
                                if (proxyUrl.isBlank()) {
                                    stringResource(
                                        R.string.setting_page_preferences_network_proxy_desc
                                    )
                                } else {
                                    proxyUrl
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_provider_page_test_connection))
                        },
                        trailingContent = {
                            FilledTonalButton(
                                onClick = ::testProxy,
                                enabled = proxyUrl.toProxyOrNull() != null && !proxyTesting,
                            ) {
                                if (proxyTesting) {
                                    ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    Text(stringResource(R.string.setting_provider_page_test))
                                }
                            }
                        },
                    )
                }
            }

        }
    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    ConnectedChoiceButtons(
                        options = UPDATE_PAUSE_DAY_OPTIONS,
                        selected = selectedUpdatePauseDays,
                        onSelect = { selectedUpdatePauseDays = it },
                        // 对话框底色和默认的按钮底色一样，换一个能分开的
                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                    ) { days, selected ->
                        ConnectedChoiceLabel(
                            text = stringResource(R.string.setting_update_reminder_pause_days, days),
                            selected = selected,
                        )
                    }
                    if (!updateChecksEnabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis =
                                    System.currentTimeMillis() + selectedUpdatePauseDays * MILLIS_PER_DAY,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

