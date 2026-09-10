package me.rerere.rikkahub.ui.pages.setting

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ynufe.CredentialObfuscator
import me.rerere.rikkahub.data.ai.tools.ynufe.YnufeLoginHelper
import me.rerere.rikkahub.data.ai.tools.ynufe.YnufeStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 华灯设置：全局兼容 / 辅助功能 + 云财教务系统账号。
 */
@Composable
fun SettingHuaDengPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_huadeng)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 兼容与辅助 ──
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_huadeng_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_proxy_fix)) },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_proxy_fix_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableProxyFix,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableProxyFix = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_anti_empty_response)) },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_anti_empty_response_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableAntiEmptyResponse,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableAntiEmptyResponse = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("上下文瞬态内容裁剪") },
                        supportingContent = {
                            Text("超过两轮对话之前的网页搜索结果、图片、音视频不再随每次请求发送（占位说明附带消息 ID，AI 可通过 read_history_message 工具按需取回原文），大幅减少图片与搜索类长对话的 token 消耗；消息存储与聊天记录显示不受影响")
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.huadengSettings.enableTransientContentPrune,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            huadengSettings = settings.huadengSettings.copy(
                                                enableTransientContentPrune = enabled,
                                            ),
                                        )
                                    )
                                },
                            )
                        },
                    )
                }
            }

            // ── 云财教务系统账号 ──
            item {
                YnufeAccountSection()
            }
        }
    }
}

// ── 教务系统账号（从 YnufeAccountPage 嵌入）──

@Composable
private fun YnufeAccountSection() {
    val context = LocalContext.current
    val store = remember { YnufeStore(context) }
    val auth by store.authFlow.collectAsStateWithLifecycle(
        initialValue = me.rerere.rikkahub.data.ai.tools.ynufe.YnufeAuth()
    )
    val scope = rememberCoroutineScope()

    var account by remember(auth.account) { mutableStateOf(auth.account) }
    var password by remember(auth.passwordObf) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf(false) }

    var loginBusy by remember { mutableStateOf(false) }
    var loginMsg by remember { mutableStateOf("") }
    var captchaBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var captchaInput by remember { mutableStateOf("") }
    var showCaptchaPanel by remember { mutableStateOf(false) }

    fun JsonObject.bool(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.content == "true"

    suspend fun runLogin(manual: String?) {
        val obfuscator = CredentialObfuscator(context)
        val pwd = password.ifBlank { obfuscator.deobfuscate(auth.passwordObf) }
        if (account.isBlank() || pwd.isBlank()) {
            loginMsg = "请先填写学号和密码"
            return
        }
        loginBusy = true
        try {
            val helper = YnufeLoginHelper(context)
            val code: String
            if (manual != null) {
                code = manual.trim()
            } else {
                val bytes = helper.fetchCaptcha()
                val ocr = me.rerere.rikkahub.data.ai.tools.ynufe.YnufeCaptcha.recognize(bytes)
                code = ocr.text
                if (code.length != 4) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        captchaBitmap = it.asImageBitmap()
                    }
                    captchaInput = code
                    showCaptchaPanel = true
                    loginMsg = "验证码自动识别失败，请看图手动输入"
                    return
                }
            }
            val res = helper.login(account.trim(), pwd, code, saveCredentials = true)
            when {
                res.bool("logged_in") -> {
                    loginMsg = "登录成功 ✓ 凭据已保存，会话已建立"
                    showCaptchaPanel = false
                    captchaBitmap = null
                    password = ""
                    savedHint = true
                }
                res.bool("bad_credentials") -> {
                    loginMsg = "学号或密码有误，请核对后重试"
                    showCaptchaPanel = false
                }
                else -> {
                    val bytes = helper.fetchCaptcha()
                    val ocr = me.rerere.rikkahub.data.ai.tools.ynufe.YnufeCaptcha.recognize(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        captchaBitmap = it.asImageBitmap()
                    }
                    captchaInput = ocr.text
                    showCaptchaPanel = true
                    loginMsg = if (res.bool("bad_captcha")) "验证码不对，已换新图，输入框已预填识别结果，请改正后重试"
                    else res["message"]?.toString()?.trim('"') ?: "登录失败，请重试"
                }
            }
        } catch (e: Exception) {
            loginMsg = "登录失败: ${e.message}"
        } finally {
            loginBusy = false
        }
    }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.setting_page_ynufe)) },
    ) {
        // 学号
        item(
            headlineContent = {
                OutlinedTextField(
                    value = account,
                    onValueChange = {
                        account = it.trim()
                        savedHint = false
                    },
                    label = { Text("学号") },
                    placeholder = { Text("输入教务系统学号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        // 密码
        item(
            headlineContent = {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        savedHint = false
                    },
                    label = { Text(if (auth.hasCredentials) "密码（已保存，留空保持不变）" else "密码") },
                    placeholder = { Text("输入教务系统密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        // 登录
        item(
            headlineContent = {
                Text(
                    loginMsg.ifBlank { "自动识别验证码并登录一次，验证账号密码是否有效" },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        loginMsg.startsWith("登录成功") -> MaterialTheme.colorScheme.primary
                        loginMsg.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            },
            trailingContent = {
                if (loginBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    TextButton(
                        enabled = account.isNotBlank(),
                        onClick = { scope.launch { runLogin(null) } },
                    ) {
                        Text("登录")
                    }
                }
            },
        )
        // 保存
        item(
            headlineContent = {
                Text(
                    if (savedHint) "已保存 ✓ 会话有效期内直接查询"
                    else if (auth.hasCredentials) "已保存（${maskAccount(auth.account)}），修改后点保存"
                    else "保存后 AI 查询课表/成绩时自动登录",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (savedHint) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                TextButton(
                    enabled = account.isNotBlank() && (password.isNotBlank() || auth.hasCredentials),
                    onClick = {
                        scope.launch {
                            val obfuscator = CredentialObfuscator(context)
                            val pwdToSave = password.ifBlank {
                                obfuscator.deobfuscate(auth.passwordObf)
                            }
                            if (pwdToSave.isNotBlank()) {
                                store.saveCredentials(account, obfuscator.obfuscate(pwdToSave))
                                password = ""
                                savedHint = true
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
        )
        // 清除
        item(
            headlineContent = {
                Text(
                    "清除凭据",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            supportingContent = {
                Text(
                    "删除本机保存的学号密码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                TextButton(
                    enabled = auth.hasCredentials,
                    onClick = {
                        scope.launch {
                            store.clearAll()
                            account = ""
                            password = ""
                            savedHint = false
                            showCaptchaPanel = false
                            loginMsg = ""
                        }
                    },
                ) {
                    Text("清除")
                }
            },
        )
        // 会话状态
        item(
            headlineContent = {
                Text(
                    if (auth.jsessionid.isNotEmpty()) "会话：已登录" else "会话：未登录",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            supportingContent = {
                Text(
                    if (auth.lastLoginTs > 0L)
                        "最近登录：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(auth.lastLoginTs))}"
                    else "保存凭据后在对话里说「登录教务」即可",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    // 验证码面板
    if (showCaptchaPanel && captchaBitmap != null) {
        Card(
            modifier = Modifier.padding(horizontal = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("请输入验证码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                captchaBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "验证码图片",
                        modifier = Modifier.height(48.dp).padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = captchaInput,
                    onValueChange = { captchaInput = it.take(4) },
                    label = { Text("验证码（4 位，已预填识别结果）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !loginBusy && captchaInput.isNotBlank(),
                    onClick = { scope.launch { runLogin(captchaInput) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("用此验证码登录")
                }
            }
        }
    }

    // 说明文字
    Text(
        text = "凭据仅保存在本机（混淆存储），用于登录云南财经大学教务系统自动查询课表、成绩、考试安排。验证码由内置 OCR 自动识别，失败时手动输入。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private fun maskAccount(account: String): String = when {
    account.length <= 4 -> account
    else -> account.take(2) + "****" + account.takeLast(2)
}
