package dev.eclipse.ssh.feature.about

/**
 * The open-source licences the app depends on — the single list the About dialog renders, so
 * the data and the rendering cannot drift.
 *
 * Versions are mirrored by hand from gradle/libs.versions.toml (and, for FreeRDP, the pin in
 * freerdp/build.gradle.kts): there is no runtime path from a version catalog to a dialog, so
 * the mirror and the pins must be updated together.
 *
 * Licences are stated exactly as each project's own licensing file states them. Several were
 * corrected against upstream in September 2026 — ed25519-java is CC0, not MIT, and its home
 * is str4d/ed25519-java, not the i2p tree it is published under — so do not "fix" a licence
 * from memory; check the project first.
 */
val OPEN_SOURCE_LICENSES: List<OpenSourceLicense> = listOf(
    OpenSourceLicense(
        name = "Apache MINA SSHD",
        version = "2.14.0",
        license = "Apache License 2.0",
        purpose = "SSH transport, SFTP and port forwarding",
        url = "https://github.com/apache/mina-sshd",
    ),
    OpenSourceLicense(
        name = "FreeRDP",
        version = "3.31.1",
        license = "Apache License 2.0",
        purpose = "RDP remote desktop client, cross-compiled per ABI",
        url = "https://github.com/FreeRDP/FreeRDP",
    ),
    OpenSourceLicense(
        name = "LibFreeRDP Java bridge",
        version = "3.31.1 (adapted copy)",
        license = "Mozilla Public License 2.0",
        purpose = "the JNI wrapper between the app and FreeRDP",
        url = "https://github.com/FreeRDP/FreeRDP",
    ),
    OpenSourceLicense(
        name = "OpenSSL",
        version = "bundled with FreeRDP",
        license = "Apache License 2.0",
        purpose = "TLS and NLA for the RDP client",
        url = "https://github.com/openssl/openssl",
    ),
    OpenSourceLicense(
        name = "cJSON",
        version = "bundled with FreeRDP",
        license = "MIT License",
        purpose = "JSON handling inside the RDP client",
        url = "https://github.com/DaveGamble/cJSON",
    ),
    OpenSourceLicense(
        name = "uriparser",
        version = "bundled with FreeRDP",
        license = "BSD 3-Clause",
        purpose = "URI handling inside the RDP client",
        url = "https://github.com/uriparser/uriparser",
    ),
    OpenSourceLicense(
        name = "vernacular-vnc",
        version = "f39cbe2 (JitPack)",
        license = "MIT License",
        purpose = "VNC remote desktop client",
        url = "https://github.com/maragung/vernacular-vnc",
    ),
    OpenSourceLicense(
        name = "ed25519-java",
        version = "0.3.0",
        license = "CC0 1.0 (public domain)",
        purpose = "Ed25519 key support",
        url = "https://github.com/str4d/ed25519-java",
    ),
    OpenSourceLicense(
        name = "SLF4J",
        version = "2.0.17",
        license = "MIT License",
        purpose = "logging facade",
        url = "https://www.slf4j.org/license.html",
    ),
    OpenSourceLicense(
        name = "Kotlin / Kotlin Coroutines",
        version = "2.1.20 / 1.10.1",
        license = "Apache License 2.0",
        purpose = "language and async runtime",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    OpenSourceLicense(
        name = "AndroidX",
        version = "Compose BOM 2025.04.01",
        license = "Apache License 2.0",
        purpose = "UI toolkit",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "Hilt",
        version = "2.56.1",
        license = "Apache License 2.0",
        purpose = "dependency injection",
        url = "https://github.com/google/dagger",
    ),
    OpenSourceLicense(
        name = "Room",
        version = "2.7.1",
        license = "Apache License 2.0",
        purpose = "host database",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "DataStore",
        version = "1.1.4",
        license = "Apache License 2.0",
        purpose = "settings storage",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "WorkManager",
        version = "2.10.0",
        license = "Apache License 2.0",
        purpose = "background transfers",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "Biometric",
        version = "1.1.0",
        license = "Apache License 2.0",
        purpose = "vault unlock",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "JetBrains Mono",
        version = "2.3.0 (bundled font)",
        license = "SIL Open Font License 1.1",
        purpose = "terminal typeface",
        url = "https://github.com/JetBrains/JetBrainsMono",
    ),
    OpenSourceLicense(
        name = "Bouncy Castle (Bcpkix + Bcprov)",
        version = "(transitive dependency)",
        license = "Bouncy Castle Licence",
        purpose = null,
        url = "https://www.bouncycastle.org/license.html",
    ),
    // Test-only from here down: attributed for completeness, but none of it ships in the APK.
    OpenSourceLicense(
        name = "JUnit 4",
        version = "4.13.2",
        license = "Eclipse Public License 1.0",
        purpose = "test-only, not shipped in the APK",
        url = "https://junit.org/junit4/license.html",
    ),
    OpenSourceLicense(
        name = "Truth",
        version = "1.4.4",
        license = "Apache License 2.0",
        purpose = "test-only, not shipped in the APK",
        url = "https://github.com/google/truth",
    ),
    OpenSourceLicense(
        name = "Turbine",
        version = "1.2.0",
        license = "Apache License 2.0",
        purpose = "test-only, not shipped in the APK",
        url = "https://github.com/cashapp/turbine",
    ),
    OpenSourceLicense(
        name = "Robolectric",
        version = "4.14.1",
        license = "MIT License",
        purpose = "test-only, not shipped in the APK",
        url = "https://github.com/robolectric/robolectric",
    ),
)

/**
 * One dependency the About dialog lists. [purpose] is the one-line "what is it here for" a
 * reader of an About screen actually wants; it is null where the name already says everything
 * (a transitive dependency nothing calls directly).
 */
data class OpenSourceLicense(
    val name: String,
    val version: String,
    val license: String,
    val purpose: String?,
    val url: String,
)
