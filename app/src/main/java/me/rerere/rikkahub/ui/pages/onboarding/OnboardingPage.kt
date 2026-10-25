package me.rerere.rikkahub.ui.pages.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Upload
import me.rerere.rikkahub.data.datastore.RECOMMENDED_PROVIDERS
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel
import kotlin.system.exitProcess
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Switch

/**
 * 首次引导。
 *
 * 三条原则：
 *   · **只把已有能力串成一条线**，不新增任何功能、不内置任何内容资源
 *   · **必做的只有一件事**（配好一个模型），其余全部可选且同屏展示
 *   · **每一步都可以是最后一步**，右下角永远有出口
 */
@Composable
fun OnboardingPage(
    onFinish: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val vm = koinViewModel<OnboardingVM>()
    val context = LocalContext.current
    // 步骤状态要顶得住「跳出去再回来」。
    //
    // Navigation3 在切到别的页面时会销毁引导页的组合。原来用
    // `remember { Session() }`，回来时它被重新构造，step 回到 WELCOME——
    // 用户从「能聊了」那屏点进扩展页看了一眼，回来又从第一屏开始。
    //
    // 用 rememberSaveable 把 step 与 persona 存进 saved state：
    // 页面被销毁后重建，这两个值会原样恢复，用户回到他离开的那一步。
    // 只能在可序列化的值上做，所以存的是枚举的名字而不是枚举本身。
    var stepName by rememberSaveable { mutableStateOf(OnboardingState.Step.WELCOME.name) }
    var personaName by rememberSaveable { mutableStateOf<String?>(null) }
    val session = remember {
        object {
            var step: OnboardingState.Step
                get() = runCatching { OnboardingState.Step.valueOf(stepName) }
                    .getOrDefault(OnboardingState.Step.WELCOME)
                set(value) { stepName = value.name }

            var persona: OnboardingState.Persona?
                get() = personaName?.let {
                    runCatching { OnboardingState.Persona.valueOf(it) }.getOrNull()
                }
                set(value) { personaName = value?.name }
        }
    }

    // 引导内部可以回退，但不允许退出——唯一的出口是右下角那个明确的按钮，
    // 免得用户误触返回键落到一个半配置的界面里。
    BackHandler(enabled = session.step != OnboardingState.Step.WELCOME) {
        session.step = when (session.step) {
            OnboardingState.Step.PROVIDER -> OnboardingState.Step.WELCOME
            OnboardingState.Step.EXTRAS -> OnboardingState.Step.PROVIDER
            else -> OnboardingState.Step.WELCOME
        }
    }

    fun finish() {
        OnboardingState.markCompleted(context)
        onFinish()
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = session.step,
                label = "onboarding-step",
            ) { step ->
                when (step) {
                    OnboardingState.Step.WELCOME -> WelcomeStep(
                        onNewUser = { session.step = OnboardingState.Step.PROVIDER },
                        onRestore = { session.step = OnboardingState.Step.RESTORE },
                        onSkipAll = { finish() },
                    )

                    OnboardingState.Step.RESTORE -> RestoreStep(
                        vm = vm,
                        onBack = { session.step = OnboardingState.Step.WELCOME },
                        onRestored = { finish() },
                    )

                    OnboardingState.Step.PROVIDER -> ProviderStep(
                        vm = vm,
                        onBack = { session.step = OnboardingState.Step.WELCOME },
                        onDone = { session.step = OnboardingState.Step.EXTRAS },
                    )

                    OnboardingState.Step.EXTRAS -> ExtrasStep(
                        vm = vm,
                        persona = session.persona,
                        onBack = { session.step = OnboardingState.Step.PROVIDER },
                        onFinish = { finish() },
                        // 跳出去之前先落标记：用户是带着明确目的去看那一页的，
                        // 回来时不该再被引导拦一次。想重看随时能从设置里进。
                        onNavigate = { screen ->
                            OnboardingState.markCompleted(context)
                            onNavigate(screen)
                        },
                    )
                }
            }
        }
    }
}

/* ─────────────────────────── 第一屏：你从哪儿来 ─────────────────────────── */

@Composable
private fun WelcomeStep(
    onNewUser: () -> Unit,
    onRestore: () -> Unit,
    onSkipAll: () -> Unit,
) {
    val context = LocalContext.current

    // 检测放在进入这一步时一次做完，用户直接看到结论。
    // 不在按钮回调里现查——那等于把已经知道的答案再问用户一次。
    val originalLaunchIntent = remember {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(ORIGINAL_PACKAGE)
        }.getOrNull()
    }

    StepScaffold(
        skipLabel = stringResource(R.string.onboarding_skip_configure),
        onSkip = onSkipAll,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // 两个大按钮。
            //
            // 刻意做成整块可点的大卡片而不是小选项：大按钮让界面看起来
            // 更简单，也更好点。用户在这一屏只需要做一个决定。
            BigChoiceButton(
                title = stringResource(R.string.onboarding_old_user_title),
                subtitle = stringResource(R.string.onboarding_old_user_subtitle),
                accent = true,
                onClick = onRestore,
            )
            BigChoiceButton(
                title = stringResource(R.string.onboarding_new_user_title),
                subtitle = stringResource(R.string.onboarding_new_user_subtitle),
                accent = false,
                onClick = onNewUser,
            )

            Spacer(Modifier.height(4.dp))

            // 从原版 RikkaHub 直接导入。
            //
            // 放在两个大按钮下面，因为它是「老用户」的一个更省事的分支：
            // 不用先导出备份再导入，装过原版的话直接搬过来就行。
            OriginalImportRow(
                installed = originalLaunchIntent != null,
                onLaunchOriginal = originalLaunchIntent?.let { intent ->
                    {
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                // 跳不过去就退回给用户一条能走的路，不要静默失败
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.onboarding_open_original_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                },
                onUseBackup = onRestore,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ─────────────────────────── 恢复备份 ─────────────────────────── */

@Composable
private fun RestoreStep(
    vm: OnboardingVM,
    onBack: () -> Unit,
    onRestored: () -> Unit,
) {
    val context = LocalContext.current
    val restoring by vm.restoring.collectAsStateSafe()
    val error by vm.restoreError.collectAsStateSafe()
    var showRestartDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val temp = java.io.File(context.cacheDir, "onboarding_restore.zip")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
        }.onSuccess {
            vm.restoreBackup(context, temp) {
                temp.delete()
                // **恢复成功就等于引导完成。**
                //
                // 这里必须落标记，不能只弹重启框：备份恢复走 PendingRestore，
                // 文件在下次启动、数据库和设置初始化**之前**才原子替换。
                // 也就是说重启那一刻，settings 里还没有 provider，
                // `hasUsableModel` 判不出来——只靠它的话用户会被再弹一次
                // 引导，而他明明刚导入了完整备份。
                //
                // 用户从备份来 = 他不需要引导，这是确定的，直接记下来。
                OnboardingState.markCompleted(context)
                showRestartDialog = true
            }
        }
    }

    if (showRestartDialog) {
        RestartRequiredDialog(onRestart = { exitProcess(0) })
    }

    StepScaffold(onBack = onBack, skipLabel = stringResource(R.string.onboarding_restore_skip)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.onboarding_restore_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_restore_desc_prefix) +
                    // 这里用 AnnotatedString 而不是 Markdown：
                    // 引导页的 Text 不解析 Markdown，写 ** 只会原样显示成星号。
                    buildAnnotatedString {
                        append(stringResource(R.string.onboarding_restore_desc_emphasis))
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(stringResource(R.string.onboarding_restore_desc_emphasis_2))
                        }
                        append(stringResource(R.string.onboarding_restore_desc_suffix))
                    },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = restoring != true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (restoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.onboarding_restoring))
                } else {
                    Icon(Lucide.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.onboarding_choose_backup_file), style = MaterialTheme.typography.titleMedium)
                }
            }

            val err = error
            if (err != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.onboarding_restore_failed),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.onboarding_restore_failed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_restore_no_backup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 恢复完成后的重启提示。
 *
 * **这一步不能省。** 恢复走的是 PendingRestore：它把文件暂存起来，
 * 在下次 App 启动、数据库初始化**之前**原子替换。所以恢复完成时
 * 数据还没真正生效，必须重启。不说清楚的话，用户会以为卡住了。
 */
@Composable
private fun RestartRequiredDialog(onRestart: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.onboarding_restart_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_restart_body))
                Text(
                    buildAnnotatedString {
                        append(stringResource(R.string.onboarding_restart_body_prefix))
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(stringResource(R.string.onboarding_restart_body_emphasis))
                        }
                        append(stringResource(R.string.onboarding_restart_body_suffix))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onRestart) { Text(stringResource(R.string.onboarding_restart_now)) }
        },
    )
}

/* ─────────────────────────── 配模型 ─────────────────────────── */

@Composable
private fun ProviderStep(
    vm: OnboardingVM,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val testResult by vm.testResult.collectAsStateSafe()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }

    StepScaffold(
        onBack = onBack,
        skipLabel = stringResource(R.string.onboarding_provider_skip),
        onSkip = onDone,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.onboarding_provider_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_provider_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // 「配好了能用了」+ 下一步 放在**列表上面**。
            //
            // 原来放在整页最底部：测试成功之后，用户视线在配置卡片上，
            // 而结论和下一步在几十行之外，得往下翻一屏才看得到。
            // 成功信息必须在发生的地方出现。
            if (testResult is OnboardingVM.TestResult.Ok) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.onboarding_provider_ok_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            stringResource(R.string.onboarding_provider_ok_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.onboarding_next), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
            }

            // 「自定义」排在第一位。
            //
            // 用开源客户端的人多半已经有自己的中转站或自建服务，
            // 对他们来说这份列表一个都用不上——把自定义塞在最下面
            // 等于逼他先翻完一遍别人的列表。第一位才是它该在的地方。
            CustomProviderCard(
                expanded = expandedId == CUSTOM_ID,
                name = customName,
                baseUrl = customBaseUrl,
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                onNameChange = { customName = it },
                onBaseUrlChange = { customBaseUrl = it },
                testResult = if (expandedId == CUSTOM_ID) testResult
                else OnboardingVM.TestResult.Idle,
                onToggle = {
                    expandedId = if (expandedId == CUSTOM_ID) null else CUSTOM_ID
                    apiKey = ""
                },
                onSubmit = { vm.saveAndTest(buildCustomProvider(customName, customBaseUrl, apiKey, context.getString(R.string.onboarding_custom_default_name))) },
            )

            if (expandedId != null && expandedId != CUSTOM_ID) {
                Spacer(Modifier.height(2.dp))
            }

            // 官方服务商，按「有免费额度」优先排序，方便新人先试。
            RECOMMENDED_PROVIDERS.forEach { provider ->
                val pid = provider.id.toString()
                val isOpen = expandedId == pid
                ProviderCard(
                    name = provider.name,
                    baseUrl = (provider as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.baseUrl,
                    expanded = isOpen,
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    testResult = if (isOpen) testResult else OnboardingVM.TestResult.Idle,
                    onToggle = {
                        expandedId = if (isOpen) null else pid
                        apiKey = ""
                    },
                    onSubmit = {
                        val openai = provider as? me.rerere.ai.provider.ProviderSetting.OpenAI
                        if (openai != null) {
                            vm.saveAndTest(openai.copy(apiKey = apiKey.trim(), enabled = true))
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    baseUrl: String?,
    expanded: Boolean,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    testResult: OnboardingVM.TestResult,
    onToggle: () -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (baseUrl != null) {
                        Text(
                            baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Lucide.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    placeholder = { Text(stringResource(R.string.onboarding_paste_here)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = onSubmit,
                    enabled = apiKey.isNotBlank() && testResult !is OnboardingVM.TestResult.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testResult is OnboardingVM.TestResult.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_testing))
                    } else {
                        Icon(Lucide.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_test))
                    }
                }

                TestResultBlock(testResult)
            }
        }
    }
}

/* ─────────────────────────── 可选那几样 ─────────────────────────── */

@Composable
private fun ExtrasStep(
    vm: OnboardingVM,
    persona: OnboardingState.Persona?,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    StepScaffold(
        onBack = onBack,
        skipLabel = stringResource(R.string.onboarding_extras_skip),
        onSkip = onFinish,
        skipHighlighted = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.onboarding_extras_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildAnnotatedString {
                    append(stringResource(R.string.onboarding_extras_desc_prefix))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(stringResource(R.string.onboarding_extras_desc_emphasis))
                    }
                    append(stringResource(R.string.onboarding_extras_desc_suffix))
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // 酒馆模式一键开关，放在最上面。
            //
            // 角色扮演用户几乎一定要开它（大幅简化提示词、减少被安全策略
            // 拦下的概率），但多数人根本不知道设置里有这个选项。放在引导的
            // 最后一步、一眼能看到的位置，省掉「用了一阵才发现」的过程。
            var tavernMode by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                tavernMode = vm.isTavernModeEnabled()
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.onboarding_tavern_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            stringResource(R.string.onboarding_tavern_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Switch(
                        checked = tavernMode,
                        onCheckedChange = {
                            tavernMode = it
                            vm.setTavernMode(it)
                        },
                    )
                }
            }

            // 三项都**可以点进去**。
            //
            // 原来只有文字说明，用户看完得自己去找入口——而「去哪儿找」
            // 恰恰是新人最卡的地方。这里直接把入口接上，说到哪就能点到哪。
            CardGroup {
                item(
                    onClick = { onNavigate(Screen.Assistant) },
                    headlineContent = { Text(stringResource(R.string.onboarding_import_card_title)) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.onboarding_import_card_desc)
                        )
                    },
                )
                item(
                    onClick = { onNavigate(Screen.Extensions) },
                    headlineContent = { Text(stringResource(R.string.onboarding_import_ext_title)) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.onboarding_import_ext_desc)
                        )
                    },
                )
                item(
                    onClick = { onNavigate(Screen.SettingSearch) },
                    headlineContent = { Text(stringResource(R.string.onboarding_enable_search_title)) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.onboarding_enable_search_desc)
                        )
                    },
                )
            }

            // 角色扮演用户额外说一句「卡从哪来」。
            // 新人找得到导入按钮，但不知道该去哪儿下卡——这才是真正的死穴。
            if (persona == OnboardingState.Persona.ROLEPLAY ||
                persona == OnboardingState.Persona.BOTH
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.onboarding_card_source_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            stringResource(R.string.onboarding_card_source_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // 这里**不再放一个「开始用」大按钮**。
            //
            // 右下角已经有一个「知道了，开始用」了，同一屏出现两个
            // 意思相同、位置不同的出口，用户会犹豫该点哪个。
            // 出口留一个就够，且位置要固定——用户学过一次就记住了。
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ─────────────────────────── 公共外壳 ─────────────────────────── */

/**
 * 每一步共用的外壳：可滚动内容 + 固定的底部出口。
 *
 * 右下角的出口**永远存在**。用户配好模型随时能走，不会被扣在引导里
 * 学完导入角色卡。卡着人学完的引导只会被卸载。
 */
@Composable
private fun StepScaffold(
    onBack: (() -> Unit)? = null,
    skipLabel: String,
    onSkip: (() -> Unit)? = null,
    skipHighlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 出口固定在**顶部**。
        //
        // 原来放在底部：用户配好 Key、点了「测试一下」看到成功，
        // 还得往下翻一屏才能找到那个「下一步」——而配置区展开后
        // 页面本身就是长的，那个按钮经常正好在屏幕外。
        //
        // 顶部是视线自然停留的位置，且配置过程中会一直可见，
        // 不需要滚动就能点。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
            }
            Spacer(Modifier.weight(1f))
            if (onSkip != null) {
                if (skipHighlighted) {
                    Button(onClick = onSkip) { Text(skipLabel) }
                } else {
                    TextButton(onClick = onSkip) {
                        Text(
                            skipLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp)
        ) {
            content()
        }
    }
}

/** 整块可点的大按钮。首屏只有两个，用户只需要做一个决定。 */
@Composable
private fun BigChoiceButton(
    title: String,
    subtitle: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (accent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Lucide.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「装过原版 RikkaHub？」这一行。
 *
 * **检测在进入引导时就已经做完了**，用户看到的是结论，不是一个让他
 * 自己点的「检查一下」按钮——把已经知道的答案再问一遍用户，是纯粹的
 * 浪费他一次点击。
 *
 * 关于「能不能直接读原版的数据库」：做不到。Android 的沙箱不允许一个
 * 应用访问另一个应用的私有目录（/data/data/<其他包名>/），除非设备
 * 已 root。所以我们能给的最短路径是：**直接把用户送到原版的备份页**，
 * 他在那儿导出一次，回来选文件即可——全程只需点三下，而引导这边
 * 已经备好了「我是老用户」那条路。
 *
 * 不假装能自动导入：说「已自动导入」而实际没有，比不做更糟。
 */
@Composable
private fun OriginalImportRow(
    installed: Boolean,
    onLaunchOriginal: (() -> Unit)?,
    onUseBackup: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (installed) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (installed) Lucide.Check else Lucide.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (installed) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (installed) stringResource(R.string.onboarding_original_detected) else stringResource(R.string.onboarding_original_not_detected),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (installed) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (installed) {
                Text(
                    // 说清两步，别让用户在两个 App 之间来回猜
                    stringResource(R.string.onboarding_original_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onLaunchOriginal != null) {
                        Button(onClick = onLaunchOriginal) { Text(stringResource(R.string.onboarding_open_original_export)) }
                    }
                    TextButton(onClick = onUseBackup) { Text(stringResource(R.string.onboarding_already_have_backup)) }
                }
            } else {
                Text(
                    stringResource(R.string.onboarding_no_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val ORIGINAL_PACKAGE = "me.rerere.rikkahub"

/* 小工具：把 StateFlow 收成 Compose 状态，省掉每个调用点重复写扩展 import */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe() =
    collectAsStateWithLifecycle()

/**
 * 自定义提供商的固定 ID。
 *
 * 用固定值而不是 Uuid.random()：用户在引导里点开又收起、改了几个字，
 * 每次都要认得是同一条，随机 ID 会让它变成一个新建的提供商。
 */
private const val CUSTOM_ID = "custom-onboarding"

/**
 * 「自定义」卡片。
 *
 * 比官方商多两个输入框：名称和接口地址。地址留空时给一个 OpenAI 的
 * 默认值作占位提示，用户看得懂该填什么格式。
 */
@Composable
private fun CustomProviderCard(
    expanded: Boolean,
    name: String,
    baseUrl: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    testResult: OnboardingVM.TestResult,
    onToggle: () -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_custom_api), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.onboarding_custom_api_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Lucide.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.onboarding_base_url)) },
                    placeholder = { Text("https://example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.onboarding_name_optional)) },
                    placeholder = { Text(stringResource(R.string.onboarding_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    placeholder = { Text(stringResource(R.string.onboarding_paste_here)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = onSubmit,
                    enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() &&
                        testResult !is OnboardingVM.TestResult.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testResult is OnboardingVM.TestResult.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_testing))
                    } else {
                        Icon(Lucide.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_test))
                    }
                }

                TestResultBlock(testResult)
            }
        }
    }
}

/** 连接测试结果的展示。抽出来是因为自定义和官方两张卡都要用。 */
@Composable
private fun TestResultBlock(testResult: OnboardingVM.TestResult) {
    when (testResult) {
        is OnboardingVM.TestResult.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    testResult.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    testResult.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        is OnboardingVM.TestResult.Ok -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.onboarding_connection_ok_models, testResult.modelCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        else -> Unit
    }
}

/** 把自定义那三个格子拼成一个 provider。 */
private fun buildCustomProvider(
    name: String,
    baseUrl: String,
    apiKey: String,
    defaultName: String,
): me.rerere.ai.provider.ProviderSetting.OpenAI =
    me.rerere.ai.provider.ProviderSetting.OpenAI(
        // 固定 ID：用户在引导里反复展开收起时，认的是同一条
        id = kotlin.uuid.Uuid.parse("11111111-2222-3333-4444-555555555555"),
        name = name.ifBlank { defaultName },
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        enabled = true,
    )
