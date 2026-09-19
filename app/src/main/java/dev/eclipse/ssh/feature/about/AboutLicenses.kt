package dev.eclipse.ssh.feature.about

/**
 * The open-source licences the app depends on — the single list the About dialog renders, so
 * the data and the rendering cannot drift.
 *
 * Versions are mirrored by hand from gradle/libs.versions.toml (and, for FreeRDP, the pin in
 * freerdp/build.gradle.kts and the dependency versions in the tarball's own
 * cmake/DepVersions.cmake): there is no runtime path from a version catalog to a dialog, so
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
        version = "2.19.0",
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
        version = "4.0.1 (built into FreeRDP)",
        license = "Apache License 2.0",
        purpose = "TLS and NLA for the RDP client",
        url = "https://github.com/openssl/openssl",
    ),
    OpenSourceLicense(
        name = "cJSON",
        version = "1.7.19 (built into FreeRDP)",
        license = "MIT License",
        purpose = "JSON handling inside the RDP client",
        url = "https://github.com/DaveGamble/cJSON",
    ),
    OpenSourceLicense(
        name = "uriparser",
        version = "1.0.2 (built into FreeRDP)",
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
        version = "2.0.19",
        license = "MIT License",
        purpose = "logging facade",
        url = "https://www.slf4j.org/license.html",
    ),
    OpenSourceLicense(
        name = "Kotlin / Kotlin Coroutines",
        version = "2.4.20 / 1.11.0",
        license = "Apache License 2.0",
        purpose = "language and async runtime",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    OpenSourceLicense(
        name = "AndroidX",
        version = "Compose BOM 2026.09.00",
        license = "Apache License 2.0",
        purpose = "UI toolkit",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "Hilt",
        version = "2.60.1",
        license = "Apache License 2.0",
        purpose = "dependency injection",
        url = "https://github.com/google/dagger",
    ),
    OpenSourceLicense(
        name = "Room",
        version = "2.8.5",
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
        version = "2.305 (bundled font)",
        license = "SIL Open Font License 1.1",
        purpose = "terminal typeface",
        url = "https://github.com/JetBrains/JetBrainsMono",
    ),
    OpenSourceLicense(
        name = "Apache Commons Compress",
        version = "1.28.0",
        license = "Apache License 2.0",
        purpose = "tar and zip reading, and the Linux workspace snapshot",
        url = "https://commons.apache.org/proper/commons-compress/",
    ),
    OpenSourceLicense(
        name = "XZ for Java",
        version = "1.12",
        license = "Public domain",
        purpose = "the .tar.xz codec for the archive browser",
        url = "https://tukaani.org/xz/java.html",
    ),
    OpenSourceLicense(
        name = "Bouncy Castle (bcprov-jdk18on)",
        version = "1.86",
        license = "Bouncy Castle Licence",
        purpose = "the provider that replaces the platform's stripped copy at startup",
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
        version = "1.4.5",
        license = "Apache License 2.0",
        purpose = "test-only, not shipped in the APK",
        url = "https://github.com/google/truth",
    ),
    OpenSourceLicense(
        name = "Turbine",
        version = "1.2.1",
        license = "Apache License 2.0",
        purpose = "test-only, not shipped in the APK",
        url = "https://github.com/cashapp/turbine",
    ),
    OpenSourceLicense(
        name = "Robolectric",
        version = "4.17",
        license = "MIT License",
        purpose = "test-only, not shipped in the APK",
        url = "https://github.com/robolectric/robolectric",
    ),
)

/**
 * One dependency the About dialog lists. [purpose] is the one-line "what is it here for" a
 * reader of an About screen actually wants; it is nullable so that an entry whose name already
 * says everything can leave it out, though at present every entry carries one.
 */
data class OpenSourceLicense(
    val name: String,
    val version: String,
    val license: String,
    val purpose: String?,
    val url: String,
)
