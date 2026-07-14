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
        val DEFAULT = DANGER_FULL_ACCESS
    }
}
