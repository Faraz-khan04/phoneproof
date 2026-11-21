package app.phoneproof.tests

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import androidx.annotation.RequiresPermission
import app.phoneproof.core.DecidedBy
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Hardware probes used by the wizard. Everything here is honest about what it measures. */
object DeviceProbes {

    // ---------- Radios: presence + switched on. They are NOT connection-tested. ----------

    fun radios(context: Context): List<AutoTests.Line> {
        val out = ArrayList<AutoTests.Line>()

        val nfc = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        out += when {
            nfc == null -> line("radio.nfc", TestOutcome.UNSUPPORTED, "No NFC on this model")
            nfc.isEnabled -> line("radio.nfc", TestOutcome.PASS, "Present and switched on")
            else -> line("radio.nfc", TestOutcome.WARN, "Present but switched off")
        }

        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        out += when {
            bt == null -> line("radio.bluetooth", TestOutcome.UNSUPPORTED, "No Bluetooth found")
            runCatching { bt.isEnabled }.getOrDefault(false) -> line("radio.bluetooth", TestOutcome.PASS, "Present and switched on")
            else -> line("radio.bluetooth", TestOutcome.WARN, "Present but switched off")
        }

        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        out += when {
            wifi == null -> line("radio.wifi", TestOutcome.UNSUPPORTED, "No Wi-Fi found")
            wifi.isWifiEnabled -> line("radio.wifi", TestOutcome.PASS, "Present and switched on")
            else -> line("radio.wifi", TestOutcome.WARN, "Present but switched off")
        }
        return out
    }

    private fun line(id: String, o: TestOutcome, d: String) = AutoTests.Line(TestResult(id, o, DecidedBy.AUTO), d)

    // ---------- Sensors as flows (for the interactive "cover / rotate" step) ----------

    fun sensorFlow(context: Context, type: Int): Flow<FloatArray>? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(type) ?: return null
        return callbackFlow {
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) { trySend(e.values.clone()) }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
            awaitClose { sm.unregisterListener(l) }
        }
    }

    fun proximityMaxRange(context: Context): Float =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.maximumRange ?: 5f

    fun hasSensor(context: Context, type: Int) =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(type) != null

    // ---------- Flash ----------

    fun torchCameraId(context: Context): String? {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        return runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
    }

    fun setTorch(context: Context, id: String, on: Boolean) {
        runCatching { context.getSystemService(CameraManager::class.java)?.setTorchMode(id, on) }
    }

    // ---------- Speaker → microphone loopback ----------

    data class Loopback(val snrDb: Int, val outcome: TestOutcome, val detail: String)

    /**
     * Plays a 1 kHz tone through the speaker and listens with the microphone.
     * Compares the tone's energy (Goertzel at 1 kHz) to the room noise measured just before.
     * PASS ≥ 15 dB above noise, WARN 8–15 dB, FAIL below 8 dB.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun speakerMicLoopback(): Loopback = withContext(Dispatchers.IO) {
        val rate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext Loopback(0, TestOutcome.UNSUPPORTED, "Microphone not available")
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext Loopback(0, TestOutcome.FAIL, "Microphone didn't start") }

        val toneSec = 1.2
        val n = (rate * toneSec).toInt()
        val pcm = ShortArray(n) { i -> (sin(2 * PI * 1000.0 * i / rate) * Short.MAX_VALUE * 0.8).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()

        try {
            rec.startRecording()
            val noise = readFor(rec, rate / 3)                  // ~0.33 s of room noise
            track.write(pcm, 0, n); track.play()
            Thread.sleep(150)                                   // let the speaker start
            val tone = readFor(rec, (rate * 0.8).toInt())
            val pNoise = goertzel(noise, rate, 1000.0) + 1e-9
            val pTone = goertzel(tone, rate, 1000.0) + 1e-9
            val snr = (10 * log10(pTone / pNoise)).toInt()
            val (o, d) = when {
                snr >= 15 -> TestOutcome.PASS to "Speaker and microphone both work"
                snr >= 8 -> TestOutcome.WARN to "Quieter than usual"
                else -> TestOutcome.FAIL to "Tone not picked up"
            }
            Loopback(snr, o, d)
        } finally {
            runCatching { track.stop() }; track.release()
            runCatching { rec.stop() }; rec.release()
        }
    }

    private fun readFor(rec: AudioRecord, samples: Int): ShortArray {
        val buf = ShortArray(samples)
        var off = 0
        while (off < samples) {
            val r = rec.read(buf, off, samples - off)
            if (r <= 0) break
            off += r
        }
        return buf
    }

    /** Signal power at [freq] (Goertzel), normalised by length. */
    private fun goertzel(x: ShortArray, rate: Int, freq: Double): Double {
        val k = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s = v + k * s1 - s2; s2 = s1; s1 = s }
        return (s1 * s1 + s2 * s2 - k * s1 * s2) / (x.size.toDouble() * x.size)
    }
}
