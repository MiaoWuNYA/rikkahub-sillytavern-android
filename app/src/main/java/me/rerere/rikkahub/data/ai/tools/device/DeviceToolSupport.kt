package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 构造统一格式的错误结果 JSON。
 * 设备工具失败时返回可读错误而不是抛异常，元工具会原样透传给模型。
 */
internal fun deviceError(message: String, vararg extra: Pair<String, String>): JsonObject =
    buildJsonObject {
        put("success", false)
        put("error", message)
        extra.forEach { (k, v) -> put(k, v) }
    }

/** 任一权限已授予即视为可用（目录展示与执行前检查用） */
internal fun hasAnyRuntimePermission(context: Context, permissions: List<String>): Boolean =
    permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
