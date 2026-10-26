package me.rerere.rikkahub.data.datastore

import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.l10n
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("f3a8d2c4-5e91-4b7a-9c36-d80b2e4a1f57"),
        name = "砂糖酒馆",
        baseUrl = "https://ai.ota1245.top/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
        description = {
            MarkdownBlock(
                content = l10n(
                    "为酒馆而生的供应商。免实名，可用 Gemini，酒馆专用网关\n官网: [ai.ota1245.top](https://ai.ota1245.top)",
                    "A provider built for taverns. No real-name verification, Gemini available, tavern-only gateway.\nWebsite: [ai.ota1245.top](https://ai.ota1245.top)",
                )
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true
    ),
    ProviderSetting.Google(
        id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
        name = "Gemini",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
            ProviderSetting.OpenAI(
        id = Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"),
        name = l10n("硅基流动", "SiliconFlow"),
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        builtIn = true,
        description = {
            MarkdownBlock(
                content = """
                    ${stringResource(R.string.silicon_flow_description)}
                    ${stringResource(R.string.silicon_flow_website)}
                """.trimIndent()
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"),
        name = l10n("月之暗面", "Moonshot"),
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/users/me/balance",
            resultPath = "data.available_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"),
        name = l10n("阿里云百炼", "Alibaba Cloud Bailian"),
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"),
        name = l10n("火山引擎", "Volcano Engine"),
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"),
        name = l10n("智谱AI开放平台", "Zhipu AI"),
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"),
        name = l10n("阶跃星辰", "StepFun"),
        baseUrl = "https://api.stepfun.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"),
        name = l10n("腾讯Hunyuan", "Tencent Hunyuan"),
        baseUrl = "https://tokenhub.tencentmaas.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"),
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        useResponseApi = true,
    ),
            ProviderSetting.Claude(
        id = Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"),
        name = "MiniMax",
        baseUrl = "https://api.minimaxi.com/anthropic/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"),
        name = "MIMO",
        baseUrl = "https://api.xiaomimimo.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
    ),
)

// 已下架的非官方网关预设：存量里没填 Key 的条目会在规整时清掉
val REMOVED_PROVIDER_IDS = setOf(
    Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"),
    Uuid.parse("9c68d0bb-48c4-4e0a-8e2d-7a7e8d9f3f17"),
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"),
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"),
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"),
)

// 历史版本内置过的非官方网关域名：按 baseUrl 匹配清理存量空 Key 条目
val REMOVED_PROVIDER_BASES = setOf(
    "aihubmix.com",
    "sui-xiang.com",
    "muteki.site",
    "apimart.ai",
    "unifyllm.top",
    "ackai.fun",
    "juheai.top",
    "orcarouter.ai",
    "302.ai",
    "tokenpony.cn",
    "vercel.sh",
    "openrouter.ai",
)

// 砂糖酒馆的域名：供应商列表置顶
const val SHATANG_HOST = "ota1245"
