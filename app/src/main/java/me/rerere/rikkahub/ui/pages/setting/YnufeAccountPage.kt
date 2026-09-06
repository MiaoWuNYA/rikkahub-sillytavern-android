package me.rerere.rikkahub.ui.pages.setting

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.tools.ynufe.CredentialObfuscator
import me.rerere.rikkahub.data.ai.tools.ynufe.YnufeLoginHelper
import me.rerere.rikkahub.data.ai.tools.ynufe.YnufeStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 教务系统账号设置页（云南财经大学 强智教务）。
 * 学号/密码保存后由 ynufe AI 工具自动复用，无需每次对话重新输入。
 * 「更新凭据并登录」：自动识别验证码登录一次；验证码不对时展示图片 + 预填识别值让用户改正。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YnufeAccountPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { YnufeStore(context) }
    val auth by store.authFlow.collectAsStateWithLifecycle(initialValue = me.rerere.rikkahub.data.ai.tools.ynufe.YnufeAuth())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var account by remember(auth.account) { mutableStateOf(auth.account) }
    var password by remember(auth.passwordObf) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf(false) }

    // 登录验证状态
    var loginBusy by remember { mutableStateOf(false) }
    var loginMsg by remember { mutableStateOf("") }
    var captchaBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var captchaInput by remember { mutableStateOf("") }
    var showCaptchaPanel by remember { mutableStateOf(false) }

    fun JsonObject.bool(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.content == "true"

    /** 登录尝试：manual==null 时自动 OCR；失败(验证码)则展示图片 + 预填识别值。 */
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
                    // 验证码不对 / 未知失败：拉一张新验证码，展示 + 预填自动识别值
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

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("教务系统账号 · YNUFE") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 账号密码输入
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("登录凭据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
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
                    Spacer(Modifier.height(8.dp))
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
                }
            }

            // 更新凭据并登录（自动识别验证码）
            CardGroup {
                item(
                    headlineContent = {
                        Text("更新凭据并登录", style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
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
            }

            // 验证码手动面板（自动识别失败 / 验证码不对时出现）
            if (showCaptchaPanel && captchaBitmap != null) {
                Card(
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
                                modifier = Modifier
                                    .height(48.dp)
                                    .padding(vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = captchaInput,
                            onValueChange = { captchaInput = it.take(4) },
                            label = { Text("验证码（4 位，已预填自动识别结果）") },
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

            // 保存 / 清除
            CardGroup {
                item(
                    headlineContent = {
                        Text("保存凭据", style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        Text(
                            if (savedHint) "已保存 ✓ 会话有效期内直接查询，无需重复登录"
                            else if (auth.hasCredentials) "已保存过凭据（学号 ${maskAccount(auth.account)}），修改后点击保存"
                            else "保存后 AI 查询课表/成绩时自动登录，无需每次输入",
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
                item(
                    headlineContent = {
                        Text("清除凭据", style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        Text(
                            "删除本机保存的学号密码（AI 将无法自动登录教务）",
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
            }

            // 登录状态
            CardGroup {
                item(
                    headlineContent = {
                        Text(
                            if (auth.jsessionid.isNotEmpty()) "会话状态：已登录（或缓存会话）" else "会话状态：未登录",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                    supportingContent = {
                        Text(
                            if (auth.lastLoginTs > 0L)
                                "最近登录：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(auth.lastLoginTs))}"
                            else "还没有登录过；保存凭据后在对话里说「登录教务」即可",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            Text(
                text = "凭据仅保存在本机（密码混淆存储），用于登录云南财经大学教务系统（xjwis.ynufe.edu.cn）自动查询课表、成绩、考试安排与教务公告。验证码由内置 OCR 自动识别，识别失败时会让用户手动输入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

private fun maskAccount(account: String): String = when {
    account.length <= 4 -> account
    else -> account.take(2) + "****" + account.takeLast(2)
}
