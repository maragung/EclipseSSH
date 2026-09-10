package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.ssh.SshKeyProbe
import org.junit.Test

/**
 * Every rule the Add / Edit Host form enforces before it will let a profile be saved.
 *
 * These run on the plain JVM with no Robolectric and no Compose, which is the point of
 * [HostFormDraft] existing: the same rules living inside the `AlertDialog` were unreachable from any
 * JVM test, because Robolectric never idles while a Compose dialog window is open.
 */
class HostFormDraftTest {

    /** A draft that is valid, so each test can make exactly one thing wrong. */
    private fun valid(
        host: String = "edge.example.com",
        username: String = "deploy",
        port: String = "22",
        timeout: String = "15",
        keepAlive: String = "",
        fingerprint: String = "",
        storedFingerprint: String? = null,
        proxyType: ProxyType = ProxyType.NONE,
        proxyJump: String = "",
        socksHost: String = "",
        socksPort: String = "1080",
        passphrase: String = "",
        keyPicked: Boolean = false,
        pickedKey: SshKeyProbe? = null,
        storedCredentials: StoredCredentials = StoredCredentials(),
        forgetKey: Boolean = false,
        wakeOnLanMac: String = "",
    ) = HostFormDraft(
        host = host,
        username = username,
        port = port,
        timeout = timeout,
        keepAlive = keepAlive,
        fingerprint = fingerprint,
        storedFingerprint = storedFingerprint,
        wakeOnLanMac = wakeOnLanMac,
        proxyType = proxyType,
        proxyJump = proxyJump,
        socksHost = socksHost,
        socksPort = socksPort,
        passphrase = passphrase,
        keyPicked = keyPicked,
        pickedKey = pickedKey,
        storedCredentials = storedCredentials,
        forgetKey = forgetKey,
    )

    /** A well-formed 43-character SHA-256 fingerprint, the shape the app prints. */
    private val realPin = "SHA256:" + "A".repeat(43)

    @Test
    fun `the defaults of an untouched form are the app defaults`() {
        val fresh = HostFormDraft()

        assertThat(fresh.port).isEqualTo("22")
        assertThat(fresh.timeout).isEqualTo("15")
        assertThat(fresh.keepAlive).isEmpty()
        assertThat(fresh.socksPort).isEqualTo("1080")
        assertThat(fresh.proxyType).isEqualTo(ProxyType.NONE)
        // The port and the timeout are already valid; only the identity is missing, which is the
        // one thing the app cannot invent for the user.
        assertThat(fresh.portValid).isTrue()
        assertThat(fresh.timeoutValid).isTrue()
        assertThat(fresh.identityValid).isFalse()
        assertThat(fresh.canSave).isFalse()
    }

    @Test
    fun `a minimal host with a name and a user can be saved`() {
        assertThat(valid().canSave).isTrue()
    }

    // --- Identity ---

    @Test
    fun `a blank hostname or username blocks saving`() {
        assertThat(valid(host = "").canSave).isFalse()
        assertThat(valid(username = "").canSave).isFalse()
        // Whitespace is not a hostname. The value is trimmed on save, so accepting a space-only
        // field would persist a host with an empty address that can never connect — and whose name
        // falls back to that same empty address, leaving a blank row in the host list.
        assertThat(valid(host = "   ").identityValid).isFalse()
        assertThat(valid(host = "   ").canSave).isFalse()
        assertThat(valid(username = "\t").canSave).isFalse()
    }

    // --- Port ---

    @Test
    fun `the port must be a number inside the TCP range`() {
        assertThat(valid(port = "1").portValid).isTrue()
        assertThat(valid(port = "65535").portValid).isTrue()
        assertThat(valid(port = "0").portValid).isFalse()
        assertThat(valid(port = "65536").portValid).isFalse()
        assertThat(valid(port = "").portValid).isFalse()
        assertThat(valid(port = "22 ").portValid).isTrue()
        assertThat(valid(port = "0").canSave).isFalse()
    }

    @Test
    fun `an empty port field is not yet an error`() {
        // Mid-typing, after the user has cleared the field to replace it. Flagging that instantly
        // paints the form red while they are still working.
        assertThat(valid(port = "").showPortError).isFalse()
        assertThat(valid(port = "0").showPortError).isTrue()
        assertThat(valid(port = "22").showPortError).isFalse()
        // Blocked from saving all the same — not-yet-an-error is not the same as acceptable.
        assertThat(valid(port = "").canSave).isFalse()
    }

    // --- Timeout ---

    @Test
    fun `the connect timeout must be inside the range the engine honours`() {
        assertThat(valid(timeout = CONNECT_TIMEOUT_RANGE.first.toString()).timeoutValid).isTrue()
        assertThat(valid(timeout = CONNECT_TIMEOUT_RANGE.last.toString()).timeoutValid).isTrue()
        assertThat(valid(timeout = (CONNECT_TIMEOUT_RANGE.first - 1).toString()).timeoutValid).isFalse()
        assertThat(valid(timeout = (CONNECT_TIMEOUT_RANGE.last + 1).toString()).timeoutValid).isFalse()
    }

    @Test
    fun `a blank or zero timeout cannot be saved`() {
        // Unlike keep-alive there is no "inherit" for the timeout, so blank has no meaning — and a
        // zero would make `verify(0, SECONDS)` give up at once and read as an unreachable server.
        assertThat(valid(timeout = "").timeoutValid).isFalse()
        assertThat(valid(timeout = "").canSave).isFalse()
        assertThat(valid(timeout = "0").canSave).isFalse()
    }

    // --- Keep-alive ---

    @Test
    fun `a blank keep-alive means inherit the global interval`() {
        val inherited = valid(keepAlive = "")

        assertThat(inherited.keepAliveValid).isTrue()
        assertThat(inherited.keepAliveNumber).isNull()
        assertThat(inherited.canSave).isTrue()
    }

    @Test
    fun `a keep-alive that is present must be inside the range`() {
        assertThat(valid(keepAlive = KEEP_ALIVE_RANGE.first.toString()).keepAliveValid).isTrue()
        assertThat(valid(keepAlive = KEEP_ALIVE_RANGE.last.toString()).keepAliveValid).isTrue()
        assertThat(valid(keepAlive = "0").keepAliveValid).isFalse()
        assertThat(valid(keepAlive = (KEEP_ALIVE_RANGE.last + 1).toString()).keepAliveValid).isFalse()
        assertThat(valid(keepAlive = "0").canSave).isFalse()
    }

    // --- Fingerprint pinning ---

    @Test
    fun `no pin is the normal case and is always valid`() {
        assertThat(valid(fingerprint = "").fingerprintValid).isTrue()
        assertThat(valid(fingerprint = "").canSave).isTrue()
    }

    @Test
    fun `a pin the app itself printed is accepted`() {
        assertThat(valid(fingerprint = realPin).fingerprintValid).isTrue()
        // Base64 alphabet, all of it. A digest can contain any of these bytes.
        val mixed = "SHA256:" + "aZ09+/".repeat(7) + "a"
        assertThat(mixed.length).isEqualTo("SHA256:".length + 43)
        assertThat(valid(fingerprint = mixed).fingerprintValid).isTrue()
    }

    @Test
    fun `a malformed pin is rejected rather than silently trusted`() {
        // Each of these would be written straight into the known-hosts store by saveHost, where it
        // could never match a real server key — every connection would then look like a key change.
        val bad = listOf(
            "7m3Pq9",                                   // no algorithm prefix
            "MD5:aa:bb:cc",                             // the other fingerprint format
            "SHA256:",                                  // prefix only
            "SHA256:" + "A".repeat(42),                 // one character short
            "SHA256:" + "A".repeat(44),                 // one character long
            "SHA256:" + "A".repeat(42) + "=",           // padded, which the app never emits
            "SHA256:" + "A".repeat(42) + "!",           // outside the base64 alphabet
            "sha256:" + "A".repeat(43),                 // wrong case: not what the app prints
            "SHA256:7m3…Pq9",                           // the old placeholder from the seed data
        )

        for (candidate in bad) {
            assertThat(valid(fingerprint = candidate).fingerprintValid).isFalse()
            assertThat(valid(fingerprint = candidate).canSave).isFalse()
        }
    }

    // --- Wake-on-LAN ---

    @Test
    fun `no wake-on-lan address is the normal case and is always valid`() {
        assertThat(valid(wakeOnLanMac = "").wakeOnLanMacValid).isTrue()
        assertThat(valid(wakeOnLanMac = "").canSave).isTrue()
    }

    @Test
    fun `every mac spelling the parser accepts is a saveable one`() {
        for (mac in listOf("AA:BB:CC:DD:EE:01", "aa:bb:cc:dd:ee:01", "AA-BB-CC-DD-EE-01", "AABBCCDDEE01")) {
            assertThat(valid(wakeOnLanMac = mac).wakeOnLanMacValid).isTrue()
            assertThat(valid(wakeOnLanMac = mac).canSave).isTrue()
        }
    }

    @Test
    fun `a mac that is not a mac blocks saving`() {
        // Each of these would be stored verbatim and handed to the wake menu, which would build a
        // packet no card recognises - a host that looks wakable and is not.
        for (mac in listOf(
            "AA:BB:CC:DD:EE",               // a pair short
            "AA:BB:CC:DD:EE:01:02",         // a pair long
            "AA:BB:CC:DD:EE:ZZ",            // not hex
            "AA:BB-CC:DD:EE:01",            // mixed separators
            "wake the server",              // prose, not an address
        )) {
            assertThat(valid(wakeOnLanMac = mac).wakeOnLanMacValid).isFalse()
            assertThat(valid(wakeOnLanMac = mac).canSave).isFalse()
        }
    }

    @Test
    fun `surrounding whitespace on a pasted pin is tolerated`() {
        // Copying a fingerprint out of a terminal or an email brings whitespace with it, and the
        // value is trimmed before it is saved, so rejecting it here would be rejecting a good pin.
        assertThat(valid(fingerprint = "  $realPin  ").fingerprintValid).isTrue()
    }

    @Test
    fun `a legacy pin already on the profile does not trap the user in the dialog`() {
        val legacy = "SHA256:7m3…Pq9"

        // Editing any other field on a profile that predates this validation still saves.
        val untouched = valid(fingerprint = legacy, storedFingerprint = legacy)
        assertThat(untouched.fingerprintValid).isTrue()
        assertThat(untouched.canSave).isTrue()

        // Clearing it is allowed too — dropping a pin only ever falls back to the verify prompt.
        assertThat(valid(fingerprint = "", storedFingerprint = legacy).canSave).isTrue()

        // But typing a *new* malformed value is not: the exemption covers what is already stored,
        // not whatever the user types next.
        assertThat(valid(fingerprint = "SHA256:nonsense", storedFingerprint = legacy).canSave).isFalse()

        // And a well-formed replacement is accepted.
        assertThat(valid(fingerprint = realPin, storedFingerprint = legacy).canSave).isTrue()
    }

    // --- Connection route ---

    @Test
    fun `a direct route needs no proxy fields`() {
        assertThat(valid(proxyType = ProxyType.NONE).routeValid).isTrue()
    }

    @Test
    fun `proxy jump without a jump host cannot be saved`() {
        // Saving it would connect straight to the target and bypass the bastion the user chose the
        // route for — a silent downgrade of exactly the hop that was the point.
        assertThat(valid(proxyType = ProxyType.PROXY_JUMP, proxyJump = "").routeValid).isFalse()
        assertThat(valid(proxyType = ProxyType.PROXY_JUMP, proxyJump = "").canSave).isFalse()
        assertThat(
            valid(proxyType = ProxyType.PROXY_JUMP, proxyJump = "ops@bastion.example.com:22").canSave,
        ).isTrue()
    }

    @Test
    fun `socks and http routes need an address and a valid port`() {
        for (type in listOf(ProxyType.SOCKS5, ProxyType.HTTP_CONNECT)) {
            assertThat(valid(proxyType = type, socksHost = "").canSave).isFalse()
            assertThat(valid(proxyType = type, socksHost = "127.0.0.1", socksPort = "0").canSave).isFalse()
            assertThat(valid(proxyType = type, socksHost = "127.0.0.1", socksPort = "").canSave).isFalse()
            assertThat(
                valid(proxyType = type, socksHost = "127.0.0.1", socksPort = "65536").canSave,
            ).isFalse()
            assertThat(valid(proxyType = type, socksHost = "127.0.0.1", socksPort = "9050").canSave).isTrue()
            assertThat(
                valid(proxyType = type, socksHost = "127.0.0.1", socksPort = PORT_RANGE.last.toString()).canSave,
            ).isTrue()
        }
    }

    @Test
    fun `proxy fields left over from another route do not block saving`() {
        // Switching the route chips back to Direct hides the proxy fields but keeps their text, so
        // a half-typed SOCKS address must not keep Save disabled forever.
        val leftovers = valid(proxyType = ProxyType.NONE, socksHost = "", socksPort = "", proxyJump = "")

        assertThat(leftovers.canSave).isTrue()
    }

    // --- Parsed values handed to HostProfile ---

    @Test
    fun `parsed numbers are what the saved profile will carry`() {
        val draft = valid(port = "2222", timeout = "120", keepAlive = "45", socksPort = "9050")

        assertThat(draft.portNumber).isEqualTo(2222)
        assertThat(draft.timeoutNumber).isEqualTo(120)
        assertThat(draft.keepAliveNumber).isEqualTo(45)
        assertThat(draft.socksPortNumber).isEqualTo(9050)
    }

    @Test
    fun `unparseable numbers surface as null rather than zero`() {
        // The dialog falls back with `?: DEFAULT`, so a null is what makes the default apply. A 0
        // would sail through that elvis operator and be persisted.
        val draft = valid(port = "", timeout = "", keepAlive = "", socksPort = "")

        assertThat(draft.portNumber).isNull()
        assertThat(draft.timeoutNumber).isNull()
        assertThat(draft.keepAliveNumber).isNull()
        assertThat(draft.socksPortNumber).isNull()
    }

    // --- Saved credentials ---

    @Test
    fun `no credentials at all is a saveable host`() {
        // The app's original behaviour and still the default: nothing saved, asked at every connect.
        assertThat(valid().canSave).isTrue()
        assertThat(valid().keyAttached).isFalse()
    }

    @Test
    fun `a key still being read blocks Save`() {
        // Reading an encrypted OpenSSH key runs bcrypt-pbkdf, so this state lasts long enough to tap
        // Save in. Treating it as "no key" would silently drop the file the user just chose.
        val reading = valid(keyPicked = true, pickedKey = null)

        assertThat(reading.keyReadable).isFalse()
        assertThat(reading.canSave).isFalse()
    }

    @Test
    fun `a key that was read blocks nothing`() {
        val ready = valid(keyPicked = true, pickedKey = SshKeyProbe.Ready("Ed25519"))

        assertThat(ready.keyReadable).isTrue()
        assertThat(ready.keyAttached).isTrue()
        assertThat(ready.canSave).isTrue()
    }

    @Test
    fun `a key that needs a passphrase blocks Save until one is given`() {
        // Saving it would produce a host configured for key auth that cannot authenticate, and the form
        // is the only place the passphrase is ever asked for.
        val locked = valid(keyPicked = true, pickedKey = SshKeyProbe.PassphraseRequired)

        assertThat(locked.keyReadable).isFalse()
        assertThat(locked.keyAttached).isFalse()
        assertThat(locked.canSave).isFalse()
    }

    @Test
    fun `an unreadable key blocks Save`() {
        val junk = valid(keyPicked = true, pickedKey = SshKeyProbe.Unreadable("nope"))

        assertThat(junk.keyReadable).isFalse()
        assertThat(junk.canSave).isFalse()
    }

    @Test
    fun `a stored key counts as attached without anything being picked`() {
        val editing = valid(storedCredentials = StoredCredentials(keyLabel = "id_rsa", keyType = "RSA 2048"))

        assertThat(editing.keyAttached).isTrue()
        assertThat(editing.keyReadable).isTrue()
        assertThat(editing.canSave).isTrue()
    }

    @Test
    fun `forgetting the stored key detaches it`() {
        val forgetting = valid(
            storedCredentials = StoredCredentials(keyLabel = "id_rsa"),
            forgetKey = true,
        )

        assertThat(forgetting.keyAttached).isFalse()
        // Nothing invalid about a host with no key, so Save stays available.
        assertThat(forgetting.canSave).isTrue()
    }

    @Test
    fun `a picked key overrides a stored key being forgotten`() {
        // "Replace private key" sets both: forget what is there, attach what was picked.
        val replacing = valid(
            storedCredentials = StoredCredentials(keyLabel = "old_key"),
            forgetKey = true,
            keyPicked = true,
            pickedKey = SshKeyProbe.Ready("RSA 4096"),
        )

        assertThat(replacing.keyAttached).isTrue()
        assertThat(replacing.canSave).isTrue()
    }

    @Test
    fun `a passphrase with no key to unlock blocks Save`() {
        // A passphrase stored alone is a secret at rest that nothing can ever use, and the store drops
        // it on write. Refusing here is what tells the user why instead of discarding it silently.
        val orphan = valid(passphrase = "hunter2")

        assertThat(orphan.passphraseValid).isFalse()
        assertThat(orphan.canSave).isFalse()
    }

    @Test
    fun `a passphrase is fine alongside a picked key`() {
        val paired = valid(
            passphrase = "hunter2",
            keyPicked = true,
            pickedKey = SshKeyProbe.Ready("Ed25519"),
        )

        assertThat(paired.passphraseValid).isTrue()
        assertThat(paired.canSave).isTrue()
    }

    @Test
    fun `a passphrase is fine alongside a stored key`() {
        val paired = valid(
            passphrase = "hunter2",
            storedCredentials = StoredCredentials(keyLabel = "id_rsa"),
        )

        assertThat(paired.passphraseValid).isTrue()
        assertThat(paired.canSave).isTrue()
    }

    @Test
    fun `a passphrase left behind after forgetting the key blocks Save`() {
        val stranded = valid(
            passphrase = "hunter2",
            storedCredentials = StoredCredentials(keyLabel = "id_rsa", hasPassphrase = true),
            forgetKey = true,
        )

        assertThat(stranded.passphraseValid).isFalse()
        assertThat(stranded.canSave).isFalse()
    }

    @Test
    fun `a blank passphrase is not an orphan`() {
        // Whitespace only: the field has effectively not been filled in, so there is nothing to strand.
        assertThat(valid(passphrase = "   ").passphraseValid).isTrue()
        assertThat(valid(passphrase = "   ").canSave).isTrue()
    }

    @Test
    fun `a stored password alone does not affect key validation`() {
        val passwordOnly = valid(storedCredentials = StoredCredentials(hasPassword = true))

        assertThat(passwordOnly.keyAttached).isFalse()
        assertThat(passwordOnly.credentialsValid).isTrue()
        assertThat(passwordOnly.canSave).isTrue()
    }

    @Test
    fun `credential problems do not mask identity problems`() {
        // Both wrong at once still means Save is off; the two rules are independent.
        val broken = valid(host = "", keyPicked = true, pickedKey = SshKeyProbe.PassphraseRequired)

        assertThat(broken.identityValid).isFalse()
        assertThat(broken.credentialsValid).isFalse()
        assertThat(broken.canSave).isFalse()
    }
}
