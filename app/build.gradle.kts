import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.eclipse.ssh"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.eclipse.ssh"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing comes from keystore.properties (git-ignored) so the repo
        // never carries a keystore. When the file is absent the release build falls
        // back to the debug key, which keeps `assembleRelease` installable locally.
        create("release") {
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val props = Properties().apply { keystoreFile.inputStream().use(::load) }
                storeFile = rootProject.file(props.getProperty("storeFile") ?: "")
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
            // Stated rather than left to the default, which produced a v2-only APK. v3 is what
            // Android 9 and later verify with, and it is the scheme that carries a rotation proof, so
            // without it this key can never be rotated for already-installed copies. v1 stays off:
            // minSdk is 28, no installer here reads a JAR signature, and it is the scheme the
            // Janus-class attacks target. v4 needs an accompanying .idsig file that only `adb install
            // --incremental` consumes, so it would be a file nobody in this pipeline uses.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                // The fallback itself is intended (see signingConfigs), but it must not be silent:
                // a debug-signed artifact called "release" cannot be updated over a Play-signed
                // install and carries the debug key's identity, and nothing else in the build output
                // says so. Warn loudly rather than change the behaviour.
                logger.warn(
                    "WARNING: keystore.properties is missing, so the release APK will be signed " +
                        "with the DEBUG key. It is not publishable and cannot upgrade an existing " +
                        "install. Provide keystore.properties to sign it for real.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Drops every locale except English and Indonesian from the AndroidX libraries, which
        // otherwise carry ~80 translations each. The app's own strings are English only (there
        // is no values-in), so "in" here only keeps the framework/AndroidX Indonesian strings
        // for a future translation. Replaces defaultConfig.resourceConfigurations, which AGP
        // deprecated for locale filtering.
        localeFilters += listOf("en", "in")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Apache MINA SSHD touches java.nio.file / java.time APIs that need desugaring
        // to stay safe on API 28 devices.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.ExperimentalUnsignedTypes",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
            // MINA SSHD resolves its security providers / factories through
            // META-INF/services, so those entries must survive packaging.
            pickFirsts += setOf("META-INF/services/**")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Robolectric pulls in Conscrypt, which extracts its native library into
                // java.io.tmpdir on first use. Where /tmp is a small tmpfs that write fails,
                // and Conscrypt rethrows it as UnsatisfiedLinkError, which fails every
                // Robolectric test for a reason that looks nothing like disk pressure.
                // Keeping the temp directory under build/ makes it independent of the host's
                // /tmp and lets `clean` reclaim it.
                val jvmTmpDir = layout.buildDirectory.dir("tmp/unit-test-jvm").get().asFile
                test.doFirst { jvmTmpDir.mkdirs() }
                test.systemProperty("java.io.tmpdir", jvmTmpDir.absolutePath)
                // Gradle gives test JVMs 512m. That is enough for plain JVM tests but not for
                // Compose under Robolectric at sdk 35: the android-all jar, a full semantics tree
                // and Robolectric's ShadowTrace section deque together exhaust it mid-test, and it
                // surfaces as an OutOfMemoryError inside text measurement rather than anything that
                // points at the heap size.
                test.maxHeapSize = "1536m"
                // The same provider scoping EclipseApp installs at startup — see
                // EclipseApp.scopeSshdSecurityProviders. The unit-test JVM needs it set from the
                // outside because sshd reads it in a static initializer, and here there is no
                // Application to get in first: Robolectric tests touch sshd inside a sandbox
                // classloader, and a provider registered by name from *there* lands in the
                // process-wide java.security.Security while carrying sandbox-loaded classes.
                // Plain JVM tests running later then resolve "EdDSA" to that provider and every
                // Ed25519 key fails to parse against classes from the other loader — a real
                // failure with a cause nothing in the failing test points at, and one that
                // depended on which tests happened to run first.
                //
                // Spelled out here because a Gradle script cannot read the app's own constants.
                // EclipseAppTest asserts that the running test JVM really has every property
                // EclipseApp.SSHD_PROVIDER_SCOPING names, so these two drifting apart fails the
                // build rather than silently reinstating the shared registration.
                listOf("BC", "EdDSA").forEach { provider ->
                    test.systemProperty("org.apache.sshd.security.provider.$provider.useNamed", "false")
                }
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        // NullSafeMutableLiveData ships in lifecycle's bundled lint checks and throws
        // NoClassDefFoundError while loading its own UAST handler, which aborts
        // lintAnalyze/lintVitalAnalyze with an exception rather than a finding — so
        // abortOnError above cannot absorb it, and because lintVitalRelease is a
        // dependency of assembleRelease it blocks the release build outright.
        // The check only inspects LiveData fields and this app has none (Compose +
        // StateFlow throughout), so it can never report anything here.
        disable += "NullSafeMutableLiveData"
    }
}

// Both sshd-common and sshd-sftp ship META-INF/services/java.nio.file.spi.FileSystemProvider.
// On the host JVM (unit tests) the JDK's ServiceLoader then instantiates
// SftpFileSystemProvider, which fails under Robolectric's sandbox classloaders and
// breaks every java.nio.file call. Android is unaffected (the platform hardcodes
// its providers), so tests use copies of both jars without that registration.
val strippedSshd by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
dependencies { strippedSshd(libs.sshd.common); strippedSshd(libs.sshd.sftp) }

val stripSshdServices by tasks.registering {
    val outCommon = layout.buildDirectory.file("sshd-nosvc/sshd-common-nosvc.jar")
    val outSftp = layout.buildDirectory.file("sshd-nosvc/sshd-sftp-nosvc.jar")
    inputs.files(strippedSshd.incoming.artifacts.artifactFiles)
    outputs.file(outCommon)
    outputs.file(outSftp)
    doLast {
        // Local function: keeps the closure free of script-object references so
        // the task stays configuration-cache compatible.
        fun stripProviderRegistrations(src: File, dest: File) {
            dest.parentFile.mkdirs()
            ZipFile(src).use { zip ->
                ZipOutputStream(dest.outputStream().buffered()).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.name == "META-INF/services/java.nio.file.spi.FileSystemProvider") continue
                        zos.putNextEntry(ZipEntry(e.name))
                        zip.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        val sshdJars = inputs.files.filter { it.name.startsWith("sshd-") && it.name.endsWith(".jar") }
        stripProviderRegistrations(sshdJars.first { it.name.startsWith("sshd-common") }, outCommon.get().asFile)
        stripProviderRegistrations(sshdJars.first { it.name.startsWith("sshd-sftp") }, outSftp.get().asFile)
    }
}

configurations.configureEach {
    // Keep the full jars for the app; tests use the stripped copies above.
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "org.apache.sshd", module = "sshd-sftp")
        exclude(group = "org.apache.sshd", module = "sshd-common")
    }
}

/**
 * Writes the Room schema to `app/schemas` on every compilation.
 *
 * The database is at version 11 with hand-written migrations for 2 through 11, but `exportSchema` was
 * off, so none of those migrations could be verified and no future one could be written against a
 * known starting point: Room's own `MigrationTestHelper` and its "did the migration produce the schema
 * the entities describe" check both read these JSON files. Turning it on now records the shape that
 * shipped, which is the last moment it can be recorded for free - see the destructive-fallback note in
 * AppModule.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Debug-only, and deliberately kept with no @Preview in the tree: this is what makes the
    // Compose hierarchy visible to Android Studio's Layout Inspector. Its ui-tooling-preview
    // sibling used to be declared as `implementation`, which shipped the preview annotations in
    // the release APK for nothing; it arrives here transitively when a preview is added back.
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    // Nothing here imports androidx.navigation - there is no NavHost in the app, the single
    // Activity switches screens on its own state. It is in the graph regardless, because
    // hilt-navigation-compose (which is where `hiltViewModel` comes from) depends on it, and the
    // version *it* asks for is 2.5.1. Stated as a constraint rather than as an `implementation`:
    // the version needs pinning forward, but declaring a dependency this module never imports
    // would tell the next reader that some screen navigates through it.
    constraints {
        implementation(libs.androidx.navigation.compose)
    }
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.sshd.core)
    implementation(libs.sshd.common)
    implementation(libs.sshd.sftp)
    implementation(libs.eddsa)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    // Compose UI testing on the JVM. Robolectric can drive the real MainActivity and assert against
    // the semantics tree, so the navigation flows in NavigationRobolectricTest run in an ordinary
    // `testDebugUnitTest` — no emulator, and at sdk 35 rather than whatever image is installed. The
    // instrumentation copy in androidTest stays as the real-device signal.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    // Stripped copies of sshd-common + sshd-sftp for unit tests (see above).
    testImplementation(files(tasks.named("stripSshdServices")))

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Room migrations are also exercised against the device's real SQLite, not just Robolectric's.
    androidTestImplementation(libs.androidx.room.testing)
    // Releases the test activity when a Compose UI test finishes.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
