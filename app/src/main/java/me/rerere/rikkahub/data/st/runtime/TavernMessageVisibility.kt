package me.rerere.rikkahub.data.st.runtime

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation

// ST /hide 语义：宿主没有 data/model/MessageVisibility.kt，在本包复刻（标记随消息注解持久化）。

val UIMessage.isHidden: Boolean
    get() = annotations.any { it is UIMessageAnnotation.Hidden }

fun UIMessage.withHidden(): UIMessage =
    if (isHidden) this else copy(annotations = annotations + UIMessageAnnotation.Hidden)

fun UIMessage.withoutHidden(): UIMessage =
    if (!isHidden) this else copy(annotations = annotations.filterNot { it is UIMessageAnnotation.Hidden })
