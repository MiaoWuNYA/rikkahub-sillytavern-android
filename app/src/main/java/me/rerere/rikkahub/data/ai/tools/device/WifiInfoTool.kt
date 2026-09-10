package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** WiFi 连接信息（不要求 SSID 精确：被系统打码时只返回信号等通用信息） */
internal fun buildWifiInfoTool(context: Context): Tool = Tool(
    name = "get_wifi_info",
    description = "Get current WiFi connection info: SSID (may be redacted without location permission), IP address, signal strength, link speed and frequency.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("ConnectivityManager unavailable").toString()))
            val activeNetwork = cm.activeNetwork
            val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("connected", false)
                        put("message", "Not connected to WiFi")
                    }.toString()
                ))
            }

            val wm = context.getSystemService(WifiManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("WifiManager unavailable").toString()))
            val connectionInfo = wm.connectionInfo
            val result = buildJsonObject {
                put("success", true)
                put("connected", true)
                if (connectionInfo != null) {
                    val ssid = connectionInfo.ssid ?: ""
                    val bssid = connectionInfo.bssid ?: ""
                    val ssidRedacted = ssid == "<unknown ssid>" || bssid == "02:00:00:00:00:00"
                    put("ssid_redacted", ssidRedacted)
                    if (!ssidRedacted) {
                        put("ssid", ssid.removeSurrounding("\""))
                        put("bssid", bssid)
                    }
                    put("link_speed_mbps", connectionInfo.linkSpeed)
                    put("rssi_dbm", connectionInfo.rssi)
                    put("frequency_mhz", connectionInfo.frequency)
                }
                val lp = activeNetwork?.let { cm.getLinkProperties(it) }
                if (lp != null) {
                    lp.linkAddresses.firstOrNull()?.address?.hostAddress?.let { put("ip_address", it) }
                    put("interface_name", lp.interfaceName ?: "")
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
