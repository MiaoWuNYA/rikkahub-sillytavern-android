package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WechatBotSetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.weixin.WeixinBotClient
import me.rerere.rikkahub.service.WeixinBotService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 微信 Bot 设置页.
 *
 * 功能: 开关 (启停服务) / 扫码登录 / 助手状态.
 * 扫码流程: 点登录 → getQrcode → ZXing 渲染二维码 → 轮询 status → confirmed 存 token.
 */
@Composable
fun SettingWeixinBotPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val client: WeixinBotClient = koinInject()
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var botSetting by remember(settings) { mutableStateOf(settings.wechatBotSetting) }
    LaunchedEffect(settings) { botSetting = settings.wechatBotSetting }

    fun update(newSetting: WechatBotSetting) {
        botSetting = newSetting
        vm.updateSettings(settings.copy(wechatBotSetting = newSetting))
    }

    // 扫码登录状态
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    var showEnableRiskDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showEnableRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_weixin_bot_title),
            message = stringResource(R.string.risk_weixin_bot_message),
            onConfirm = {
                showEnableRiskDialog = false
                update(botSetting.copy(enabled = true))
                WeixinBotService.start(context)
            },
            onDismiss = { showEnableRiskDialog = false }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.weixin_bot_title)) },
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
            // 说明
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.weixin_bot_section_about)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    item(
                        leadingContent = { Icon(imageVector = HugeIcons.MessageMultiple01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.weixin_bot_what_is)) },
                        supportingContent = { Text(stringResource(R.string.weixin_bot_what_is_desc)) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.weixin_bot_linked_assistant)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.weixin_bot_linked_assistant_desc,
                                    settings.getCurrentAssistant().name.ifBlank { stringResource(R.string.weixin_bot_unnamed) }
                                )
                            )
                        }
                    )
                }
            }

            // 扫码登录
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.weixin_bot_section_login)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.weixin_bot_login_status)) },
                        supportingContent = {
                            Text(
                                if (botSetting.botToken.isNotBlank()) {
                                    stringResource(
                                        R.string.weixin_bot_logged_in,
                                        botSetting.botId.ifBlank { stringResource(R.string.weixin_bot_unknown) }
                                    )
                                } else {
                                    stringResource(R.string.weixin_bot_not_logged_in)
                                }
                            )
                        },
                        trailingContent = {
                            FilledTonalButton(
                                enabled = !isLoggingIn,
                                onClick = {
                                    scope.launch {
                                        isLoggingIn = true
                                        loginStatus = context.getString(R.string.weixin_bot_fetching_qrcode)
                                        try {
                                            val qr = client.getQrcode(botSetting.baseUrl)
                                            qrContent = qr.qrcodeImgContent
                                            loginStatus = context.getString(R.string.weixin_bot_scan_prompt)
                                            qrBitmap = withContext(Dispatchers.Default) {
                                                runCatching { renderQrCode(qr.qrcodeImgContent, 480) }
                                                    .onFailure {
                                                        loginStatus = context.getString(
                                                            R.string.weixin_bot_qr_render_failed, it.message.orEmpty()
                                                        )
                                                    }
                                                    .getOrNull()
                                            }
                                            // 轮询扫码状态, 最多 5 分钟
                                            val waitingStatus = context.getString(R.string.weixin_bot_waiting_scan)
                                            val deadline = System.currentTimeMillis() + 5 * 60_000
                                            var currentQrcode = qr.qrcode
                                            var refreshCount = 0
                                            var confirmed = false
                                            while (System.currentTimeMillis() < deadline && !confirmed) {
                                                val st = client.getQrcodeStatus(currentQrcode, botSetting.baseUrl)
                                                when (st.status) {
                                                    "confirmed" -> {
                                                        update(
                                                            botSetting.copy(
                                                                botToken = st.botToken ?: "",
                                                                baseUrl = st.baseUrl ?: botSetting.baseUrl,
                                                                botId = st.botId ?: "",
                                                            )
                                                        )
                                                        loginStatus = context.getString(R.string.weixin_bot_login_success)
                                                        confirmed = true
                                                    }
                                                    "scaned" -> loginStatus = context.getString(R.string.weixin_bot_scanned)
                                                    "expired" -> {
                                                        refreshCount++
                                                        if (refreshCount > 3) {
                                                            loginStatus = context.getString(R.string.weixin_bot_qr_expired_many)
                                                            break
                                                        }
                                                        loginStatus = context.getString(R.string.weixin_bot_qr_expired_refresh)
                                                        val newQr = client.getQrcode(botSetting.baseUrl)
                                                        currentQrcode = newQr.qrcode
                                                        qrBitmap = withContext(Dispatchers.Default) {
                                                            runCatching { renderQrCode(newQr.qrcodeImgContent, 480) }
                                                                .getOrNull()
                                                        }
                                                    }
                                                    else -> loginStatus = waitingStatus // wait
                                                }
                                                delay(1000)
                                            }
                                            if (!confirmed && loginStatus == waitingStatus) {
                                                loginStatus = context.getString(R.string.weixin_bot_login_timeout)
                                            }
                                            qrBitmap = null
                                        } catch (e: Exception) {
                                            loginStatus = context.getString(
                                                R.string.weixin_bot_login_failed,
                                                e.message ?: e::class.simpleName.orEmpty()
                                            )
                                        } finally {
                                            isLoggingIn = false
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    if (isLoggingIn) stringResource(R.string.weixin_bot_logging_in)
                                    else if (botSetting.botToken.isNotBlank()) stringResource(R.string.weixin_bot_relogin)
                                    else stringResource(R.string.weixin_bot_scan_login)
                                )
                            }
                        }
                    )
                    if (loginStatus.isNotBlank() && loginStatus != context.getString(R.string.weixin_bot_not_logged_in)) {
                        item(
                            headlineContent = { Text(stringResource(R.string.weixin_bot_status)) },
                            supportingContent = { Text(loginStatus) }
                        )
                    }
                    if (botSetting.botToken.isNotBlank()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.weixin_bot_logout)) },
                            trailingContent = {
                                FilledTonalButton(onClick = {
                                    WeixinBotService.stop(context)
                                    update(botSetting.copy(botToken = "", botId = ""))
                                    loginStatus = context.getString(R.string.weixin_bot_logged_out)
                                }) { Text(stringResource(R.string.weixin_bot_logout_button)) }
                            }
                        )
                    }
                }
            }

            // 二维码区 —— 独立顶层 item, 确保状态变化一定可见
            if (qrContent != null || qrBitmap != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = loginStatus,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 二维码图 (白底)
                        qrBitmap?.let { bmp ->
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.weixin_bot_qr_content_desc),
                                modifier = Modifier
                                    .size(240.dp)
                                    .background(Color.White)
                                    .padding(12.dp)
                            )
                        }
                        // URL 始终显示 (可点击打开)
                        qrContent?.let { url ->
                            Text(
                                text = stringResource(R.string.weixin_bot_qr_open_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { context.startActivity(intent) } catch (_: Exception) {}
                            }) { Text(stringResource(R.string.weixin_bot_open_qr_link)) }
                        }
                    }
                }
            }

            // 总开关
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.weixin_bot_section_run)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.weixin_bot_enable)) },
                        supportingContent = { Text(stringResource(R.string.weixin_bot_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = botSetting.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showEnableRiskDialog = true
                                    } else {
                                        update(botSetting.copy(enabled = false))
                                        WeixinBotService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                    if (botSetting.enabled && botSetting.botToken.isBlank()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.weixin_bot_not_logged_warning)) },
                            supportingContent = { Text(stringResource(R.string.weixin_bot_not_logged_warning_desc)) }
                        )
                    }
                }
            }
        }
    }
}

/** 用 ZXing 把字符串渲染成二维码 Bitmap. */
private fun renderQrCode(content: String, sizePx: Int): android.graphics.Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bmp = createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bmp[x, y] = if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bmp
}
