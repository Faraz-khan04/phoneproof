package app.phoneproof.ui.wizard

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.phoneproof.ui.components.PrimaryButton

fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Prominent disclosure shown right before a test that needs a runtime permission (Play policy:
 * explain the purpose in-app before the system dialog). Declining marks the test "Not tested".
 */
@Composable
fun PermissionStep(
    step: Int, total: Int, minutesLeft: Int,
    title: String,
    instruction: String,
    why: String,
    permission: String,
    buttonLabel: String,
    onGranted: () -> Unit,
    onDeclined: () -> Unit,
    onClose: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onGranted() else onDeclined()
    }
    TestStep(step, total, minutesLeft, title, instruction, onClose = onClose, onSkip = onDeclined,
        answers = { PrimaryButton(buttonLabel, { launcher.launch(permission) }, Modifier.fillMaxWidth()) },
    ) {
        Text(why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class BioAvailability { Ready, NoneEnrolled, NoHardware, Unavailable }

fun biometricAvailability(context: Context): BioAvailability =
    when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BioAvailability.Ready
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BioAvailability.NoneEnrolled
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BioAvailability.NoHardware
        else -> BioAvailability.Unavailable
    }

/**
 * Shows the system fingerprint / face prompt. [onResult] gets true on success, false on
 * a hard error, null if the seller cancelled.
 */
fun runBiometricPrompt(activity: FragmentActivity, onResult: (Boolean?) -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
        override fun onAuthenticationError(code: Int, msg: CharSequence) {
            val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_CANCELED
            onResult(if (cancelled) null else false)
        }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Fingerprint or face test")
            .setSubtitle("Unlock once to show the sensor works. Nothing is stored.")
            .setNegativeButtonText("Skip")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build(),
    )
}
