package dev.eclipse.ssh.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.eclipse.ssh.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricUnlocker @Inject constructor() {
    /** True when a prompt raised right now could succeed. */
    fun canAuthenticate(activity: FragmentActivity): Boolean = unavailableReason(activity) == null

    /**
     * Null when biometric unlock is usable, otherwise a sentence naming why it is not.
     *
     * The four cases are genuinely different to the person holding the phone - a sensor that does not
     * exist, one that is temporarily busy, one with nothing enrolled on it, and one the platform has
     * put behind a security update - and only the middle one is worth retrying. Before this, all four
     * arrived as an authentication *error* from a prompt that had already been raised, which is both
     * a pointless dialog and a worse message: "Authentication failed" for a device that has no
     * fingerprint sensor at all.
     *
     * `runCatching` because `BiometricManager` talks to a system service, and an OEM implementation
     * that throws must not take the process down with the lock screen on top of it. An unknown answer
     * is treated as unavailable, so the PIN - which always works - is what the user is left with.
     */
    fun unavailableReason(activity: FragmentActivity): String? {
        val status = runCatching { BiometricManager.from(activity).canAuthenticate(authenticators()) }
            .getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                activity.getString(R.string.lock_biometric_no_hardware)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                activity.getString(R.string.lock_biometric_hw_unavailable)
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                activity.getString(R.string.lock_biometric_none_enrolled)
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                activity.getString(R.string.lock_biometric_update_required)
            else -> activity.getString(R.string.lock_biometric_unavailable)
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
                override fun onAuthenticationFailed() =
                    onFailure(activity.getString(R.string.lock_auth_failed))
            },
        )
        val allowed = authenticators()
        // Every string below is drawn on the system's own authentication sheet, so all four come
        // from resources rather than from source. They were literals until now, which put four
        // user-facing sentences outside the one file a translator would ever be given — and two of
        // them already existed in strings.xml, unreferenced, with exactly this wording.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_biometric_title))
            .setSubtitle(
                activity.getString(
                    if (allowsDeviceCredential()) R.string.lock_biometric_subtitle
                    else R.string.lock_biometric_subtitle_no_credential,
                ),
            )
            .setAllowedAuthenticators(allowed)
            .apply {
                // A negative button is mandatory unless DEVICE_CREDENTIAL is allowed;
                // BiometricPrompt throws IllegalArgumentException otherwise.
                if (!allowsDeviceCredential()) setNegativeButtonText(activity.getString(R.string.lock_use_pin))
            }
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { onFailure(it.message ?: activity.getString(R.string.lock_biometric_unavailable)) }
    }

    /**
     * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is only supported from API 30 onwards. On
     * API 28-29 that combination is rejected by BiometricPrompt and reported as
     * BIOMETRIC_ERROR_UNSUPPORTED by BiometricManager, which silently disabled biometric
     * unlock on the app's own minSdk. Those levels therefore use BIOMETRIC_STRONG alone
     * plus an explicit negative button that falls back to the in-app PIN.
     */
    private fun allowsDeviceCredential(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun authenticators(): Int = if (allowsDeviceCredential()) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
