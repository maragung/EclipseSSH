package dev.eclipse.ssh.feature.about

/**
 * Static metadata for the About screen.
 *
 * The version, build, and the list of open-source licences come from one
 * place. The Composable reads from this object so the data and the
 * rendering cannot drift.
 */
data class AboutInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val kotlinVersion: String,
    val agpVersion: String,
    val gradleVersion: String,
    val sshdVersion: String,
    val composeBom: String,
    val hiltVersion: String,
    val roomVersion: String,
) {
    /**
     * The short, single-line version used by the settings row and the
     * "About" screen header.
     */
    val versionLine: String get() = "$appName $versionName ($versionCode)"
}

/**
 * The list of open-source licences the app depends on, with a short
 * attribution and a URL for the full text. The order is the order the
 * About screen displays them; the renderer splits into primary
 * ("Apache 2.0", "MIT", "EPL 2.0") and the rest.
 */
val OPEN_SOURCE_LICENSES: List<OpenSourceLicense> = listOf(
    OpenSourceLicense(
        name = "JetBrains Mono",
        version = "2.3.0 (bundled font)",
        license = "SIL Open Font License 1.1",
        url = "https://github.com/JetBrains/JetBrainsMono",
    ),
    OpenSourceLicense(
        name = "Apache MINA SSHD",
        version = "2.14.0",
        license = "Apache License 2.0",
        url = "https://github.com/apache/mina-sshd",
    ),
    OpenSourceLicense(
        name = "Bouncy Castle (Bcpkix + Bcprov)",
        version = "transitive",
        license = "Bouncy Castle Licence",
        url = "https://www.bouncycastle.org/license.html",
    ),
    OpenSourceLicense(
        name = "ed25519-java (i2p)",
        version = "0.3.0",
        license = "MIT License",
        url = "https://github.com/i2p/i2p.i2p",
    ),
    OpenSourceLicense(
        name = "SLF4J",
        version = "2.0.17",
        license = "MIT License",
        url = "https://www.slf4j.org/license.html",
    ),
    OpenSourceLicense(
        name = "Kotlin / Kotlin Coroutines",
        version = "2.1.20 / 1.10.1",
        license = "Apache License 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    OpenSourceLicense(
        name = "AndroidX",
        version = "Compose BOM 2025.04.01",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "Hilt",
        version = "2.56.1",
        license = "Apache License 2.0",
        url = "https://github.com/google/dagger",
    ),
    OpenSourceLicense(
        name = "Room",
        version = "2.7.1",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "DataStore",
        version = "1.1.4",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "WorkManager",
        version = "2.10.0",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "Biometric",
        version = "1.1.0",
        license = "Apache License 2.0",
        url = "https://github.com/androidx/androidx",
    ),
    OpenSourceLicense(
        name = "JUnit 4",
        version = "4.13.2",
        license = "Eclipse Public License 1.0",
        url = "https://junit.org/junit4/license.html",
    ),
    OpenSourceLicense(
        name = "Truth",
        version = "1.4.4",
        license = "Apache License 2.0",
        url = "https://github.com/google/truth",
    ),
    OpenSourceLicense(
        name = "Turbine",
        version = "1.2.0",
        license = "Apache License 2.0",
        url = "https://github.com/cashapp/turbine",
    ),
    OpenSourceLicense(
        name = "Robolectric",
        version = "4.14.1",
        license = "MIT License",
        url = "https://github.com/robolectric/robolectric",
    ),
)

data class OpenSourceLicense(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
)
