package me.rerere.rikkahub.data.ai.tools.device

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale

/**
 * 定位（LocationManager.getLastKnownLocation + Geocoder 逆地理）。
 * 无定位权限时返回明确错误，引导用户去系统设置开权限。
 */
internal fun buildLocationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = "Get the device's last known location (latitude, longitude, accuracy) with optional reverse-geocoded address. Requires location permission; if not granted, an error with instructions is returned.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("include_address", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to include reverse-geocoded address info (default true)")
                })
            }
        )
    },
    execute = { args ->
        val includeAddress = args.jsonObject["include_address"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val locationPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (!hasAnyRuntimePermission(context, locationPermissions)) {
            return@Tool listOf(UIMessagePart.Text(deviceError(
                "Location permission not granted",
                "needs_permission" to "ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION",
                "hint" to "Grant Location permission in system Settings > Apps > Permissions > Location",
            ).toString()))
        }
        try {
            val lm = context.getSystemService(LocationManager::class.java)
                ?: return@Tool listOf(UIMessagePart.Text(deviceError("LocationManager unavailable").toString()))
            val providersEnabled = try {
                lm.getProviders(true)
            } catch (_: Exception) {
                emptyList()
            }
            val loc = providersEnabled.firstNotNullOfOrNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
            if (loc == null) {
                return@Tool listOf(UIMessagePart.Text(deviceError(
                    "No last known location available",
                    "hint" to "Open a maps app once to seed the location cache, then retry",
                ).toString()))
            }
            val ageMs = System.currentTimeMillis() - loc.time
            val result = buildJsonObject {
                put("success", true)
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("accuracy_m", loc.accuracy.toDouble())
                put("provider", loc.provider ?: "")
                put("timestamp_ms", loc.time)
                put("age_ms", ageMs)
                put("cached", true)
                if (includeAddress) {
                    try {
                        val addresses = Geocoder(context, Locale.getDefault())
                            .getFromLocation(loc.latitude, loc.longitude, 1)
                        val addr = addresses?.firstOrNull()
                        if (addr != null) {
                            val lines = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }
                            put("address", lines.joinToString(", ").ifBlank { addr.featureName ?: "" })
                            put("country", addr.countryName ?: "")
                            put("admin_area", addr.adminArea ?: "")
                            put("locality", addr.locality ?: "")
                        } else {
                            put("address", "Unknown address")
                        }
                    } catch (e: Exception) {
                        put("address", "Unknown address (geocoder failed: ${e.message})")
                    }
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(deviceError(e.message ?: "Unknown error").toString()))
        }
    }
)
