package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.l10n
import kotlin.uuid.Uuid

/**
 * 推荐的提供商列表。
 *
 * 之前在提供商设置页右上角的推荐 Sheet 中展示，现在也作为新手引导里
 * 「选个提供商」这一步的数据源。
 *
 * **这里只列官方服务商，不列中转网关。**
 *
 * 这个列表前后有两版：
 *   · 第一版全是带返利链接的中转站（aff=、go.xxx）。那些链接对项目
 *     有收益，但把一个「选谁家的模型」的决定替用户做了，而且是照着
 *     收益做的——不合适，已全部移除。
 *   · 现在这版是各家官方接口。地址是公开的、稳定的，不随谁的推广
 *     活动变化。
 *
 * 每条都标了**门槛**（要不要实名、有没有免费额度）。这不是推荐谁，
 * 只是把事实摆出来：新人最常见的死法是选了个要充值 + 实名的，
 * 卡在支付页就卸载了。
 *
 * 没在这里的也不影响使用——设置里可以自己填任意服务的地址和 Key。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
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
    ProviderSetting.OpenAI(
        id = Uuid.parse("7549f937-35d4-0056-3d35-5d42936da8cc"),
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append(l10n("一个 Key 调用各家主流模型，模型的覆盖面最广。", "One key for all major models, with the widest coverage."))
                    appendLine()
                    append(l10n("门槛：需科学上网，部分模型免费", "Requirements: VPN access; some models are free"))
                    appendLine()
                    append(l10n("官网：", "Website:"))
                    withLink(LinkAnnotation.Url("https://openrouter.ai")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("openrouter.ai")
                        }
                    }
                }
            )
        },
    ),
)
