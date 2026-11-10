package me.rerere.rikkahub.data.st.expressions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ExpressionClassifier {
    @SerialName("llm")
    LLM,

    @SerialName("none")
    NONE;

    companion object {
        /** 宽松解析（未知值回退 LLM，保证旧版本/坏数据可读） */
        fun parse(raw: String?): ExpressionClassifier = when (raw?.trim()?.lowercase()) {
            "none" -> NONE
            else -> LLM
        }
    }
}
