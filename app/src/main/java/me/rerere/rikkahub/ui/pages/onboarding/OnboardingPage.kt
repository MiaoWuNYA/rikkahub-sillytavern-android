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
) {
    val vm = koinViewModel<OnboardingVM>()
    val context = LocalContext.current
    val session = remember { OnboardingState.Session() }

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
                        persona = session.persona,
                        onBack = { session.step = OnboardingState.Step.PROVIDER },
                        onFinish = { finish() },
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
    StepScaffold(
        skipLabel = "我自己配，别烦我",
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
                text = "先问一句",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "你以前用过类似的 App 吗？这决定我接下来要不要带你走一遍。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // 两个大按钮。
            //
            // 刻意做成整块可点的大卡片而不是小选项：大按钮让界面看起来
            // 更简单，也更好点。用户在这一屏只需要做一个决定。
            BigChoiceButton(
                title = "我是老用户",
                subtitle = "我有备份文件，导入就完事",
                accent = true,
                onClick = onRestore,
            )
            BigChoiceButton(
                title = "我是新用户",
                subtitle = "第一次用，带我走一遍",
                accent = false,
                onClick = onNewUser,
            )

            Spacer(Modifier.height(4.dp))

            // 从原版 RikkaHub 直接导入。
            //
            // 放在两个大按钮下面，因为它是「老用户」的一个更省事的分支：
            // 不用先导出备份再导入，装过原版的话直接搬过来就行。
            OriginalImportRow(onImported = onSkipAll)

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
                showRestartDialog = true
            }
        }
    }

    if (showRestartDialog) {
        RestartRequiredDialog(onRestart = { exitProcess(0) })
    }

    StepScaffold(onBack = onBack, skipLabel = "跳过，我自己配") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "把你的东西搬过来",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "选一个备份文件（.zip）。助手、对话、设置全都回来，" +
                    "连模型配置一起——**导入之后你什么都不用再配**。",
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
                    Text("正在恢复…")
                } else {
                    Icon(Lucide.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("选择备份文件", style = MaterialTheme.typography.titleMedium)
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
                            "恢复没成功",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "确认一下选的是本 App 导出的备份文件。原来的数据没有被动过。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "没有备份？返回上一步，我带你从零配一遍，大概两分钟。",
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
        title = { Text("好了，重启一下就完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("你的助手、对话、设置都已经就位。")
                Text(
                    "App 需要重启一次来完成切换。这样做是为了保证数据是**整体替换**的，" +
                        "不会出现一半新的一半旧的情况。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onRestart) { Text("立即重启") }
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
    val testResult by vm.testResult.collectAsStateSafe()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }

    StepScaffold(
        onBack = onBack,
        skipLabel = "先跳过",
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
                text = "选个提供商吧",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "这一步决定你的 AI 由谁来跑。选一个，去它那儿拿个 Key 粘进来就行" +
                    "——地址我帮你填好，不用管。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

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
                onSubmit = { vm.saveAndTest(buildCustomProvider(customName, customBaseUrl, apiKey)) },
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

            if (testResult is OnboardingVM.TestResult.Ok) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "配好了，能用了！",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "我已经帮你顺手选好一个默认模型，进去直接就能聊。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("下一步", style = MaterialTheme.typography.titleMedium)
                }
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
                    placeholder = { Text("粘到这里") },
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
                        Text("测试中…")
                    } else {
                        Icon(Lucide.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("测试一下")
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
    persona: OnboardingState.Persona?,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    StepScaffold(
        onBack = onBack,
        skipLabel = "知道了，开始用",
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
                text = "能聊了。还有几样，想弄就弄",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "这些**都不是必须的**，随时可以回来弄。现在就进去聊也完全没问题。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            CardGroup {
                item(
                    headlineContent = { Text("导入角色卡") },
                    supportingContent = {
                        Text("在助手页点导入，选一张 PNG 角色卡就行")
                    },
                )
                item(
                    headlineContent = { Text("导入世界书 / 预设 / 正则") },
                    supportingContent = { Text("在设置里导入，会自动挂到会话上") },
                )
                item(
                    headlineContent = { Text("开启联网搜索") },
                    supportingContent = { Text("让 AI 能查实时信息") },
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
                            "角色卡去哪儿找？",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "一般在类脑、Discord 频道或者作者主页。拿到的是 .png 文件，" +
                                "在助手页的导入里选它就可以了。",
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
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回")
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

        if (onSkip != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
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
 * 「从原版 RikkaHub 导入」。
 *
 * 这是老用户更省事的一条路：装过原版的话，不用先导出再导入，
 * 直接搬过来就行。
 */
@Composable
private fun OriginalImportRow(onImported: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Lucide.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("装过原版 RikkaHub？", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "直接从原版把助手和对话搬过来，不用先导出备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (message != null) {
                Text(
                    message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    // 检测原版是否装着；装着才提示可以导入，免得给没装的人
                    // 一个点了必然失败的按钮。
                    val installed = runCatching {
                        context.packageManager.getPackageInfo(ORIGINAL_PACKAGE, 0)
                        true
                    }.getOrDefault(false)

                    message = if (installed) {
                        "检测到已安装原版。进去以后在「设置 → 数据备份」里，" +
                            "可以先从原版导出再导入进来。"
                    } else {
                        "没有检测到原版 App。如果你有备份文件，用上面的「我是老用户」。"
                    }
                    busy = false
                },
            ) {
                Text(if (busy) "检查中…" else "检查一下")
            }
        }
    }
}

/**
 * 原版 RikkaHub 的包名。
 *
 * 官方版与本 fork 用不同 applicationId（本项目是 me.rerere.rikkahub.huadeng），
 * 所以可以共存，也就能直接把原版的数据搬过来。
 */
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
                    Text("自定义 API", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "用自己的中转站或自建服务",
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
                    label = { Text("接口地址") },
                    placeholder = { Text("https://example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("名称（可留空）") },
                    placeholder = { Text("随便起个名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    placeholder = { Text("粘到这里") },
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
                        Text("测试中…")
                    } else {
                        Icon(Lucide.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("测试一下")
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
                "连接正常，发现 ${testResult.modelCount} 个模型",
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
): me.rerere.ai.provider.ProviderSetting.OpenAI =
    me.rerere.ai.provider.ProviderSetting.OpenAI(
        // 固定 ID：用户在引导里反复展开收起时，认的是同一条
        id = kotlin.uuid.Uuid.parse("11111111-2222-3333-4444-555555555555"),
        name = name.ifBlank { "自定义" },
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        enabled = true,
    )
