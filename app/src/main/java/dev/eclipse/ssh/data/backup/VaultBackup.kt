package dev.eclipse.ssh.data.backup

import dev.eclipse.ssh.data.model.ALGORITHM_LIST_MAX_LENGTH
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_AUTH_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.DEFAULT_SERVER_ALIVE_COUNT_MAX
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.ENVIRONMENT_MAX_LENGTH
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.STARTUP_COMMAND_MAX_LENGTH
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_TYPE_CHOICES
import dev.eclipse.ssh.data.model.HOST_KEY_FINGERPRINT_PATTERN
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.data.model.encodeForwardRules
import dev.eclipse.ssh.data.model.encodeRemoteDesktop
import dev.eclipse.ssh.data.settings.SettingsRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Passphrase-encrypted backup of the workspace configuration (host profiles and
 * settings). A full vault excludes credentials on purpose and they remain device-local inside the
 * Android Keystore vault: SSH passwords, private keys, the unlock PIN hash, and the SOCKS
 * proxy password are never written to a vault backup. Every other host and settings field
 * round-trips losslessly, so restoring a vault reproduces the workspace apart from those secrets.
 *
 * A single-account export is the one deliberate exception, because its purpose is different: it
 * moves *one account* to another device, and an account that arrives without its password asks
 * the user for the one thing the export already knew. [toAccountJson] may therefore carry the
 * account's saved SSH password and key passphrase in an opt-in `credentials` block, inside the
 * same AES-GCM/PBKDF2 envelope as the rest of the payload — a block no vault backup ever writes
 * and no older build ever reads. Private keys stay out of both formats: the key is the credential
 * whose compromise is catastrophic, it already has its own export path from the Keys screen, and
 * the account file plus that separately exported key is a complete account without duplicating
 * key material.
 *
 * Older payloads still import, and this is the property the format is built around rather than a
 * concession: every key is read with an `opt…(key, default)` whose default is the field's own shipped
 * default, so a backup written before a field existed restores a host that behaves exactly as it did on
 * the build that wrote it. Version 1 omitted the extra settings and host fields; version 2 omitted the
 * twenty-one advanced per-host columns, which is what version 3 adds.
 *
 * **Every imported value is range- or shape-checked**, without exception, for a reason that is easy to
 * lose sight of: a backup is a file the user can hand-edit, mail to themselves, or restore from a
 * truncated copy, so it is untrusted input that goes straight into the engine. An unchecked import is a
 * way to install a 0-second timeout, a 0-column pty, a `TERM` no terminfo database has, or a forwarding
 * rule the form would have refused. Anything that fails its check falls back to the default rather than
 * failing the import - one bad field must not cost the user their hosts.
 */
object VaultBackup {
    const val VERSION = 3

    fun toJson(hosts: List<HostProfile>, settings: AppSettings, knownHosts: Map<String, String>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("settings", JSONObject().apply {
            put("biometricUnlock", settings.biometricUnlock)
            put("darkTheme", settings.darkTheme)
            put("clipboardSeconds", settings.clearClipboardAfterSeconds)
            put("keepAliveSeconds", settings.keepAliveSeconds)
            put("reconnectBaseSeconds", settings.reconnectBaseSeconds)
            put("terminalFontSize", settings.terminalFontSize)
            put("terminalMinColumns", settings.terminalMinColumns)
            put("pinEnabled", settings.pinEnabled)
            put("legacyAlgorithms", settings.legacyAlgorithms)
            put("terminalTheme", settings.terminalTheme)
            put("blockScreenshots", settings.blockScreenshots)
            put("reconnectAskFirst", settings.reconnectAskFirst)
        })
        root.put("hosts", JSONArray().apply {
            hosts.forEach { host -> put(JSONObject().apply {
                put("id", host.id)
                put("name", host.name)
                put("host", host.host)
                put("username", host.username)
                put("port", host.port)
                put("authMethod", host.authMethod.name)
                put("group", host.group)
                put("tags", JSONArray(host.tags))
                put("isFavorite", host.isFavorite)
                host.lastConnectedAt?.let { put("lastConnectedAt", it) }
                host.fingerprint?.let { put("fingerprint", it) }
                put("proxyType", host.proxyType.name)
                host.proxyJump?.let { put("proxyJump", it) }
                host.socksHost?.let { put("socksHost", it) }
                put("socksPort", host.socksPort)
                host.socksUsername?.let { put("socksUsername", it) }
                host.accentColor?.let { put("accentColor", it) }
                put("connectTimeoutSeconds", host.connectTimeoutSeconds)
                host.keepAliveSeconds?.let { put("keepAliveSeconds", it) }
                put("autoLoginSftp", host.autoLoginSftp)
                // The advanced per-host columns. Absent from version 2 backups, which is why every one
                // of them is read back against its own default rather than against zero or false.
                put("compression", host.compression)
                put("keepAliveEnabled", host.keepAliveEnabled)
                put("serverAliveCountMax", host.serverAliveCountMax)
                put("authTimeoutSeconds", host.authTimeoutSeconds)
                put("autoReconnect", host.autoReconnect)
                put("maxReconnectAttempts", host.maxReconnectAttempts)
                put("reconnectBackoffSeconds", host.reconnectBackoffSeconds)
                put("usePty", host.usePty)
                put("terminalType", host.terminalType)
                put("terminalColumns", host.terminalColumns)
                put("terminalRows", host.terminalRows)
                put("keyboardInteractiveAuth", host.keyboardInteractiveAuth)
                // Tri-state: null means "follow the app-wide switch", and writing it as a boolean would
                // turn every host with no opinion into one that has pinned this device's setting.
                host.legacyAlgorithms?.let { put("legacyAlgorithms", it) }
                host.ciphers?.let { put("ciphers", it) }
                host.kexAlgorithms?.let { put("kexAlgorithms", it) }
                host.macs?.let { put("macs", it) }
                host.hostKeyAlgorithms?.let { put("hostKeyAlgorithms", it) }
                // Written unconditionally, blank included, so a host whose startup command was cleared
                // restores cleared. Neither is a credential, and both say so in the form that edits
                // them - but a backup is the one place they leave the device, so they are also the two
                // fields [HostProfile.toString] redacts, in case one is used as one anyway.
                put("startupCommand", host.startupCommand)
                put("environment", host.environment)
                // Re-encoded from the decoded rules rather than copied, so a column that arrived from a
                // hand-edited file is normalised on its way out instead of being passed on.
                put("savedForwards", encodeForwardRules(decodeForwardRules(host.savedForwards, host.id)))
                // Same treatment for the remote-desktop column, and it travels in the clear by
                // design: the line is a host, a port and flags - nothing to redact.
                put("remoteDesktop", encodeRemoteDesktop(decodeRemoteDesktop(host.remoteDesktop)))
                // Written unconditionally even though it is false by default, so a host whose
                // forwarding was turned on and back off restores "off" rather than whatever the
                // importing build ships as its default. Absent from every older backup, which
                // optBoolean resolves to false - the grant stays opt-in on restore, the same way
                // the Room migration keeps it off for upgraded rows.
                put("agentForwarding", host.agentForwarding)
                put("hostKeyPolicy", host.hostKeyPolicy.name)
            }) }
        })
        root.put("knownHosts", JSONObject().apply { knownHosts.forEach { (key, value) -> put(key, value) } })
        return root.toString()
    }

    fun fromJson(json: String): Triple<List<HostProfile>, AppSettings, Map<String, String>> {
        val root = try {
            JSONObject(json)
        } catch (error: Throwable) {
            throw BackupFormatException("Backup contents are not valid Eclipse SSH JSON", error)
        }
        val defaults = AppSettings()
        val settingsObj = root.optJSONObject("settings") ?: JSONObject()
        val settings = AppSettings(
            biometricUnlock = settingsObj.optBoolean("biometricUnlock", defaults.biometricUnlock),
            darkTheme = settingsObj.optBoolean("darkTheme", defaults.darkTheme),
            clearClipboardAfterSeconds = settingsObj.optInt("clipboardSeconds", defaults.clearClipboardAfterSeconds),
            keepAliveSeconds = settingsObj.optInt("keepAliveSeconds", defaults.keepAliveSeconds),
            reconnectBaseSeconds = settingsObj.optInt("reconnectBaseSeconds", defaults.reconnectBaseSeconds),
            terminalFontSize = settingsObj.optInt("terminalFontSize", defaults.terminalFontSize),
            // Normalized on the way in as well as on the way out: a backup is a file a user can edit,
            // and the width it names has to be one the pty would actually accept.
            terminalMinColumns = SettingsRepository.normalizeMinColumns(
                settingsObj.optInt("terminalMinColumns", defaults.terminalMinColumns),
            ),
            pinEnabled = settingsObj.optBoolean("pinEnabled", defaults.pinEnabled),
            legacyAlgorithms = settingsObj.optBoolean("legacyAlgorithms", defaults.legacyAlgorithms),
            terminalTheme = settingsObj.optString("terminalTheme", defaults.terminalTheme)
                .takeIf { name -> TerminalTheme.entries.any { it.name == name } } ?: defaults.terminalTheme,
            // Absent from backups written before this setting existed, which optBoolean resolves to
            // the default rather than to false-by-accident.
            blockScreenshots = settingsObj.optBoolean("blockScreenshots", defaults.blockScreenshots),
            reconnectAskFirst = settingsObj.optBoolean("reconnectAskFirst", defaults.reconnectAskFirst),
        )
        val hostsArray = root.optJSONArray("hosts") ?: JSONArray()
        val hosts = ArrayList<HostProfile>(hostsArray.length())
        for (i in 0 until hostsArray.length()) {
            val h = hostsArray.getJSONObject(i)
            hosts += HostProfile(
                id = h.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = h.optString("name"),
                host = h.optString("host"),
                username = h.optString("username"),
                port = h.optInt("port", 22).takeIf { it in 1..65535 } ?: 22,
                authMethod = AuthMethod.entries.firstOrNull { it.name == h.optString("authMethod") } ?: AuthMethod.PASSWORD,
                group = h.optString("group", "Personal").ifBlank { "Personal" },
                tags = h.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                isFavorite = h.optBoolean("isFavorite", false),
                lastConnectedAt = if (h.isNull("lastConnectedAt")) null else h.optLong("lastConnectedAt").takeIf { it > 0L },
                // Shape-checked for the same reason as the known-hosts block below: this field is
                // not decoration, `MainViewModel.saveHost` seeds the trust store from it, so an
                // imported profile carrying an arbitrary string is a second route to pinning a key
                // the user never saw. Dropping an unparseable pin leaves the host on
                // trust-on-first-use, which is the app's normal behaviour and asks before trusting.
                fingerprint = if (h.isNull("fingerprint")) null else {
                    h.optString("fingerprint").trim().takeIf(HOST_KEY_FINGERPRINT_PATTERN::matches)
                },
                proxyType = ProxyType.entries.firstOrNull { it.name == h.optString("proxyType") } ?: ProxyType.NONE,
                proxyJump = if (h.isNull("proxyJump")) null else h.optString("proxyJump").takeIf(String::isNotBlank),
                socksHost = if (h.isNull("socksHost")) null else h.optString("socksHost").takeIf(String::isNotBlank),
                socksPort = h.optInt("socksPort", 1080).takeIf { it in 1..65535 } ?: 1080,
                socksUsername = if (h.isNull("socksUsername")) null else h.optString("socksUsername").takeIf(String::isNotBlank),
                accentColor = if (h.isNull("accentColor")) null else h.optLong("accentColor").takeIf { it != 0L },
                // Range-checked like every other imported number: a backup is untrusted input, and a
                // hand-edited or truncated file must not be able to install a 0-second timeout.
                connectTimeoutSeconds = h.optInt("connectTimeoutSeconds", DEFAULT_CONNECT_TIMEOUT_SECONDS)
                    .takeIf { it in CONNECT_TIMEOUT_RANGE } ?: DEFAULT_CONNECT_TIMEOUT_SECONDS,
                keepAliveSeconds = if (h.isNull("keepAliveSeconds")) null else {
                    h.optInt("keepAliveSeconds").takeIf { it in KEEP_ALIVE_RANGE }
                },
                // Absent from every backup written before this setting existed, and optBoolean's
                // default is what keeps those importing as they used to behave rather than as
                // false-by-accident.
                autoLoginSftp = h.optBoolean("autoLoginSftp", HostProfile.DEFAULT_AUTO_LOGIN_SFTP),
                compression = h.optBoolean("compression", false),
                keepAliveEnabled = h.optBoolean("keepAliveEnabled", true),
                serverAliveCountMax = h.optInt("serverAliveCountMax", DEFAULT_SERVER_ALIVE_COUNT_MAX)
                    .takeIf { it in SERVER_ALIVE_COUNT_RANGE } ?: DEFAULT_SERVER_ALIVE_COUNT_MAX,
                authTimeoutSeconds = h.optInt("authTimeoutSeconds", DEFAULT_AUTH_TIMEOUT_SECONDS)
                    .takeIf { it in AUTH_TIMEOUT_RANGE } ?: DEFAULT_AUTH_TIMEOUT_SECONDS,
                autoReconnect = h.optBoolean("autoReconnect", true),
                maxReconnectAttempts = h.optInt("maxReconnectAttempts", DEFAULT_MAX_RECONNECT_ATTEMPTS)
                    .takeIf { it in MAX_RECONNECT_ATTEMPTS_RANGE } ?: DEFAULT_MAX_RECONNECT_ATTEMPTS,
                // The sentinel is outside the range on purpose - 0 means "inherit the app-wide delay" -
                // so it has to be admitted here as well, or restoring a host that inherits would give it
                // a fixed delay it never had.
                reconnectBackoffSeconds = h.optInt("reconnectBackoffSeconds", INHERIT_RECONNECT_BACKOFF)
                    .takeIf { it == INHERIT_RECONNECT_BACKOFF || it in RECONNECT_BACKOFF_RANGE }
                    ?: INHERIT_RECONNECT_BACKOFF,
                usePty = h.optBoolean("usePty", true),
                // Checked against the offered list rather than merely non-blank: `TERM` is looked up in
                // the server's terminfo database, and a name that is not in it fails as a broken `vim`
                // rather than as a setting - the exact reason the form is a set of choices and not a
                // text field. See [TERMINAL_TYPE_CHOICES].
                terminalType = h.optString("terminalType", DEFAULT_TERMINAL_TYPE)
                    .takeIf { it in TERMINAL_TYPE_CHOICES } ?: DEFAULT_TERMINAL_TYPE,
                // 0 is the "match the screen" sentinel and is admitted alongside the range, like the
                // backoff above. A pty may not be 0 columns wide, so an out-of-range number becomes the
                // sentinel rather than being clamped to 20 - "automatic" is what the user had.
                terminalColumns = h.optInt("terminalColumns", 0)
                    .takeIf { it == 0 || it in TERMINAL_COLUMNS_RANGE } ?: 0,
                terminalRows = h.optInt("terminalRows", 0)
                    .takeIf { it == 0 || it in TERMINAL_ROWS_RANGE } ?: 0,
                keyboardInteractiveAuth = h.optBoolean("keyboardInteractiveAuth", true),
                // Tri-state, and `isNull` is what preserves it: an absent key and an explicit `null` both
                // have to come back as "follow the app-wide switch" rather than as false.
                legacyAlgorithms = if (h.isNull("legacyAlgorithms")) null else h.optBoolean("legacyAlgorithms"),
                // Length only. What the names mean is the engine's business, and it already refuses to
                // propose an empty list - a preference whose names this build cannot honour falls back to
                // the library's own list rather than failing key exchange - so dropping an unrecognised
                // name here would throw away a preference that is valid on the device it came from.
                ciphers = h.algorithmList("ciphers"),
                kexAlgorithms = h.algorithmList("kexAlgorithms"),
                macs = h.algorithmList("macs"),
                hostKeyAlgorithms = h.algorithmList("hostKeyAlgorithms"),
                startupCommand = h.optString("startupCommand").take(STARTUP_COMMAND_MAX_LENGTH),
                environment = h.optString("environment").take(ENVIRONMENT_MAX_LENGTH),
                // Re-encoded from what decoding accepted, so an imported column contains exactly the
                // rules the engine will act on: a line the app would not have let the user save does not
                // survive the round trip, and the [MAX_SAVED_FORWARDS] cap is applied by the decoder.
                savedForwards = encodeForwardRules(decodeForwardRules(h.optString("savedForwards"))),
                // The remote-desktop column reads the same way: what decoding accepts is what is kept,
                // and an absent key (a backup from before the column existed) is simply no endpoint.
                remoteDesktop = encodeRemoteDesktop(decodeRemoteDesktop(h.optString("remoteDesktop"))),
                // The one boolean in this file where "absent means false" is a security property and
                // not just a default: an older backup predating the flag must not restore it on, for
                // the same reason the migration keeps it off - it is a grant, and only the user can
                // make it on the host that carries it.
                agentForwarding = h.optBoolean("agentForwarding", false),
                hostKeyPolicy = HostKeyPolicy.entries.firstOrNull { it.name == h.optString("hostKeyPolicy") }
                    ?: HostKeyPolicy.ASK,
            )
        }
        // Known-host entries are validated before they are handed back, because importing a vault
        // writes them straight into the trust store: `MainViewModel.importVault` passes them to
        // `SshConnectionManager.importKnownHosts`, which merges them in. An unvalidated entry is
        // therefore a way to pre-trust a key the user has never seen — pair a hostile
        // `host:port -> fingerprint` line with a host profile in the same file and the fingerprint
        // prompt that is this app's entire host-key defence never appears for it. Only entries in
        // exactly the shape the app itself writes are kept, so a genuine backup round-trips
        // untouched while a hand-edited one loses whatever does not parse.
        //
        // Malformed entries are dropped rather than failing the whole import: one of them can only
        // ever fail to match a real host key, so refusing the import over a single bad line would
        // cost the user their hosts and settings for no gain.
        val knownHostsObj = root.optJSONObject("knownHosts") ?: JSONObject()
        val knownHosts = mutableMapOf<String, String>()
        knownHostsObj.keys().forEach { key ->
            val fingerprint = knownHostsObj.optString(key)
            if (isKnownHostKey(key) && HOST_KEY_FINGERPRINT_PATTERN.matches(fingerprint)) knownHosts[key] = fingerprint
        }
        return Triple(hosts, settings, knownHosts)
    }

    /**
     * One algorithm preference column as imported: bounded, trimmed, and null when there is nothing.
     *
     * Null rather than blank, because the column's two states are not the same thing to the engine -
     * null is "no opinion, negotiate normally" and an empty list would be an instruction to propose
     * nothing at all. See [HostProfile.ciphers].
     */
    private fun JSONObject.algorithmList(key: String): String? =
        if (isNull(key)) null else optString(key).trim().take(ALGORITHM_LIST_MAX_LENGTH).takeIf(String::isNotBlank)

    /** Exports one connection profile using the same passphrase-encrypted format as a vault. */
    fun toAccountJson(host: HostProfile, credentials: AccountCredentials? = null): String {
        // Built on top of toJson rather than threaded through it, so the vault path cannot start
        // carrying credentials by accident: the only writer of the block is this function.
        val root = JSONObject(toJson(listOf(host), AppSettings(), emptyMap()))
        if (credentials != null) {
            val block = JSONObject()
            credentials.password?.let { block.put("password", it) }
            credentials.passphrase?.let { block.put("passphrase", it) }
            // Absent by default, so exports from before the block existed and exports of accounts
            // with nothing stored read the same way on import - and no version bump is needed.
            if (block.length() > 0) root.put("credentials", block)
        }
        return root.toString()
    }

    /** Reads one account export and rejects empty/multi-purpose payloads. */
    fun fromAccountJson(json: String): ImportedAccount {
        val hosts = fromJson(json).first
        if (hosts.size != 1) throw BackupFormatException("Account export must contain exactly one host")
        val block = runCatching { JSONObject(json) }.getOrNull()?.optJSONObject("credentials")
        // Shape-checked like every other imported value: the block is untrusted input, so an
        // over-long or blank field is dropped rather than stored or fatal.
        val credentials = block?.let {
            AccountCredentials(password = it.readAccountSecret("password"), passphrase = it.readAccountSecret("passphrase"))
        }?.takeIf { it.password != null || it.passphrase != null }
        return ImportedAccount(host = hosts.single(), credentials = credentials)
    }

    /** One credential field as imported: bounded, trimmed, and null when there is nothing usable. */
    private fun JSONObject.readAccountSecret(key: String): String? =
        if (isNull(key)) null else optString(key).trim().take(MAX_ACCOUNT_CREDENTIAL_LENGTH).takeIf(String::isNotBlank)

    fun encrypt(plaintext: String, passphrase: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(salt, cipher.iv, ciphertext).joinToString(DELIMITER) { Base64.getEncoder().encodeToString(it) }
    }

    /**
     * Reverses [encrypt].
     *
     * A wrong passphrase surfaces from the JCE as `AEADBadTagException`; it is translated
     * into [BackupFormatException] so callers can show "incorrect passphrase" instead of
     * leaking a crypto stack trace (or crashing).
     */
    fun decrypt(payload: String, passphrase: String): String {
        val parts = payload.trim().split(DELIMITER)
        if (parts.size != 3) throw BackupFormatException("This file is not an Eclipse SSH backup")
        return try {
            val salt = Base64.getDecoder().decode(parts[0])
            val iv = Base64.getDecoder().decode(parts[1])
            val ciphertext = Base64.getDecoder().decode(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Throwable) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException("Incorrect passphrase or corrupted backup", error)
        }
    }

    /**
     * True for a `host:port` key in the form `KnownHostsStore` writes.
     *
     * Split from the right, because a literal IPv6 address contains colons of its own and
     * `InetSocketAddress.getHostString` hands them over unbracketed — `::1:22` has to keep four.
     */
    private fun isKnownHostKey(key: String): Boolean {
        val host = key.substringBeforeLast(':', missingDelimiterValue = "")
        val port = key.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
        return host.isNotBlank() && host.none(Char::isWhitespace) && port != null && port in PORT_RANGE
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val ITERATIONS = 100_000
    private const val DELIMITER = "."

    /**
     * Bound on an imported account credential's length. Generous against any real password - longer
     * than every passphrase policy - and bounded against a hand-edited file, because the value goes
     * from the file straight into the credential store.
     */
    private const val MAX_ACCOUNT_CREDENTIAL_LENGTH = 4096
}

/** Raised when a backup payload cannot be read: wrong passphrase, truncation, or bad JSON. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The credentials an account export may carry: the saved SSH password and the saved key passphrase,
 * nothing else. Private keys are deliberately absent - see [VaultBackup].
 *
 * `toString` is redacted because this type travels through ViewModels where anything can end up in
 * a log; the values themselves are the entire point of the type, so the redaction has to be spelled
 * out rather than inherited from a generic data-class rendering.
 */
data class AccountCredentials(val password: String? = null, val passphrase: String? = null) {
    override fun toString(): String = "AccountCredentials(password=${if (password != null) "***" else "null"}, passphrase=${if (passphrase != null) "***" else "null"})"
}

/** One account export as imported: the profile, plus the credentials the file carried, if any. */
data class ImportedAccount(val host: HostProfile, val credentials: AccountCredentials?)
