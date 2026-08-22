package com.monitorcheck.hardware.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.monitorcheck.core.Reading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class SensorInfo(
    val id: Int,
    val name: String,
    val vendor: String,
    val type: Int,
    val typeName: String,
    val version: Int,
    val resolution: Float,
    val maximumRange: Float,
    val power: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val isWakeUp: Boolean,
    val isDynamic: Boolean,
    val reportingMode: String,
    val stringType: String?,
    val maxEventCount: Int
) {

    val maxRateHz: Double? get() = if (minDelayUs > 0) 1_000_000.0 / minDelayUs else null
}

data class SensorReading(
    val values: FloatArray,
    val accuracy: Int,
    val timestampNanos: Long
) {
    override fun equals(other: Any?): Boolean =
        other is SensorReading && values.contentEquals(other.values) &&
            accuracy == other.accuracy && timestampNanos == other.timestampNanos

    override fun hashCode(): Int =
        values.contentHashCode() * 31 + accuracy * 31 + timestampNanos.hashCode()
}

class SensorRepository(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun allSensors(): Reading<List<SensorInfo>> {
        val sm = sensorManager ?: return Reading.unavailable("SensorManager unavailable")
        return try {
            val list = sm.getSensorList(Sensor.TYPE_ALL).map { it.toInfo() }
            if (list.isEmpty()) Reading.noHardware("Device reports no sensors")
            else Reading.available(list.sortedBy { it.typeName }, "SensorManager.getSensorList")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun defaultSensor(type: Int): Sensor? = try {
        sensorManager?.getDefaultSensor(type)
    } catch (_: Throwable) { null }

    fun sensorById(id: Int): Sensor? = try {
        sensorManager?.getSensorList(Sensor.TYPE_ALL)?.firstOrNull { it.hashCode() == id }
    } catch (_: Throwable) { null }

    fun observe(sensor: Sensor, samplingUs: Int = SensorManager.SENSOR_DELAY_UI): Flow<SensorReading> =
        callbackFlow {
            val sm = sensorManager
            if (sm == null) { close(); return@callbackFlow }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(SensorReading(event.values.copyOf(), event.accuracy, event.timestamp))
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {  }
            }
            val registered = try {
                sm.registerListener(listener, sensor, samplingUs)
            } catch (_: Throwable) { false }
            if (!registered) { close(); return@callbackFlow }
            awaitClose { runCatching { sm.unregisterListener(listener) } }
        }

    fun environmentSensors(): Map<String, Sensor?> = mapOf(
        "Ambient temperature" to defaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE),
        "Relative humidity" to defaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY),
        "Pressure" to defaultSensor(Sensor.TYPE_PRESSURE),
        "Light" to defaultSensor(Sensor.TYPE_LIGHT)
    )

    private fun Sensor.toInfo() = SensorInfo(
        id = hashCode(),
        name = name,
        vendor = vendor,
        type = type,
        typeName = typeLabel(type),
        version = version,
        resolution = resolution,
        maximumRange = maximumRange,
        power = power,
        minDelayUs = minDelay,
        maxDelayUs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) maxDelay else -1,
        isWakeUp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) isWakeUpSensor else false,
        isDynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isDynamicSensor else false,
        reportingMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            reportingModeLabel(reportingMode) else "Unknown",
        stringType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) stringType else null,
        maxEventCount = fifoMaxEventCount
    )

    companion object {
        fun accuracyLabel(v: Int) = when (v) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
            SensorManager.SENSOR_STATUS_NO_CONTACT -> "No contact"
            else -> "Unknown"
        }

        fun reportingModeLabel(v: Int) = when (v) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
            Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
            Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
            else -> "Unknown"
        }

        fun unitFor(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY -> "m/s²"
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
            Sensor.TYPE_LIGHT -> "lx"
            Sensor.TYPE_PRESSURE -> "hPa"
            Sensor.TYPE_PROXIMITY -> "cm"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
            Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> "steps"
            Sensor.TYPE_HEART_RATE -> "bpm"
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "unit vector"
            else -> ""
        }

        fun typeLabel(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic field"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_LIGHT -> "Light"
            Sensor.TYPE_PRESSURE -> "Pressure"
            Sensor.TYPE_PROXIMITY -> "Proximity"
            Sensor.TYPE_GRAVITY -> "Gravity"
            Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative humidity"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient temperature"
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic field (uncalibrated)"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope (uncalibrated)"
            Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
            Sensor.TYPE_STEP_DETECTOR -> "Step detector"
            Sensor.TYPE_STEP_COUNTER -> "Step counter"
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation vector"
            Sensor.TYPE_HEART_RATE -> "Heart rate"
            Sensor.TYPE_POSE_6DOF -> "Pose 6DOF"
            Sensor.TYPE_STATIONARY_DETECT -> "Stationary detect"
            Sensor.TYPE_MOTION_DETECT -> "Motion detect"
            Sensor.TYPE_HEART_BEAT -> "Heart beat"
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> "Off-body detect"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Accelerometer (uncalibrated)"
            else -> "Type $type"
        }
    }
}
