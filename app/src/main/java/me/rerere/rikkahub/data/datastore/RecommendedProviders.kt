package me.rerere.rikkahub.data.datastore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.l10n
import kotlin.uuid.Uuid

/**
 * 推荐的提供商列表，引导页「选个提供商」与设置页右上角推荐 Sheet 共用。
 *
 * 置顶的砂糖酒馆是为酒馆而生的网关(ai.ota1245.top)。其余为各家官方接口，
 * 地址公开稳定。历史版本曾放带返利链接的中转站，已全部移除，测试防回归。
 *
 * 每条都标了门槛（要不要实名、有没有免费额度）。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("f3a8d2c4-5e91-4b7a-9c36-d80b2e4a1f57"),
        name = "砂糖酒馆",
        baseUrl = "https://ai.ota1245.top/v1",
        apiKey = "",
        enabled = true,
        description = { ShaTangDescription() },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("c601ef92-36db-fc8e-fe15-9f7a1ce5ddb6"),
        name = l10n("DeepSeek 深度求索", "DeepSeek"),
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("国产，价格很低，中文能力强。", "A domestic provider with very low prices and strong Chinese capability."))
                    appendLine()
                    append(l10n("门槛：需实名，需充值（无免费额度）", "Requirements: real-name verification and top-up (no free quota)"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://platform.deepseek.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("platform.deepseek.com")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("fcc9dd50-6893-65b1-f6e2-40949bc75396"),
        name = l10n("硅基流动 SiliconFlow", "SiliconFlow"),
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("国产聚合，有免费额度，新手上手成本最低的一家。", "A domestic aggregator with a free quota; the cheapest way for beginners to start."))
                    appendLine()
                    append(l10n("门槛：需实名，注册送额度", "Requirements: real-name verification; quota granted on sign-up"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://cloud.siliconflow.cn")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("cloud.siliconflow.cn")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("8d1ccb1d-73e1-5cd0-9cc8-d235109c5085"),
        name = l10n("月之暗面 Kimi", "Moonshot Kimi"),
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("长上下文见长，中文写作好。", "Strong long-context handling and Chinese writing."))
                    appendLine()
                    append(l10n("门槛：需实名，需充值", "Requirements: real-name verification and top-up"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://platform.moonshot.cn")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("platform.moonshot.cn")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("59487d36-832a-192b-f6dd-b7502c13da51"),
        name = l10n("智谱 AI 开放平台", "Zhipu AI"),
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("GLM 系列，有免费模型可用。", "GLM series with free models available."))
                    appendLine()
                    append(l10n("门槛：需实名，部分模型免费", "Requirements: real-name verification; some models are free"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://open.bigmodel.cn")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("open.bigmodel.cn")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("37ab5f4e-bce0-e6fb-7bae-9a58fcfe6ecf"),
        name = l10n("阿里云百炼", "Alibaba Cloud Bailian"),
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("通义千问系列，新用户有免费额度。", "Qwen series; new users get a free quota."))
                    appendLine()
                    append(l10n("门槛：需实名，注册送额度", "Requirements: real-name verification; quota granted on sign-up"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://bailian.console.aliyun.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("bailian.console.aliyun.com")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("672adb3a-b52a-a4b2-4a03-d7fb8efd9bd1"),
        name = l10n("火山引擎（豆包）", "Volcano Engine (Doubao)"),
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("豆包系列，推理速度和价格都不错。", "Doubao series with good inference speed and pricing."))
                    appendLine()
                    append(l10n("门槛：需实名，需在控制台创建推理接入点", "Requirements: real-name verification; create an inference endpoint in the console"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://console.volcengine.com/ark")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("console.volcengine.com/ark")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("2c62d24e-d0d9-7753-8ff6-f38315dc6cb8"),
        name = l10n("OpenAI 官方", "OpenAI"),
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("GPT 系列。", "GPT series."))
                    appendLine()
                    append(l10n("门槛：需科学上网，需绑海外支付方式", "Requirements: VPN access and an overseas payment method"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://platform.openai.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("platform.openai.com")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("2c0dbd02-1d01-e2fa-3a85-1c887291633e"),
        name = l10n("Anthropic Claude 官方", "Anthropic Claude"),
        baseUrl = "https://api.anthropic.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("Claude 系列，长文和角色扮演表现好。", "Claude series; strong at long-form writing and roleplay."))
                    appendLine()
                    append(l10n("门槛：需科学上网，需绑海外支付方式", "Requirements: VPN access and an overseas payment method"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://console.anthropic.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("console.anthropic.com")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("a12e5e12-0c5e-1ad4-f9d5-f0529e8320d6"),
        name = l10n("Google Gemini 官方", "Google Gemini"),
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("Gemini 系列，有免费额度。", "Gemini series with a free quota."))
                    appendLine()
                    append(l10n("门槛：需科学上网，有免费额度", "Requirements: VPN access; free quota available"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://aistudio.google.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("aistudio.google.com")
                        }
                    }
                }
            )
        },
    ),
)

// 砂糖酒馆的介绍：推荐 Sheet 与内置预设共用同一份，避免 normalization 覆盖后形态漂移
@Composable
internal fun ShaTangDescription() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShaTangTag("酒馆专用")
            ShaTangTag("Gemini")
        }
        Text(
            text = buildAnnotatedString {
                append(l10n("为酒馆而生的供应商。免实名，可用 Gemini，酒馆专用网关。", "A provider built for taverns. No real-name verification, Gemini available, tavern-only gateway."))
                appendLine()
                append(l10n("门槛：免实名，可用 Gemini", "Requirements: no real-name verification; Gemini available"))
                appendLine()
                append(l10n("官网：", "Website:"))
                withLink(LinkAnnotation.Url("https://ai.ota1245.top")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("ai.ota1245.top")
                    }
                }
            }
        )
    }
}

// 砂糖酒馆的标签小胶囊
@Composable
private fun ShaTangTag(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
