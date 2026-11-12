package vn.vicomart.kidslock.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import vn.vicomart.kidslock.R
import vn.vicomart.kidslock.service.MonitorService

/**
 * Transparent screen that shows the system biometric prompt (the phone owner's fingerprint/face).
 * Any outcome other than success puts the lock screen straight back.
 */
class BiometricUnlockActivity : AppCompatActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finishWith(MonitorService.ACTION_UNLOCKED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finishWith(MonitorService.ACTION_RELOCK)
                }
                // onAuthenticationFailed: one bad attempt; the prompt stays open.
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.bio_title))
            .setSubtitle(getString(R.string.bio_subtitle))
            .setNegativeButtonText(getString(R.string.use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    override fun onStop() {
        super.onStop()
        // User left (Home, Recents…) without authenticating: lock again.
        if (!isChangingConfigurations) finishWith(MonitorService.ACTION_RELOCK)
    }

    private fun finishWith(action: String) {
        if (done) return
        done = true
        MonitorService.send(this, action)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
