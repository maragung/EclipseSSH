package dev.eclipse.ssh.data.backup

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.ALGORITHM_LIST_MAX_LENGTH
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_AUTH_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.DEFAULT_SERVER_ALIVE_COUNT_MAX
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.ENVIRONMENT_MAX_LENGTH
import dev.eclipse.ssh.data.model.HOST_KEY_FINGERPRINT_PATTERN
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.presentation.HostFormDraft
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.MAX_SAVED_FORWARDS
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.STARTUP_COMMAND_MAX_LENGTH
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.ssh.cipherFactoriesFor
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs under Robolectric because [VaultBackup] parses with `org.json`, which is a throwing
 * stub on the bare host JVM. The crypto itself comes from the host JDK either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultBackupTest {
    @Test
    fun `encrypt then decrypt restores original content`() {
        val original = "{\"hosts\":[{\"name\":\"Edge\"}]}"
        val passphrase = "correct horse battery staple"

        val encrypted = VaultBackup.encrypt(original, passphrase)

        assertThat(encrypted).isNotEqualTo(original)
        assertThat(VaultBackup.decrypt(encrypted, passphrase)).isEqualTo(original)
    }

    @Test
    fun `decrypt with wrong passphrase fails`() {
        val encrypted = VaultBackup.encrypt("secret payload", "right-pass")

        assertThrows(Exception::class.java) { VaultBackup.decrypt(encrypted, "wrong-pass") }
    }

    @Test
    fun `encryption is non deterministic thanks to random salt`() {
        val first = VaultBackup.encrypt("same", "pass")
        val second = VaultBackup.encrypt("same", "pass")

        assertThat(first).isNotEqualTo(second)
        assertThat(VaultBackup.decrypt(first, "pass")).isEqualTo("same")
        assertThat(VaultBackup.decrypt(second, "pass")).isEqualTo("same")
    }

    // --- Wrong passphrase and damaged payloads must not reach the user as a crash ---

    @Test
    fun `a wrong passphrase surfaces as a readable backup error`() {
        val encrypted = VaultBackup.encrypt("payload", "right-pass")

        val error = assertThrows(BackupFormatException::class.java) {
            VaultBackup.decrypt(encrypted, "wrong-pass")
        }

        assertThat(error).hasMessageThat().contains("passphrase")
    }

    @Test
    fun `a truncated payload is rejected before any crypto runs`() {
        val encrypted = VaultBackup.encrypt("payload", "pass")
        val truncated = encrypted.substringBeforeLast('.')

        val error = assertThrows(BackupFormatException::class.java) {
            VaultBackup.decrypt(truncated, "pass")
        }

        assertThat(error).hasMessageThat().contains("not an Eclipse SSH backup")
    }

    @Test
    fun `an arbitrary file picked by mistake is rejected`() {
        listOf("", "   ", "hello world", "not.a.backup", "{\"json\":true}").forEach { payload ->
            assertThrows(BackupFormatException::class.java) { VaultBackup.decrypt(payload, "pass") }
        }
    }

    @Test
    fun `a tampered ciphertext fails the GCM tag check`() {
        val encrypted = VaultBackup.encrypt("payload", "pass")
        val parts = encrypted.split('.')
        val flipped = parts[2].let { body ->
            // Change one Base64 character so the authentication tag no longer matches.
            val replacement = if (body[0] == 'A') 'B' else 'A'
            replacement + body.substring(1)
        }

        assertThrows(BackupFormatException::class.java) {
            VaultBackup.decrypt("${parts[0]}.${parts[1]}.$flipped", "pass")
        }
    }

    @Test
    fun `surrounding whitespace from a text editor does not break decryption`() {
        val encrypted = VaultBackup.encrypt("payload", "pass")

        assertThat(VaultBackup.decrypt("\n  $encrypted  \n", "pass")).isEqualTo("payload")
    }

    @Test
    fun `an empty passphrase still round trips`() {
        val encrypted = VaultBackup.encrypt("payload", "")

        assertThat(VaultBackup.decrypt(encrypted, "")).isEqualTo("payload")
    }

    @Test
    fun `unicode payloads survive the UTF-8 round trip`() {
        val payload = "{\"name\":\"Produksi — Jakarta 🌏\",\"note\":\"ünïcødé\"}"

        assertThat(VaultBackup.decrypt(VaultBackup.encrypt(payload, "pass"), "pass")).isEqualTo(payload)
    }

    // --- JSON round trip: every non-secret field has to survive export and import ---

    @Test
    fun `hosts settings and known hosts round trip losslessly`() {
        val hosts = listOf(
            HostProfile(
                id = "host-1",
                name = "Production edge",
                host = "edge.example.com",
                username = "deploy",
                port = 2222,
                authMethod = AuthMethod.SSH_KEY,
                group = "Work",
                tags = listOf("prod", "eu-west"),
                isFavorite = true,
                lastConnectedAt = 1_750_000_000_000L,
                fingerprint = HOST1_FINGERPRINT,
                proxyType = ProxyType.SOCKS5,
                proxyJump = "bastion.example.com",
                socksHost = "127.0.0.1",
                socksPort = 9050,
                socksUsername = "tor",
                accentColor = 0xFF3366FFL,
                connectTimeoutSeconds = 60,
                keepAliveSeconds = 120,
                // Opposite of the default, so a backup that dropped the field would still fail the
                // whole-object comparison below instead of matching by coincidence.
                autoLoginSftp = false,
            ),
            HostProfile(id = "host-2", name = "Minimal", host = "10.0.0.5", username = "root"),
        )
        val settings = AppSettings(
            biometricUnlock = false,
            darkTheme = false,
            clearClipboardAfterSeconds = 120,
            keepAliveSeconds = 45,
            reconnectBaseSeconds = 17,
            terminalFontSize = 20,
            pinEnabled = true,
            legacyAlgorithms = true,
            terminalTheme = TerminalTheme.AMBER.name,
            // Set to the opposite of its default on purpose. The assertion below compares whole
            // objects, so a field left at its default would match even if the backup dropped it.
            blockScreenshots = true,
            reconnectAskFirst = true,
        )
        val knownHosts = mapOf("edge.example.com:2222" to EDGE_FINGERPRINT)

        val (restoredHosts, restoredSettings, restoredKnownHosts) =
            VaultBackup.fromJson(VaultBackup.toJson(hosts, settings, knownHosts))

        assertThat(restoredKnownHosts).isEqualTo(knownHosts)
        // isLocked is runtime-only and never persisted, so compare the rest field by field.
        assertThat(restoredSettings).isEqualTo(settings.copy(isLocked = false))
        // Passwords stay in the Keystore, so only that field is expected to drop.
        assertThat(restoredHosts).isEqualTo(hosts.map { it.copy(socksPassword = null) })
    }

    @Test
    fun `credentials are never written into a vault backup`() {
        val host = HostProfile(
            name = "Edge",
            host = "edge.example.com",
            username = "deploy",
            socksHost = "127.0.0.1",
            socksPassword = "super-secret-proxy-password",
        )

        val json = VaultBackup.toJson(listOf(host), AppSettings(), emptyMap())

        assertThat(json).doesNotContain("super-secret-proxy-password")
        assertThat(json).doesNotContain("socksPassword")
        assertThat(json).doesNotContain("credentials")
        assertThat(VaultBackup.fromJson(json).first.single().socksPassword).isNull()
    }

    @Test
    fun `a version 1 backup still imports with defaults for the newer fields`() {
        // Written by the shipped v1 format: no reconnectBaseSeconds, legacyAlgorithms,
        // terminalTheme, blockScreenshots, proxy fields or accentColor.
        val v1 = """
            {"version":1,
             "settings":{"biometricUnlock":false,"darkTheme":true,"clipboardSeconds":60,
                         "keepAliveSeconds":20,"terminalFontSize":15,"pinEnabled":true},
             "hosts":[{"id":"legacy-1","name":"Legacy","host":"old.example.com",
                       "username":"admin","port":22,"authMethod":"PASSWORD",
                       "group":"Personal","tags":["legacy"],"isFavorite":false}],
             "knownHosts":{"old.example.com:22":"$OLD_FINGERPRINT"}}
        """.trimIndent()

        val (hosts, settings, knownHosts) = VaultBackup.fromJson(v1)

        val defaults = AppSettings()
        assertThat(settings.clearClipboardAfterSeconds).isEqualTo(60)
        assertThat(settings.reconnectBaseSeconds).isEqualTo(defaults.reconnectBaseSeconds)
        assertThat(settings.legacyAlgorithms).isEqualTo(defaults.legacyAlgorithms)
        assertThat(settings.terminalTheme).isEqualTo(defaults.terminalTheme)
        assertThat(settings.blockScreenshots).isEqualTo(defaults.blockScreenshots)
        assertThat(settings.reconnectAskFirst).isEqualTo(defaults.reconnectAskFirst)
        assertThat(hosts).hasSize(1)
        assertThat(hosts.single().proxyType).isEqualTo(ProxyType.NONE)
        assertThat(hosts.single().socksPort).isEqualTo(1080)
        assertThat(hosts.single().accentColor).isNull()
        // A backup predating the columns must not invent a keep-alive: null means "follow the
        // global setting", which is exactly what the host was doing when the backup was written.
        assertThat(hosts.single().connectTimeoutSeconds).isEqualTo(DEFAULT_CONNECT_TIMEOUT_SECONDS)
        assertThat(hosts.single().keepAliveSeconds).isNull()
        // Same reasoning as the Room migration: a backup predating the column describes a host whose
        // file browser opened on connect, so importing it must not switch that off.
        assertThat(hosts.single().autoLoginSftp).isTrue()
        assertThat(hosts.single().tags).containsExactly("legacy")
        assertThat(knownHosts).containsEntry("old.example.com:22", OLD_FINGERPRINT)
    }

    @Test
    fun `a backup edited by hand into nonsense is rejected, not silently accepted`() {
        assertThrows(BackupFormatException::class.java) { VaultBackup.fromJson("not json at all") }
        assertThrows(BackupFormatException::class.java) { VaultBackup.fromJson("") }
    }

    @Test
    fun `an empty object imports as an empty workspace with default settings`() {
        val (hosts, settings, knownHosts) = VaultBackup.fromJson("{}")

        assertThat(hosts).isEmpty()
        assertThat(knownHosts).isEmpty()
        assertThat(settings).isEqualTo(AppSettings())
    }

    @Test
    fun `out of range and unknown values are coerced instead of crashing the import`() {
        val hostile = """
            {"version":2,
             "settings":{"terminalTheme":"NEON_PURPLE"},
             "hosts":[{"name":"Bad","host":"h","username":"u","port":99999,
                       "authMethod":"MAGIC","proxyType":"TELEPATHY","socksPort":-3,
                       "group":"   ","accentColor":0,
                       "connectTimeoutSeconds":0,"keepAliveSeconds":100000}]}
        """.trimIndent()

        val (hosts, settings, _) = VaultBackup.fromJson(hostile)
        val host = hosts.single()

        assertThat(settings.terminalTheme).isEqualTo(TerminalTheme.DARK.name)
        assertThat(host.port).isEqualTo(22)
        assertThat(host.authMethod).isEqualTo(AuthMethod.PASSWORD)
        assertThat(host.proxyType).isEqualTo(ProxyType.NONE)
        assertThat(host.socksPort).isEqualTo(1080)
        assertThat(host.group).isEqualTo("Personal")
        assertThat(host.accentColor).isNull()
        // A zero timeout is the dangerous one: `verify(0, SECONDS)` gives up instantly and the
        // failure reads as an unreachable server, so a hand-edited backup must not be able to
        // install it. Out of range falls back to the default rather than being clamped to 5.
        assertThat(host.connectTimeoutSeconds).isEqualTo(DEFAULT_CONNECT_TIMEOUT_SECONDS)
        // Keep-alive has a null to fall back to, so an impossible interval becomes "inherit".
        assertThat(host.keepAliveSeconds).isNull()
    }

    /**
     * Connection options survive the encrypted round trip at the bounds of their ranges, and a
     * value inside the range is never rewritten.
     *
     * The bounds are what an importer is most likely to reject by an off-by-one, and the whole
     * point of range-checking untrusted input is that legitimate extremes still get through.
     */
    @Test
    fun `connection options at the range bounds import unchanged`() {
        val hosts = listOf(
            HostProfile(
                id = "edge-low",
                name = "Low",
                host = "low.example.com",
                username = "u",
                connectTimeoutSeconds = CONNECT_TIMEOUT_RANGE.first,
                keepAliveSeconds = KEEP_ALIVE_RANGE.first,
            ),
            HostProfile(
                id = "edge-high",
                name = "High",
                host = "high.example.com",
                username = "u",
                connectTimeoutSeconds = CONNECT_TIMEOUT_RANGE.last,
                keepAliveSeconds = KEEP_ALIVE_RANGE.last,
            ),
        )

        val restored = VaultBackup.fromJson(VaultBackup.toJson(hosts, AppSettings(), emptyMap())).first

        assertThat(restored.map { it.connectTimeoutSeconds })
            .containsExactly(CONNECT_TIMEOUT_RANGE.first, CONNECT_TIMEOUT_RANGE.last).inOrder()
        assertThat(restored.map { it.keepAliveSeconds })
            .containsExactly(KEEP_ALIVE_RANGE.first, KEEP_ALIVE_RANGE.last).inOrder()
    }

    /**
     * An explicit `null` keep-alive is written as an absent key and read back as null.
     *
     * `optInt` returns 0 for a missing key, so a naive reader would turn "inherit the global
     * interval" into a 0-second heartbeat — a busy loop of IGNORE packets on every restored host.
     */
    @Test
    fun `the SFTP auto login toggle survives a backup in both positions`() {
        val hosts = listOf(
            HostProfile(id = "on", name = "On", host = "a.example.com", username = "root", autoLoginSftp = true),
            HostProfile(id = "off", name = "Off", host = "b.example.com", username = "root", autoLoginSftp = false),
        )

        val restored = VaultBackup.fromJson(VaultBackup.toJson(hosts, AppSettings(), emptyMap())).first

        assertThat(restored.map { it.autoLoginSftp }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `an inherited keep-alive stays inherited across a backup`() {
        val host = HostProfile(id = "inherit", name = "Inherit", host = "i.example.com", username = "u")

        val json = VaultBackup.toJson(listOf(host), AppSettings(), emptyMap())

        assertThat(json).doesNotContain("keepAliveSeconds\":0")
        assertThat(VaultBackup.fromJson(json).first.single().keepAliveSeconds).isNull()
    }

    @Test
    fun `a host with no id is given one so it cannot collide in Room`() {
        val json = """{"hosts":[{"name":"A","host":"a","username":"u"},
                                {"name":"B","host":"b","username":"u"}]}"""

        val hosts = VaultBackup.fromJson(json).first

        assertThat(hosts.map { it.id }.filter(String::isNotBlank)).hasSize(2)
        assertThat(hosts[0].id).isNotEqualTo(hosts[1].id)
    }

    // --- Single-account export ---

    @Test
    fun `an account export round trips one host`() {
        val host = HostProfile(
            id = "acct-1",
            name = "Shared box",
            host = "box.example.com",
            username = "ci",
            port = 2200,
            authMethod = AuthMethod.KEYBOARD_INTERACTIVE,
            tags = listOf("ci"),
        )

        val restored = VaultBackup.fromAccountJson(VaultBackup.toAccountJson(host))

        assertThat(restored.host).isEqualTo(host)
        assertThat(restored.credentials).isNull()
    }

    /**
     * The reason the credentials block exists: an account export lands on another device as a
     * complete account, password included, so first connect there does not ask for the one thing the
     * export already knew. The passphrase travels too — only meaningful beside a key, but the key
     * never travels in this file, so the passphrase waits on the new device for the key's own import.
     */
    @Test
    fun `an account export carries the saved password and passphrase round trip`() {
        val host = HostProfile(id = "acct-2", name = "Keyed box", host = "box.example.com", username = "ci")

        val restored = VaultBackup.fromAccountJson(
            VaultBackup.toAccountJson(host, AccountCredentials(password = "hunter2", passphrase = "open sesame")),
        )

        assertThat(restored.host).isEqualTo(host)
        assertThat(restored.credentials).isEqualTo(AccountCredentials(password = "hunter2", passphrase = "open sesame"))
    }

    /** Exports written before the block existed, and accounts with nothing stored: same read. */
    @Test
    fun `an account export with a blank credentials block imports as one without`() {
        val host = HostProfile(id = "acct-3", name = "Old box", host = "old.example.com", username = "u")
        val legacy = VaultBackup.toAccountJson(host)
        assertThat(legacy).doesNotContain("credentials")

        val blankBlock = JSONObject(legacy).apply {
            put("credentials", JSONObject().put("password", "  ").put("passphrase", ""))
        }.toString()

        val restored = VaultBackup.fromAccountJson(blankBlock)
        assertThat(restored.host).isEqualTo(host)
        assertThat(restored.credentials).isNull()
    }

    /** The block is untrusted input like every other field: bounded, never fatal. */
    @Test
    fun `an absurdly long account credential is truncated rather than rejected`() {
        val host = HostProfile(id = "acct-4", name = "Stuffed box", host = "box.example.com", username = "u")
        val oversized = "x".repeat(10_000)

        val restored = VaultBackup.fromAccountJson(
            VaultBackup.toAccountJson(host, AccountCredentials(password = oversized)),
        )

        assertThat(restored.credentials?.password).hasLength(4096)
    }

    @Test
    fun `a full vault cannot be imported as a single account`() {
        val json = VaultBackup.toJson(
            listOf(
                HostProfile(name = "A", host = "a", username = "u"),
                HostProfile(name = "B", host = "b", username = "u"),
            ),
            AppSettings(),
            emptyMap(),
        )

        val error = assertThrows(BackupFormatException::class.java) { VaultBackup.fromAccountJson(json) }

        assertThat(error).hasMessageThat().contains("exactly one host")
    }

    @Test
    fun `an account export with no hosts is rejected`() {
        assertThrows(BackupFormatException::class.java) { VaultBackup.fromAccountJson("{\"hosts\":[]}") }
    }

    // --- A backup file is untrusted input, and two of its fields feed the host-key trust store ---

    /**
     * Importing a vault merges its `knownHosts` into the trust store (`importVault` →
     * `SshConnectionManager.importKnownHosts`), so an unchecked entry pre-trusts a key the user has
     * never seen: pair one with a host profile in the same file and the fingerprint prompt — the
     * app's entire host-key defence — never appears for it. Only entries shaped the way the app
     * writes them survive.
     */
    @Test
    fun `known host entries that are not fingerprints the app could have written are dropped`() {
        val hostile = """
            {"version":2,"hosts":[],"knownHosts":{
              "good.example.com:22":"$EDGE_FINGERPRINT",
              "evil.example.com:22":"not a fingerprint at all",
              "truncated.example.com:22":"SHA256:tooshort",
              "md5.example.com:22":"MD5:ab:cd:ef",
              "keyline.example.com:22":"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5",
              "noport.example.com":"$OLD_FINGERPRINT",
              "badport.example.com:70000":"$OLD_FINGERPRINT",
              "zeroport.example.com:0":"$OLD_FINGERPRINT",
              "":"$OLD_FINGERPRINT"
            }}
        """.trimIndent()

        val knownHosts = VaultBackup.fromJson(hostile).third

        assertThat(knownHosts).containsExactly("good.example.com:22", EDGE_FINGERPRINT)
    }

    /**
     * IPv6 keys carry colons of their own — `InetSocketAddress.getHostString` hands them over
     * unbracketed, so `KnownHostsStore` writes `::1:22`. Splitting the port off from the left would
     * throw those away, and a user who trusted a v6 host would silently lose that trust on restore.
     */
    @Test
    fun `an ipv6 known host key survives the shape check`() {
        val json = VaultBackup.toJson(
            hosts = emptyList(),
            settings = AppSettings(),
            knownHosts = mapOf("::1:22" to EDGE_FINGERPRINT, "2001:db8::5:2222" to OLD_FINGERPRINT),
        )

        assertThat(VaultBackup.fromJson(json).third).containsExactly(
            "::1:22", EDGE_FINGERPRINT,
            "2001:db8::5:2222", OLD_FINGERPRINT,
        )
    }

    /**
     * The other door into the trust store: `MainViewModel.saveHost` seeds it from
     * `HostProfile.fingerprint`, so an imported profile carrying an arbitrary string is a second way
     * to pin a key. Dropping the pin leaves the host on trust-on-first-use, which asks first.
     */
    @Test
    fun `a pinned fingerprint that is not the shape the form accepts is dropped`() {
        val json = """
            {"version":2,"hosts":[
              {"id":"a","name":"Pinned","host":"a.example.com","username":"u",
               "fingerprint":"$HOST1_FINGERPRINT"},
              {"id":"b","name":"Bogus","host":"b.example.com","username":"u",
               "fingerprint":"ssh-rsa AAAAB3NzaC1yc2E"},
              {"id":"c","name":"Padded","host":"c.example.com","username":"u",
               "fingerprint":"  $HOST1_FINGERPRINT  "}
            ]}
        """.trimIndent()

        val hosts = VaultBackup.fromJson(json).first.associateBy { it.id }

        assertThat(hosts.getValue("a").fingerprint).isEqualTo(HOST1_FINGERPRINT)
        assertThat(hosts.getValue("b").fingerprint).isNull()
        // Surrounding whitespace is a copy-paste artefact, not a different fingerprint.
        assertThat(hosts.getValue("c").fingerprint).isEqualTo(HOST1_FINGERPRINT)
    }

    /**
     * The importer's rule and the Add Host form's rule are the same object, so a pin the form
     * accepts can never be one the importer throws away. If they drift, a restore un-pins a host
     * with nothing on screen to say so.
     */
    @Test
    fun `the form and the importer agree on what a fingerprint is`() {
        assertThat(HostFormDraft.FINGERPRINT_PATTERN).isSameInstanceAs(HOST_KEY_FINGERPRINT_PATTERN)
        listOf(EDGE_FINGERPRINT, OLD_FINGERPRINT, HOST1_FINGERPRINT).forEach { fingerprint ->
            assertThat(HostFormDraft(host = "h", username = "u", fingerprint = fingerprint).fingerprintValid)
                .isTrue()
        }
    }

    // --- The advanced per-host columns, which arrived after the format did ---

    @Test
    fun `every advanced per-host option survives the round trip`() {
        // Each of the twenty-one set away from its default, so the whole-object comparison at the end
        // fails for a dropped field instead of matching it by coincidence. This is the test that says a
        // restored host connects the way the host that was backed up connected.
        val tuned = HostProfile(
            id = "tuned-host",
            name = "Tuned edge",
            host = "edge.example.com",
            username = "ops",
            compression = true,
            keepAliveEnabled = false,
            serverAliveCountMax = 6,
            authTimeoutSeconds = 90,
            autoReconnect = false,
            maxReconnectAttempts = 9,
            reconnectBackoffSeconds = 12,
            usePty = false,
            terminalType = "screen-256color",
            terminalColumns = 132,
            terminalRows = 50,
            keyboardInteractiveAuth = false,
            legacyAlgorithms = true,
            hostKeyPolicy = HostKeyPolicy.STRICT,
            ciphers = "aes256-gcm@openssh.com,aes128-ctr",
            kexAlgorithms = "curve25519-sha256,diffie-hellman-group14-sha256",
            macs = "hmac-sha2-256-etm@openssh.com",
            hostKeyAlgorithms = "ssh-ed25519,rsa-sha2-512",
            startupCommand = "tmux attach || tmux new",
            environment = "LANG=en_US.UTF-8\nTZ=Europe/Amsterdam",
            savedForwards = "L:8080:intranet.example:80\nR:2222:22\nD:1080",
            remoteDesktop = "V:10.0.1.5:5900 view-only",
            agentForwarding = true,
        )

        val restored = VaultBackup.fromJson(VaultBackup.toJson(listOf(tuned), AppSettings(), emptyMap())).first

        assertThat(restored).containsExactly(tuned)
    }

    @Test
    fun `a version 2 backup imports the advanced options as the behaviour it described`() {
        // Written before any of these columns existed. Every one of them has to arrive as the behaviour
        // the app had when the backup was taken - not as false, not as zero, and not as an empty
        // algorithm list, which is an instruction to propose nothing rather than an absence of opinion.
        val v2 = """
            {"version":2,
             "settings":{},
             "hosts":[{"id":"plain-1","name":"Plain","host":"plain.example.com","username":"admin"}]}
        """.trimIndent()

        val host = VaultBackup.fromJson(v2).first.single()

        assertThat(host.compression).isFalse()
        assertThat(host.keepAliveEnabled).isTrue()
        assertThat(host.serverAliveCountMax).isEqualTo(DEFAULT_SERVER_ALIVE_COUNT_MAX)
        assertThat(host.authTimeoutSeconds).isEqualTo(DEFAULT_AUTH_TIMEOUT_SECONDS)
        assertThat(host.autoReconnect).isTrue()
        assertThat(host.maxReconnectAttempts).isEqualTo(DEFAULT_MAX_RECONNECT_ATTEMPTS)
        assertThat(host.reconnectBackoffSeconds).isEqualTo(INHERIT_RECONNECT_BACKOFF)
        assertThat(host.usePty).isTrue()
        assertThat(host.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
        assertThat(host.terminalColumns).isEqualTo(0)
        assertThat(host.terminalRows).isEqualTo(0)
        assertThat(host.keyboardInteractiveAuth).isTrue()
        assertThat(host.legacyAlgorithms).isNull()
        assertThat(host.hostKeyPolicy).isEqualTo(HostKeyPolicy.ASK)
        assertThat(host.ciphers).isNull()
        assertThat(host.kexAlgorithms).isNull()
        assertThat(host.macs).isNull()
        assertThat(host.hostKeyAlgorithms).isNull()
        assertThat(host.startupCommand).isEmpty()
        assertThat(host.environment).isEmpty()
        assertThat(host.savedForwards).isEmpty()
        assertThat(host.remoteDesktop).isEmpty()
        // The grant again: a backup written before the flag existed imports it off, so the
        // administrator of that host gains nothing the user never gave them.
        assertThat(host.agentForwarding).isFalse()
    }

    @Test
    fun `hostile advanced values are coerced instead of installing themselves`() {
        // The importer is the app's only untrusted-input boundary for these columns: the form validates
        // what a user types, and a hand-edited or truncated backup goes around the form entirely.
        val hostile = """
            {"version":3,
             "hosts":[{"name":"Bad","host":"h","username":"u",
                       "serverAliveCountMax":0,"authTimeoutSeconds":99999,
                       "maxReconnectAttempts":0,"reconnectBackoffSeconds":9999,
                       "terminalType":"vt520","terminalColumns":5000,"terminalRows":-3,
                       "hostKeyPolicy":"TRUST_EVERYTHING",
                       "ciphers":"${"a".repeat(ALGORITHM_LIST_MAX_LENGTH + 200)}",
                       "macs":"   ",
                       "startupCommand":"${"x".repeat(STARTUP_COMMAND_MAX_LENGTH + 200)}",
                       "environment":"${"E".repeat(ENVIRONMENT_MAX_LENGTH + 200)}",
                       "savedForwards":"L:8080\nX:1\nD:1080",
                       "remoteDesktop":"V:not-a-target\nV:5900 view-only"}]}
        """.trimIndent()

        val host = VaultBackup.fromJson(hostile).first.single()

        // Out of range falls back to the shipped default rather than being clamped to the nearest bound:
        // a host that asked for something impossible had no working setting to preserve.
        assertThat(host.serverAliveCountMax).isEqualTo(DEFAULT_SERVER_ALIVE_COUNT_MAX)
        assertThat(host.authTimeoutSeconds).isEqualTo(DEFAULT_AUTH_TIMEOUT_SECONDS)
        assertThat(host.maxReconnectAttempts).isEqualTo(DEFAULT_MAX_RECONNECT_ATTEMPTS)
        assertThat(host.reconnectBackoffSeconds).isEqualTo(INHERIT_RECONNECT_BACKOFF)
        // A TERM the emulator does not implement fails as a broken editor rather than as a setting.
        assertThat(host.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
        // 0 is "match the screen", which is the honest answer for a size that cannot be asked for.
        assertThat(host.terminalColumns).isEqualTo(0)
        assertThat(host.terminalRows).isEqualTo(0)
        // The one field where a wrong answer weakens a defence: an unknown policy name must never read
        // as "accept whatever key turns up".
        assertThat(host.hostKeyPolicy).isEqualTo(HostKeyPolicy.ASK)
        assertThat(host.ciphers).hasLength(ALGORITHM_LIST_MAX_LENGTH)
        // Whitespace is not a preference. Blank has to become null, or the engine would be told to
        // propose an empty MAC list and the key exchange would fail on a host that used to work.
        assertThat(host.macs).isNull()
        assertThat(host.startupCommand).hasLength(STARTUP_COMMAND_MAX_LENGTH)
        assertThat(host.environment).hasLength(ENVIRONMENT_MAX_LENGTH)
        // Only the rules the engine can act on survive: `L:8080` names no target and `X:1` no kind.
        assertThat(host.savedForwards).isEqualTo("D:1080")
        // The remote-desktop column reads the same way: the first line that names a target the
        // engine could dial wins, and a line that names none falls out before it.
        assertThat(host.remoteDesktop).isEqualTo("V:5900 view-only")
    }

    @Test
    fun `a backup cannot carry more forwards than a host may hold`() {
        val many = List(MAX_SAVED_FORWARDS + 8) { "D:${1080 + it}" }.joinToString("\\n")
        val json = """
            {"version":3,"hosts":[{"name":"Many","host":"h","username":"u","savedForwards":"$many"}]}
        """.trimIndent()

        val host = VaultBackup.fromJson(json).first.single()

        assertThat(decodeForwardRules(host.savedForwards)).hasSize(MAX_SAVED_FORWARDS)
    }

    @Test
    fun `a hand edited backup cannot switch a host's encryption off`() {
        // The third layer of the same refusal. The form will not let `none` be typed and the chip rows do
        // not offer it, but a backup file is neither - so the importer keeps the text and the connect
        // path is what refuses to install it, falling back to the library's own ciphers. Asserted here
        // because this is the path that goes around the form.
        val json = """
            {"version":3,"hosts":[{"name":"Plain","host":"h","username":"u","ciphers":"none"}]}
        """.trimIndent()

        val host = VaultBackup.fromJson(json).first.single()

        assertThat(cipherFactoriesFor(host.ciphers)).isNull()
        assertThat(cipherFactoriesFor("none,aes128-ctr")?.map { it.name }).doesNotContain("none")
    }

    @Test
    fun `the advanced sentinels and range bounds import unchanged`() {
        val hosts = listOf(
            HostProfile(
                id = "low",
                name = "Low",
                host = "low.example.com",
                username = "u",
                serverAliveCountMax = SERVER_ALIVE_COUNT_RANGE.first,
                authTimeoutSeconds = AUTH_TIMEOUT_RANGE.first,
                maxReconnectAttempts = MAX_RECONNECT_ATTEMPTS_RANGE.first,
                reconnectBackoffSeconds = RECONNECT_BACKOFF_RANGE.first,
                terminalColumns = TERMINAL_COLUMNS_RANGE.first,
                terminalRows = TERMINAL_ROWS_RANGE.first,
            ),
            HostProfile(
                id = "high",
                name = "High",
                host = "high.example.com",
                username = "u",
                serverAliveCountMax = SERVER_ALIVE_COUNT_RANGE.last,
                authTimeoutSeconds = AUTH_TIMEOUT_RANGE.last,
                maxReconnectAttempts = MAX_RECONNECT_ATTEMPTS_RANGE.last,
                reconnectBackoffSeconds = RECONNECT_BACKOFF_RANGE.last,
                terminalColumns = TERMINAL_COLUMNS_RANGE.last,
                terminalRows = TERMINAL_ROWS_RANGE.last,
            ),
            // The sentinels, which sit outside their ranges on purpose and have to be admitted anyway.
            HostProfile(
                id = "automatic",
                name = "Automatic",
                host = "auto.example.com",
                username = "u",
                reconnectBackoffSeconds = INHERIT_RECONNECT_BACKOFF,
                terminalColumns = 0,
                terminalRows = 0,
            ),
        )

        val restored = VaultBackup.fromJson(VaultBackup.toJson(hosts, AppSettings(), emptyMap())).first

        assertThat(restored).isEqualTo(hosts)
    }

    @Test
    fun `a host that follows the app-wide legacy switch keeps following it`() {
        val hosts = listOf(
            HostProfile(id = "follows", name = "Follows", host = "a.example.com", username = "u"),
            HostProfile(id = "on", name = "On", host = "b.example.com", username = "u", legacyAlgorithms = true),
            HostProfile(id = "off", name = "Off", host = "c.example.com", username = "u", legacyAlgorithms = false),
        )

        val json = VaultBackup.toJson(hosts, AppSettings(), emptyMap())

        // Absent rather than written as false, which is what keeps the third state a state.
        assertThat(VaultBackup.fromJson(json).first.map { it.legacyAlgorithms })
            .containsExactly(null, true, false).inOrder()
    }

    @Test
    fun `an exported backup normalises a forward column it was handed`() {
        // The column can already be malformed before the export: it is written by an importer, and older
        // builds of this app never validated it. Exporting re-encodes what decoding accepted, so a
        // backup file never propagates a line the engine would refuse.
        val host = HostProfile(
            id = "edited",
            name = "Edited",
            host = "h.example.com",
            username = "u",
            savedForwards = "D:1080\nnot-a-rule",
        )

        val json = VaultBackup.toJson(listOf(host), AppSettings(), emptyMap())

        assertThat(json).doesNotContain("not-a-rule")
        assertThat(VaultBackup.fromJson(json).first.single().savedForwards).isEqualTo("D:1080")
    }

    @Test
    fun `a cleared startup command restores cleared`() {
        // The reason both fields are written unconditionally. A host whose startup command was removed
        // and then backed up must not come back running the command again, which is what reading an
        // absent key as "unchanged" would do.
        val configured = HostProfile(
            id = "startup",
            name = "Startup",
            host = "h.example.com",
            username = "u",
            startupCommand = "tmux attach",
            environment = "LANG=C",
        )
        val cleared = configured.copy(startupCommand = "", environment = "")

        val restored = VaultBackup.fromJson(
            VaultBackup.toJson(listOf(configured, cleared), AppSettings(), emptyMap()),
        ).first

        assertThat(restored.map { it.startupCommand }).containsExactly("tmux attach", "").inOrder()
        assertThat(restored.map { it.environment }).containsExactly("LANG=C", "").inOrder()
    }

    private companion object {
        // Real SHA-256 digests, base64 without padding, because that is the only shape
        // `KnownHostsVerifier.fingerprint` produces and therefore the only one a backup can contain.
        const val EDGE_FINGERPRINT = "SHA256:ocsQD1fpccrPJp58JuRjCiWo6dS9014y3xqAtmuJYlQ"
        const val OLD_FINGERPRINT = "SHA256:y6BrVzb69n5UsHtWHq6UOV53TFF6fZEKVDaeEmPM+9Q"
        const val HOST1_FINGERPRINT = "SHA256:wDZbWjhnzDgvaFT9xPbxDHhXJ1yLHlJb64w5n4CUm+U"
    }
}
