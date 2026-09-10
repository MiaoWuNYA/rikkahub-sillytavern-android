package me.rerere.rikkahub.data.ai.tools.device

import android.Manifest
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun networkTypeToString(type: Int): String = when (type) {
    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
    TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
    TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_NR -> "NR"
    TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
    TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
    else -> "Unknown($type)"
}

private fun phoneTypeToString(type: Int): String = when (type) {
    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
    TelephonyManager.PHONE_TYPE_NONE -> "NONE"
    else -> "Unknown"
}

/**
 * SIM/运营商/网络制式信息。
 * 无 READ_PHONE_STATE 时降级：只返回运营商名称、国家等无需权限的字段。
 */
internal fun buildTelephonyInfoTool(context: Context): Tool = Tool(
    name = "get_telephony_info",
    description = "Get SIM card info, carrier name and network type. Degrades gracefully without READ_PHONE_STATE (returns carrier/country only).",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val hasPhoneStatePermission = hasAnyRuntimePermission(context, listOf(Manifest.permission.READ_PHONE_STATE))
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("TelephonyManager unavailable").toString()))

            val simOperator = try { tm.simOperator ?: "" } catch (_: Exception) { "" }
            val simOperatorName = try { tm.simOperatorName ?: "" } catch (_: Exception) { "" }
            val simCountryIso = try { tm.simCountryIso ?: "" } catch (_: Exception) { "" }
            val networkOperatorName = try { tm.networkOperatorName ?: "" } catch (_: Exception) { "" }
            val networkCountryIso = try { tm.networkCountryIso ?: "" } catch (_: Exception) { "" }
            val phoneType = try { tm.phoneType } catch (_: Exception) { TelephonyManager.PHONE_TYPE_NONE }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("sim_operator", simOperator)
                    put("sim_operator_name", simOperatorName)
                    put("sim_country", simCountryIso)
                    put("network_operator_name", networkOperatorName)
                    put("network_country", networkCountryIso)
                    put("phone_type", phoneTypeToString(phoneType))
                    if (hasPhoneStatePermission) {
                        // 这些字段需要 READ_PHONE_STATE
                        val simState = try { tm.simState } catch (_: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }
                        put("has_sim", simState == TelephonyManager.SIM_STATE_READY)
                        val networkType = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType
                            else @Suppress("DEPRECATION") tm.networkType
                        } catch (_: SecurityException) {
                            0
                        }
                        put("network_type", networkTypeToString(networkType))
                    } else {
                        put("degraded", true)
                        put("note", "READ_PHONE_STATE not granted; SIM state and network type are unavailable")
                    }
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
