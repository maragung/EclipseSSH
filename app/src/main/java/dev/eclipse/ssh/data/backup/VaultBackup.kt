package dev.eclipse.ssh.data.backup

import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.HOST_KEY_FINGERPRINT_PATTERN
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.TerminalTheme
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
 * settings). Credentials are intentionally excluded and remain device-local inside the
 * Android Keystore vault: SSH passwords, private keys, the unlock PIN hash, and the SOCKS
 * proxy password are never written to a backup, because the Keystore key that protects
 * them cannot be exported. Every other host and settings field round-trips losslessly, so
 * restoring a vault reproduces the workspace apart from those secrets.
 *
 * Version 1 payloads (which omitted the extra settings and host fields) still import; the
 * missing keys fall back to [AppSettings] defaults.
 */
object VaultBackup {
    const val VERSION = 2

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
            put("pinEnabled", settings.pinEnabled)
            put("legacyAlgorithms", settings.legacyAlgorithms)
            put("terminalTheme", settings.terminalTheme)
            put("blockScreenshots", settings.blockScreenshots)
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
            pinEnabled = settingsObj.optBoolean("pinEnabled", defaults.pinEnabled),
            legacyAlgorithms = settingsObj.optBoolean("legacyAlgorithms", defaults.legacyAlgorithms),
            terminalTheme = settingsObj.optString("terminalTheme", defaults.terminalTheme)
                .takeIf { name -> TerminalTheme.entries.any { it.name == name } } ?: defaults.terminalTheme,
            // Absent from backups written before this setting existed, which optBoolean resolves to
            // the default rather than to false-by-accident.
            blockScreenshots = settingsObj.optBoolean("blockScreenshots", defaults.blockScreenshots),
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

    /** Exports one connection profile using the same passphrase-encrypted format as a vault. */
    fun toAccountJson(host: HostProfile): String = toJson(listOf(host), AppSettings(), emptyMap())

    /** Reads one connection profile from an account export and rejects empty/multi-purpose payloads. */
    fun fromAccountJson(json: String): HostProfile {
        val hosts = fromJson(json).first
        if (hosts.size != 1) throw BackupFormatException("Account export must contain exactly one host")
        return hosts.single()
    }

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
}

/** Raised when a backup payload cannot be read: wrong passphrase, truncation, or bad JSON. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
