package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val FRIENDLY_TO_TYPE: Map<String, Int> = mapOf(
    "accelerometer" to Sensor.TYPE_ACCELEROMETER,
    "gyroscope" to Sensor.TYPE_GYROSCOPE,
    "light" to Sensor.TYPE_LIGHT,
    "proximity" to Sensor.TYPE_PROXIMITY,
    "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
    "pressure" to Sensor.TYPE_PRESSURE,
    "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
    "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
    "step_counter" to Sensor.TYPE_STEP_COUNTER,
    "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
    "gravity" to Sensor.TYPE_GRAVITY,
    "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
)

private val TYPE_TO_FRIENDLY: Map<Int, String> = FRIENDLY_TO_TYPE.entries.associate { it.value to it.key }

private val UNIT_BY_FRIENDLY: Map<String, String> = mapOf(
    "accelerometer" to "m/s^2",
    "gravity" to "m/s^2",
    "linear_acceleration" to "m/s^2",
    "gyroscope" to "rad/s",
    "magnetic_field" to "uT",
    "light" to "lx",
    "proximity" to "cm",
    "pressure" to "hPa",
    "temperature" to "°C",
    "humidity" to "%",
)

/** 列出设备全部传感器 */
internal fun buildListSensorsTool(context: Context): Tool = Tool(
    name = "list_sensors",
    description = "List all available sensors on the device, including their type, vendor, and operating range.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val sm = context.getSystemService(SensorManager::class.java)
        val payload = if (sm == null) {
            deviceError("SensorManager unavailable")
        } else {
            buildJsonObject {
                put("success", true)
                put("sensors", buildJsonArray {
                    sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
                        addJsonObject {
                            put("name", s.name)
                            put("type", TYPE_TO_FRIENDLY[s.type] ?: s.stringType ?: "type_${s.type}")
                            put("vendor", s.vendor)
                            put("max_range", s.maximumRange)
                            put("resolution", s.resolution)
                        }
                    }
                })
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 读取单个传感器在采样窗口内的平均值 */
internal fun buildReadSensorTool(context: Context): Tool = Tool(
    name = "read_sensor",
    description = "Read a single averaged sample from a named device sensor, e.g. accelerometer, gyroscope, light, proximity.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Sensor type, e.g. \"accelerometer\", \"gyroscope\", \"light\", \"proximity\", \"pressure\"")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional sample window in ms, default 200, max 5000")
                })
            },
            required = listOf("type")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val typeName = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text(deviceError("type is required").toString()))
        val durationMs = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 5000)
        val typeInt = FRIENDLY_TO_TYPE[typeName]
        val payload = if (typeInt == null) {
            deviceError("unknown sensor type: $typeName (use list_sensors to see available types)")
        } else {
            val sm = context.getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(typeInt)
            if (sm == null || sensor == null) {
                deviceError("sensor '$typeName' is unavailable on this device")
            } else {
                val lock = Any()
                val sums = mutableListOf<Double>()
                var count = 0
                var lastTimestamp = 0L
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        synchronized(lock) {
                            if (sums.isEmpty()) repeat(event.values.size) { sums.add(0.0) }
                            for (i in event.values.indices) {
                                if (i < sums.size) sums[i] = sums[i] + event.values[i]
                            }
                            count++
                            lastTimestamp = System.currentTimeMillis()
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                try {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    delay(durationMs.toLong())
                } finally {
                    sm.unregisterListener(listener)
                }
                val (resultValues, resultCount, resultTimestamp) = synchronized(lock) {
                    Triple(sums.toList(), count, lastTimestamp)
                }
                if (resultCount == 0) {
                    deviceError("no sensor samples received within ${durationMs}ms")
                } else {
                    buildJsonObject {
                        put("success", true)
                        put("type", typeName)
                        put("values", buildJsonArray { resultValues.forEach { add(it / resultCount) } })
                        UNIT_BY_FRIENDLY[typeName]?.let { put("unit", it) }
                        put("timestamp_ms", if (resultTimestamp != 0L) resultTimestamp else System.currentTimeMillis())
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
