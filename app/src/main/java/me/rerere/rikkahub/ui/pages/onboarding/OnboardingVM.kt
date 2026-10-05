package me.rerere.rikkahub.ui.pages.onboarding

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

/**
 * 引导页的状态机。
 *
 * 设计上只做三件有副作用的事：
 *   1. 把选中的服务商写进设置（含 baseUrl，用户只需填 Key）
 *   2. 验证这个 Key 真的能用
 *   3. 恢复备份
 *
 * 其余全是导航。**引导不新增任何能力**，它只是把已有功能串起来。
 */
class OnboardingVM(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val backupVM: me.rerere.rikkahub.ui.pages.backup.BackupVM,
) : ViewModel() {

    private companion object {
        const val TAG = "Onboarding"
    }

    /** 连接测试的结果。每一种失败都要能告诉用户**下一步怎么办**。 */
    sealed interface TestResult {
        data object Idle : TestResult
        data object Testing : TestResult
        data class Ok(val modelCount: Int) : TestResult

        /**
         * 失败。
         *
         * [hint] 必须是可操作的建议，不能只说「失败了」——
         * 新人卡在这一步时，唯一能救他的就是一句「去检查 X」。
         */
        data class Failed(val message: String, val hint: String) : TestResult
    }

    private val _testResult = MutableStateFlow<TestResult>(TestResult.Idle)
    val testResult: StateFlow<TestResult> = _testResult.asStateFlow()

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    private val _restoreError = MutableStateFlow<String?>(null)
    val restoreError: StateFlow<String?> = _restoreError.asStateFlow()

    /**
     * 保存服务商并立即测试连接。
     *
     * 测试通过才算配好——用户以为配好了、一聊天报错就走，
     * 是这类应用最高频的流失点。
     */
    fun saveAndTest(candidate: ProviderSetting) {
        viewModelScope.launch {
            _testResult.value = TestResult.Testing

            // 先落盘：即使测试失败，用户填的地址和 Key 也保留着，
            // 他改一下重试即可，不用从头再填一遍。
            runCatching {
                settingsStore.update { settings ->
                    val existing = settings.providers.indexOfFirst { it.id == candidate.id }
                    if (existing >= 0) {
                        settings.copy(
                            providers = settings.providers.map {
                                if (it.id == candidate.id) candidate else it
                            }
                        )
                    } else {
                        settings.copy(providers = settings.providers + candidate)
                    }
                }
            }.onFailure {
                Log.e(TAG, "Failed to persist provider", it)
                _testResult.value = TestResult.Failed(
                    message = "保存失败了：${it.message ?: it::class.simpleName}",
                    hint = "这是本地存储的问题，不是你的配置错。重试一次；如果一直这样，" +
                        "可以点右下角先跳过，进去以后在设置里配。",
                )
                return@launch
            }

            val result = testConnection(candidate)
            _testResult.value = result

            // 测试通过 → 自动挑一个对话模型设成默认。
            //
            // 不再让用户手动去「左下角选模型」：那一步新人经常找不到，
            // 而先让他聊上一句的体验回报高得多。手动选模型降级成
            // 一句可选提示。
            if (result is TestResult.Ok) {
                autoSelectModel(candidate)
            }
        }
    }

    private suspend fun testConnection(candidate: ProviderSetting): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            providerManager.getProviderByType(candidate)
                .listModels(candidate)
                .toList()
        }.fold(
            onSuccess = { models ->
                if (models.isEmpty()) {
                    // 连上了但一个模型都没有——多半是 key 有效但没开通任何模型
                    TestResult.Failed(
                        message = "连接成功，但这个账号下一个模型都没有。",
                        hint = "去服务商后台看看是不是还没开通模型，或者余额为零。",
                    )
                } else {
                    TestResult.Ok(models.size)
                }
            },
            onFailure = { e ->
                val raw = (e.message ?: e::class.simpleName ?: "").lowercase()
                when {
                    raw.contains("401") || raw.contains("unauthorized") || raw.contains("invalid api key") ->
                        TestResult.Failed(
                            message = "Key 没通过验证。",
                            hint = "多半是复制时多带了空格，或者粘成了别的东西。回去重新复制一次完整的 Key 试试。",
                        )

                    raw.contains("404") || raw.contains("not found") ->
                        TestResult.Failed(
                            message = "接口地址不对。",
                            hint = "换一个服务商，或者去服务商官网确认接口地址。",
                        )

                    raw.contains("429") || raw.contains("quota") || raw.contains("insufficient") ->
                        TestResult.Failed(
                            message = "额度不够了。",
                            hint = "Key 本身是对的，但账号没余额。去服务商那边充值后重试。",
                        )

                    raw.contains("timeout") || raw.contains("unable to resolve host") ||
                        raw.contains("failed to connect") || raw.contains("socket") ->
                        TestResult.Failed(
                            message = "连不上服务器。",
                            hint = "检查网络；如果用了代理，确认它是通的。也可能是这家服务商此刻不稳定。",
                        )

                    else ->
                        TestResult.Failed(
                            message = e.message ?: "未知错误",
                            hint = "如果反复失败，先换一个服务商试试——不同服务商的可用性差别很大。",
                        )
                }
            },
        )
    }

    /**
     * 自动选一个默认对话模型。
     *
     * 选不到也不拦着用户——引导照常可以结束，进去以后自己挑。
     */
    private suspend fun autoSelectModel(candidate: ProviderSetting) {
        runCatching {
            val models = providerManager.getProviderByType(candidate).listModels(candidate).toList()
            // 优先挑名字里明显是对话模型的；挑不到就取第一个。
            // 这一步只影响「打开就能聊」的体验，挑得不理想用户可以自己换。
            val preferred = models.firstOrNull { m ->
                val id = m.modelId.lowercase()
                listOf("gpt", "claude", "gemini", "deepseek", "qwen", "glm", "kimi")
                    .any { id.contains(it) }
            } ?: models.firstOrNull() ?: return@runCatching

            settingsStore.update { settings ->
                val model = Model(
                    modelId = preferred.modelId,
                    displayName = preferred.displayName.ifBlank { preferred.modelId },
                )
                // 把模型挂到 provider 上，再设成当前助手和全局的默认模型
                val updatedProviders = settings.providers.map { p ->
                    if (p.id == candidate.id) p.addModel(model) else p
                }
                val assistant = settings.getCurrentAssistant()
                settings.copy(
                    providers = updatedProviders,
                    chatModelId = model.id,
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                    },
                )
            }
            Log.i(TAG, "Auto-selected model ${preferred.modelId}")
        }.onFailure { Log.w(TAG, "Auto model selection failed (non-fatal)", it) }
    }

    /** 恢复备份。成功后必须重启——见 OnboardingState 与 BackupManager 的说明。 */
    fun restoreBackup(context: Context, file: java.io.File, onDone: () -> Unit) {
        viewModelScope.launch {
            _restoring.value = true
            _restoreError.value = null
            runCatching {
                backupVM.restoreFromLocalFile(file)
            }.onSuccess {
                onDone()
            }.onFailure { e ->
                Log.e(TAG, "Restore failed", e)
                _restoreError.value = e.message ?: "恢复失败：${e::class.simpleName}"
            }
            _restoring.value = false
        }
    }
}
