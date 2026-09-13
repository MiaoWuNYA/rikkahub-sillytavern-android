package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Permission mode levels, ordered from least to most permissive.
 *
 * READ_ONLY < WORKSPACE_WRITE < DANGER_FULL_ACCESS
 *
 * Each tool declares its required permission level. The PolicyEngine
 * checks the current mode against the tool's requirement before execution.
 */
@Serializable
enum class PermissionMode {
    @SerialName("read_only")
    READ_ONLY,

    @SerialName("workspace_write")
    WORKSPACE_WRITE,

    @SerialName("danger_full_access")
    DANGER_FULL_ACCESS;

    companion object {
        // 默认最小权限：全开放只应在用户显式选择时出现，作为默认值是安全陷阱
        val DEFAULT = READ_ONLY
    }
}
