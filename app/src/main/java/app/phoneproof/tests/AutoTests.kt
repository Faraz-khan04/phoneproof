package app.phoneproof.tests

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import app.phoneproof.core.DecidedBy
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.TestResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume

/**
 * Tests that need no permission and no seller input. Each returns a [TestResult] for the
 * signed report plus a plain-language line for the screen.
 */
object AutoTests {

    data class Line(val result: TestResult, val detail: String)

    fun battery(context: Context): Line {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val tempDeci = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val cycles = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1 else -1

        val outcome = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> if (tempDeci in 0..450) TestOutcome.PASS else TestOutcome.WARN
            BatteryManager.BATTERY_HEALTH_COLD -> TestOutcome.WARN
            BatteryManager.BATTERY_HEALTH_UNKNOWN -> TestOutcome.UNSUPPORTED
            else -> TestOutcome.FAIL // overheat, dead, over-voltage, failure
        }
        val m = buildMap<String, Long> {
            if (pct >= 0) put("level", pct.toLong())
            if (tempDeci >= 0) put("temp_dc", tempDeci.toLong())
            if (cycles >= 0) put("cycles", cycles.toLong())
            put("health", health.toLong())
        }
        val detail = buildString {
            append(when (outcome) {
                TestOutcome.PASS -> "Health reported as good"
                TestOutcome.WARN -> if (health == BatteryManager.BATTERY_HEALTH_COLD) "Battery is cold" else "Running warm"
                TestOutcome.FAIL -> "Phone reports a battery fault"
                else -> "This phone doesn't report battery health"
            })
            if (cycles >= 0) append(" · $cycles cycles")
        }
        return Line(TestResult("battery.health", outcome, DecidedBy.AUTO, m), detail)
    }

    fun software(): Line {
        val patch = Build.VERSION.SECURITY_PATCH // "YYYY-MM-DD"
        val months = runCatching { ChronoUnit.MONTHS.between(LocalDate.parse(patch), LocalDate.now()) }.getOrNull()
        val outcome = if (months != null && months > 12) TestOutcome.WARN else TestOutcome.PASS
        val detail = "Android ${Build.VERSION.RELEASE} · patch $patch" + if (outcome == TestOutcome.WARN) " (over a year old)" else ""
        val m = buildMap<String, Long> {
            put("sdk", Build.VERSION.SDK_INT.toLong())
            if (months != null) put("patch_age_mo", months)
        }
        return Line(TestResult("info.os", outcome, DecidedBy.AUTO, m), detail)
    }

    suspend fun motionSensors(context: Context): Line {
        val sm = context.getSystemService(SensorManager::class.java)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelOk = accel != null && firstReading(sm, accel)
        val gyroOk = gyro != null && firstReading(sm, gyro)
        val (outcome, detail) = when {
            !accelOk -> TestOutcome.FAIL to "Accelerometer gave no reading"
            gyro == null -> TestOutcome.WARN to "Accelerometer works · no gyroscope on this model"
            !gyroOk -> TestOutcome.FAIL to "Gyroscope gave no reading"
            else -> TestOutcome.PASS to "Accelerometer and gyroscope respond"
        }
        return Line(TestResult("sensors.motion", outcome, DecidedBy.AUTO, mapOf("gyro" to if (gyro != null) 1L else 0L)), detail)
    }

    private suspend fun firstReading(sm: SensorManager, sensor: Sensor): Boolean =
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        } ?: false

    /** Heuristic only; the real integrity proof is the key attestation in the signature. */
    fun rootHeuristics(): Line {
        val paths = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk", "/system/app/Superuser.apk")
        val found = paths.count { runCatching { File(it).exists() }.getOrDefault(false) }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val outcome = if (found == 0 && !testKeys) TestOutcome.PASS else TestOutcome.WARN
        val detail = if (outcome == TestOutcome.PASS) "No root tools found" else "Signs of root or a test build"
        return Line(TestResult("integrity.root", outcome, DecidedBy.AUTO, mapOf("hits" to (found + if (testKeys) 1 else 0).toLong())), detail)
    }
}
