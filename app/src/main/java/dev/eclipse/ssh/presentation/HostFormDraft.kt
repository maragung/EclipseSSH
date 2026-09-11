package dev.eclipse.ssh.presentation

import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_SOCKS_PORT
import dev.eclipse.ssh.data.model.DEFAULT_SSH_PORT
import dev.eclipse.ssh.data.model.HOST_KEY_FINGERPRINT_PATTERN
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.feature.wakeonlan.parseMac
import dev.eclipse.ssh.ssh.SshKeyProbe

/**
 * The fallible half of the Add / Edit Host form: the fields a user can get wrong, and the single
 * source of truth for whether Save may be pressed.
 *
 * It lives outside the composable on purpose. Every rule here is a rule about *data*, and a rule
 * about data that can only be reached by tapping through an `AlertDialog` is a rule that cannot be
 * tested on this project — Robolectric never idles with a Compose dialog window open, so the
 * dialog's contents are unreachable from a JVM test. Extracting the rules makes them ordinary
 * functions with ordinary tests, and leaves the composable holding nothing but text-field state.
 *
 * The name, group, tags, favourite flag, accent colour, auth method and proxy credentials are
 * deliberately absent: none of them has a valid/invalid distinction — they are either empty, or
 * they fall back to a documented default when saved.
 *
 * The saved-credential fields are the exception to that last sentence and are here in full, because
 * they are the ones where accepting bad input costs the most. A key file that cannot be parsed, or a
 * passphrase typed with no key to unlock, are both saveable states that produce a host which looks
 * configured and fails at connect time — long after the file picker has closed and with a server-side
 * rejection as the only symptom.
 */
internal data class HostFormDraft(
    val host: String = "",
    val username: String = "",
    val port: String = DEFAULT_SSH_PORT.toString(),
    val timeout: String = DEFAULT_CONNECT_TIMEOUT_SECONDS.toString(),
    /** Blank means "follow [dev.eclipse.ssh.data.model.AppSettings.keepAliveSeconds]". */
    val keepAlive: String = "",
    val fingerprint: String = "",
    /**
     * The fingerprint already stored on the profile being edited, if any.
     *
     * Without this an existing host could become unsaveable. Profiles predate this validation —
     * they arrive from an older backup, from a hand-written import, or from the app's own seed data —
     * and refusing to save a value the user never typed would trap them in the dialog with no way
     * out but deleting a pin they may need. An untouched legacy value is therefore accepted as-is;
     * anything the user actually types has to be well formed.
     */
    val storedFingerprint: String? = null,
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyJump: String = "",
    val socksHost: String = "",
    val socksPort: String = DEFAULT_SOCKS_PORT.toString(),
    /**
     * The passphrase typed into the form, if any.
     *
     * Present as a validation input only. It is never compared, stored or echoed here — the draft is
     * a value the composable rebuilds on every keystroke, so it must not be the thing that decides
     * what a secret *is*, only whether the combination the user has assembled makes sense.
     */
    val passphrase: String = "",
    /** A key file was picked during this editing session. */
    val keyPicked: Boolean = false,
    /**
     * The Wake-on-LAN MAC as typed, or blank for none.
     *
     * Blank is valid — most hosts have no address to wake — and a value that is present but not a
     * MAC in any accepted spelling is not, because the kebab item would silently send a packet no
     * card answers to. Validated through the same [parseMac] the importer and the sender use, so
     * there is one definition of what a MAC is and not three that can drift.
     */
    val wakeOnLanMac: String = "",
    /**
     * What reading that file produced, or null while the read is still running. See [SshKeyProbe].
     *
     * Split from [keyPicked] rather than folded into the sealed type because "picked, not read yet"
     * is a state the form is in for as long as the key derivation takes — bcrypt-pbkdf, on purpose,
     * for an OpenSSH key — and it must disable Save. Treating it as "no key picked" would leave Save
     * enabled and silently drop the key the user chose; treating it as unreadable would flash an
     * error on a file that turns out to be fine.
     */
    val pickedKey: SshKeyProbe? = null,
    /** What is already saved on the profile being edited. Metadata only, never secret material. */
    val storedCredentials: StoredCredentials = StoredCredentials(),
    /** The user pressed "Forget key", so the stored key no longer counts as attached. */
    val forgetKey: Boolean = false,
) {
    val portNumber: Int? = port.trim().toIntOrNull()
    val timeoutNumber: Int? = timeout.trim().toIntOrNull()
    val keepAliveNumber: Int? = keepAlive.trim().toIntOrNull()
    val socksPortNumber: Int? = socksPort.trim().toIntOrNull()

    val portValid: Boolean = portNumber != null && portNumber in PORT_RANGE
    val timeoutValid: Boolean = timeoutNumber != null && timeoutNumber in CONNECT_TIMEOUT_RANGE

    /** Blank is valid — it means inherit. A value that is present but out of range is not. */
    val keepAliveValid: Boolean =
        keepAlive.isBlank() || (keepAliveNumber != null && keepAliveNumber in KEEP_ALIVE_RANGE)

    /**
     * Accepts blank (no pin), what the app itself prints, or the value already on the profile.
     *
     * Matching the app's own output means a pin can be copied straight out of the host-key prompt
     * and pasted back in, which is the only workflow that makes pinning usable at all.
     */
    val fingerprintValid: Boolean = fingerprint.isBlank() ||
        FINGERPRINT_PATTERN.matches(fingerprint.trim()) ||
        fingerprint.trim() == storedFingerprint?.trim()

    /** Blank is valid — it means no Wake-on-LAN. Anything typed has to parse as a MAC. */
    val wakeOnLanMacValid: Boolean = wakeOnLanMac.isBlank() || parseMac(wakeOnLanMac) != null

    private val proxyNeedsRoute: Boolean =
        proxyType == ProxyType.SOCKS5 || proxyType == ProxyType.HTTP_CONNECT

    /**
     * The chosen route has everywhere to go.
     *
     * A SOCKS5 or HTTP CONNECT profile without a proxy address, or a ProxyJump without a jump host,
     * would connect straight to the target instead — quietly bypassing the very hop the user chose
     * the route for. Refusing to save is the only honest outcome.
     */
    val routeValid: Boolean = when {
        proxyType == ProxyType.PROXY_JUMP -> proxyJump.isNotBlank()
        proxyNeedsRoute ->
            socksHost.isNotBlank() && socksPortNumber != null && socksPortNumber in PORT_RANGE
        else -> true
    }

    /** A hostname and a username are the two things the app cannot invent a default for. */
    val identityValid: Boolean = host.isNotBlank() && username.isNotBlank()

    /**
     * There will be a usable key on this host once it is saved — either one picked just now, or one
     * already stored and not being forgotten.
     */
    val keyAttached: Boolean =
        pickedKey is SshKeyProbe.Ready || (storedCredentials.hasKey && !forgetKey)

    /**
     * Either no key was picked, or the one that was picked has been read successfully.
     *
     * [SshKeyProbe.PassphraseRequired] fails this on purpose: it means the file did not parse without
     * a passphrase, so the user has one more field to fill before there is anything worth saving.
     * Storing it anyway would produce a host that is configured for key auth and cannot authenticate,
     * and the passphrase prompt would never come back — the form is the only place it is asked for.
     */
    val keyReadable: Boolean = !keyPicked || pickedKey is SshKeyProbe.Ready

    /**
     * A typed passphrase needs a key to unlock.
     *
     * Not pedantry: a passphrase saved on its own is a secret at rest that can never be used and that
     * nothing in the UI would ever show again, and [dev.eclipse.ssh.data.credentials.HostCredentialStore]
     * drops it on write for exactly that reason. Refusing here is what tells the user why, instead of
     * silently discarding what they typed.
     */
    val passphraseValid: Boolean = passphrase.isBlank() || keyAttached

    val credentialsValid: Boolean = keyReadable && passphraseValid

    val canSave: Boolean = identityValid && portValid && timeoutValid && keepAliveValid &&
        fingerprintValid && routeValid && credentialsValid && wakeOnLanMacValid

    /** Only flag a bad port once there is something to be wrong about; an empty field is mid-typing. */
    val showPortError: Boolean = port.isNotBlank() && !portValid

    companion object {
        /**
         * The one shared definition — see [HOST_KEY_FINGERPRINT_PATTERN]. Kept as an alias so the
         * form's own rule still reads locally, but there is no second copy to drift from the
         * importer's.
         */
        val FINGERPRINT_PATTERN = HOST_KEY_FINGERPRINT_PATTERN
    }
}
