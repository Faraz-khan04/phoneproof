package app.phoneproof.tests

import app.phoneproof.core.DecidedBy
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.TestResult

/** Display names and plain-language "who decided" lines for every test id. */
object TestCatalog {
    val names = mapOf(
        "battery.health" to "Battery health",
        "info.os" to "Software",
        "sensors.motion" to "Motion sensors",
        "integrity.root" to "Root check",
        "display.touch" to "Touchscreen",
        "display.pixels" to "Dead pixels",
        "vibration" to "Vibration",
        "audio.speaker" to "Speaker",
        "buttons.volume" to "Volume buttons",
        "sensors.proximity" to "Proximity sensor",
        "sensors.light" to "Light sensor",
        "sensors.compass" to "Compass",
        "radio.nfc" to "NFC",
        "radio.bluetooth" to "Bluetooth",
        "radio.wifi" to "Wi-Fi",
        "audio.mic" to "Microphone",
        "camera.rear" to "Rear camera",
        "camera.front" to "Front camera",
        "camera.flash" to "Flash",
        "bio.unlock" to "Fingerprint or face",
    )

    /** Tests whose failure means the phone isn't in good working order (drives the grade). */
    val core = setOf("battery.health", "display.touch", "audio.speaker", "audio.mic", "camera.rear", "camera.front", "buttons.volume")

    fun name(id: String) = names[id] ?: id

    /** Source line shown under the test name. [autoDetail] is the measured detail, if any. */
    fun source(r: TestResult, autoDetail: String?): String = when {
        r.outcome == TestOutcome.SKIPPED -> autoDetail ?: "Skipped by the seller"
        r.outcome == TestOutcome.UNSUPPORTED -> autoDetail ?: "Not available on this phone"
        r.id == "display.touch" -> {
            val missed = r.measurements["missed"] ?: 0
            if (missed == 0L) "Measured · all ${r.measurements["cells"] ?: 240} squares" else "Measured · $missed squares didn't respond"
        }
        r.by == DecidedBy.SELLER -> if (r.outcome == TestOutcome.PASS) "Confirmed by the seller" else "Problem reported by the seller"
        autoDetail != null -> autoDetail
        else -> "Measured by the phone"
    }

    /**
     * Trailing value for a row. Charge level is NOT battery health, so it is never shown here;
     * a real state-of-health % needs Android 14+ APIs most phones don't expose to apps.
     */
    fun value(r: TestResult): String? = when (r.id) {
        "display.touch" -> r.measurements["cells"]?.let { c -> "${c - (r.measurements["missed"] ?: 0)}/$c" }
        else -> null
    }
}
