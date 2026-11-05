package app.pocketagent.android.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun biometricAvailable(activity: FragmentActivity): Boolean {
    val manager = BiometricManager.from(activity)
    val result = manager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
    )
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

fun promptBiometric(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    if (!biometricAvailable(activity)) {
        onResult(false)
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("解锁 PocketAgent")
        .setSubtitle("验证指纹或面容后继续")
        .setNegativeButtonText("取消")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )
        .build()
    runCatching { prompt.authenticate(info) }.onFailure { onResult(false) }
}
