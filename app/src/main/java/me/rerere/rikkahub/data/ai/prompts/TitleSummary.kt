package me.rerere.rikkahub.data.ai.prompts

// 标题提示词改版的版本号。旧版提示词会把模型逼去逐字数字数，因此用户升级后
// 不能只在代码里改默认值——他们的 titlePrompt 已经落盘了。PreferencesStore 读
// 设置时拿这个版本号和盘里存的比，落后就替换成新默认值（只动从没改过的默认值，
// 用户手改过的提示词不会被覆盖）。
internal const val TITLE_PROMPT_REVISION = 2

// 标题长度上限。汉字按 1 算的话 10 字上下就够，这里留点余量给英文标题。
internal const val TITLE_MAX_CHARS = 14

internal val DEFAULT_TITLE_PROMPT = """
    Give this conversation a title of about {maxChars} characters or fewer.

    - Write it in {locale}
    - Plain text only: no punctuation, no quotes, no markdown
    - Name the subject of the conversation, not the fact that it happened
    - Reply with the title alone, on one line

    For example:
    猫粮推荐对比
    修复登录崩溃
    Rust 所有权疑问

    不要逐字数字数，直接给标题。

    <content>
    {content}
    </content>
""".trimIndent()
