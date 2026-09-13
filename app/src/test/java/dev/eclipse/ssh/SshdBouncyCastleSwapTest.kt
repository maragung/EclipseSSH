package dev.eclipse.ssh

import com.google.common.truth.Truth.assertThat
import java.security.AlgorithmParameters
import java.security.Provider
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import org.apache.sshd.common.cipher.ECCurves
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression cover for the crash-on-open that shipped from v1.1.12 through v1.1.16.
 *
 * MINA sshd 2.19.0 stopped hard-coding its EC curve parameters: `ECCurves` resolves them at
 * class-initialization time through `SecurityUtils.getAlgorithmParameters("EC")`, which lands
 * on whatever provider `Security.getProvider("BC")` answers. On Android that is the platform's
 * own stripped Bouncy Castle, which has no EC parameter services — so the initializer threw
 * `IllegalArgumentException: No EC params for nistp256`, surfaced as
 * `ExceptionInInitializerError` from `KeyPairProvider`'s static chain, and the process died
 * during MainActivity's first composition. The app could not draw a frame on any device.
 *
 * The host JVM running these tests has no `BC` provider at all, which is why the whole suite
 * stayed green while the installed APK died on launch — sshd constructs its own instance of
 * the bundled provider when the name is free, and that instance has the services. What these
 * tests do is recreate the device condition faithfully — a provider squatting on the `BC`
 * name with no services, exactly the shape of Android's platform copy — and pin
 * [EclipseApp]'s swap against it: the squatter is replaced, in its slot, by the bundled full
 * provider, and the EC parameter resolution that the sshd initializer performs starts
 * working through the name.
 */
class SshdBouncyCastleSwapTest {

    /** Squats on the `BC` name with no services — the shape of Android's stripped platform copy. */
    private class StrippedPlatformBc :
        Provider(BouncyCastleProvider.PROVIDER_NAME, 1.0, "simulated stripped platform provider")

    @Test
    fun `the bundled provider replaces a name squatter in its slot`() {
        val name = BouncyCastleProvider.PROVIDER_NAME
        val original = Security.getProvider(name)
        val originalSlot = Security.getProviders().indexOfFirst { it.name.equals(name, ignoreCase = true) }
        try {
            Security.removeProvider(name)
            Security.insertProviderAt(StrippedPlatformBc(), 1)

            // The device condition this class exists for: whatever holds `BC` cannot resolve
            // EC parameters, and sshd's curve initializer goes through exactly this call.
            val squatter = Security.getProvider(name)
            assertThat(squatter).isInstanceOf(StrippedPlatformBc::class.java)
            assertThrows(NoSuchAlgorithmException::class.java) {
                AlgorithmParameters.getInstance("EC", squatter)
            }

            EclipseApp.swapBundledBouncyCastleIntoProviderRegistry()

            val replacement = Security.getProvider(name)
            assertThat(replacement).isInstanceOf(BouncyCastleProvider::class.java)
            // Same slot the squatter occupied: the swap must not reorder anything else in the
            // process-wide preference list.
            val squatterSlot = 0 // installed at 1-based position 1 above
            assertThat(Security.getProviders().indexOfFirst { it.name.equals(name, ignoreCase = true) })
                .isEqualTo(squatterSlot)
            // The exact resolution the sshd initializer needs: a named SEC curve, turned into
            // a concrete parameter spec through the provider that now holds the name.
            val curve = AlgorithmParameters.getInstance("EC", replacement)
            curve.init(ECGenParameterSpec("secp256r1"))
            val spec = curve.getParameterSpec(ECParameterSpec::class.java)
            assertThat(spec.curve.field.fieldSize).isEqualTo(256)
        } finally {
            Security.removeProvider(name)
            if (original != null && originalSlot >= 0) {
                Security.insertProviderAt(original, originalSlot + 1)
            }
        }
    }

    @Test
    fun `with the name free the swap adds nothing`() {
        // On the host JVM — and on any device whose OEM did not install a `BC` — nothing holds
        // the name, and the correct behaviour is to leave the registry alone: sshd builds its
        // own instance of the bundled provider reflectively, which already has the services.
        val name = BouncyCastleProvider.PROVIDER_NAME
        val original = Security.getProvider(name)
        val originalSlot = Security.getProviders().indexOfFirst { it.name.equals(name, ignoreCase = true) }
        try {
            Security.removeProvider(name)

            EclipseApp.swapBundledBouncyCastleIntoProviderRegistry()

            assertThat(Security.getProvider(name)).isNull()
        } finally {
            Security.removeProvider(name)
            if (original != null && originalSlot >= 0) {
                Security.insertProviderAt(original, originalSlot + 1)
            }
        }
    }

    @Test
    fun `sshd curve resolution succeeds through the bundled provider`() {
        // The call that crashed on device, end to end: ECCurves' parameter resolution. On this
        // JVM it exercises sshd's own fallback (or the swapped provider, if the swap test ran
        // first); either way the assertion pins that curve parameters resolve rather than
        // throwing from inside the static initializer.
        assertThat(ECCurves.nistp256.getParameters().curve.field.fieldSize).isEqualTo(256)
        assertThat(ECCurves.nistp521.getParameters().curve.field.fieldSize).isEqualTo(521)
    }
}
