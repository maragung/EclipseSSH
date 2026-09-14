# ---------------------------------------------------------------------------
# Apache MINA SSHD
# SSHD wires almost everything through ServiceLoader + reflection, so R8 must
# not rename or drop its factory classes.
# ---------------------------------------------------------------------------
-keep class org.apache.sshd.** { *; }
-keepclassmembers class org.apache.sshd.** { *; }
-keep interface org.apache.sshd.** { *; }
-keepnames class org.apache.sshd.**
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.security.**
# EdDSA's JVM compatibility path references this optional JDK-internal type;
# Android uses the platform Ed25519 provider on supported API levels.
-dontwarn sun.security.x509.**
-dontwarn java.awt.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.tomcat.**
-dontwarn org.newsclub.net.unix.**
# Bouncy Castle (pulled in by vernacular-vnc for VNC authentication) has optional
# JDK-desktop/LDAP paths that reference classes Android does not ship.
-dontwarn javax.naming.**

# SSHD reads its own version info from a properties resource.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ---------------------------------------------------------------------------
# Room / Hilt / WorkManager
# ---------------------------------------------------------------------------
# Room entities and DAOs live in dev.eclipse.ssh.data.local, TransferDao and
# TransferEntity directly under dev.eclipse.ssh.data, and HostProfile (whose
# properties the instrumentation suite asserts) in dev.eclipse.ssh.data.model.
# The androidTest APK compiles against this build's R8 mapping: a member only
# the suite calls (TransferDao.count, HostProfile.getTags) is unreferenced by
# app code, so without this keep R8 strips it and the suite dies with
# NoSuchMethodError (release-test run 34843780520, both legs).
-keep class dev.eclipse.ssh.data.** { *; }
# Room.databaseBuilder resolves the generated "<Database>_Impl" class by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# Kotlin coroutines internals used via reflection by the debug agent.
-dontwarn kotlinx.coroutines.**

# AndroidJUnitRunner (and Espresso underneath it) resolves androidx.tracing
# through the shared instrumentation classloader: the androidTest APK's own R8
# pass sees the class on the app's classpath (it arrives transitively via Room)
# and treats it as "provided by the base APK", so it is not packaged into the
# test APK either. That makes this APK the only place it can exist - and the
# optimize file's -assumenosideeffects on android.os.Trace empties every
# app-side call site, so without this keep R8 strips the class as dead code and
# every instrumented run dies in AndroidJUnitRunner.onCreate with
# NoClassDefFoundError: androidx.tracing.Trace (release-test run 34832807524).
-keep class androidx.tracing.** { *; }

# The same mechanism takes the Kotlin stdlib and coroutines facade classes
# (run 34837380295: NoClassDefFoundError: kotlin.LazyKt in the runner). They
# are now kept as an exact per-class list in proguard-instrumentation.pro -
# derived from what the androidTest APK actually references - instead of the
# whole-namespace keep this used to be, which froze the entire stdlib and
# coroutines unshrunk and cost ~26 MB on the x86_64 APK. The remaining
# namespaces below are still broad pending the same narrowing.

# Run 34839975508 got past both keeps above and run 34843780520 got past
# this block's earlier form, which together pinned down the real mechanism
# (the "provided by the base APK" framing above is close but incomplete):
# the androidTest APK is compiled against this build's R8 mapping, so every
# reference the test code makes to a surviving class is rewritten to its
# obfuscated name and resolves fine. The suite only dies where R8 REMOVED
# something outright - a class nobody references becomes
# NoClassDefFoundError under its original name (androidx.tracing.Trace,
# kotlin.LazyKt, kotlinx.coroutines.JobKt), a member only the suite calls
# becomes NoSuchMethodError inside a mapped class
# (androidx.collection.mutableIntObjectMapOf, whose class survived renamed
# while the trivial facade method was inlined away everywhere in the app).
# The keeps below therefore cover every namespace the androidTest sources
# and the Compose test rules touch: room and lifecycle (direct test
# imports), activity plus its ComponentActivity superclass chain (core,
# savedstate - createAndroidComposeRule's bound), and collection.
# HostProfile and the transfer DAO are covered by the data rule above;
# kotlin, kotlinx.coroutines and androidx.compose moved to
# proguard-instrumentation.pro as exact per-class lists (stage 1 validated
# by release-test run 34863389211, both legs green, the x86_64 APK down
# 1.38 MB to 41,868,849 bytes; compose is stage 2, validated by the run
# after it). The next build's APK size report decides whether any of this
# gets narrowed.
-keep class androidx.room.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.collection.** { *; }
# Run 34847297300: javax.inject sits on the app classpath via Hilt, so
# androidx.test (whose runner and Espresso reference javax.inject.Provider)
# treats it as provided and does not package it - while this APK's own pass
# removes the interface outright (Hilt's generated code references it only in
# positions R8 can rewrite away). Every remaining test then died either on
# Provider itself or on androidx.test.espresso.Espresso, whose loading it
# breaks. MigrationInstrumentedTest passed for the first time in this run.
-keep class javax.inject.** { *; }

# Truth's error-prone annotations reference the javac model API, which Android
# does not ship; compile-time-only references, never evaluated on a device.
# Belt under test-proguard-rules.pro in case an androidTest R8 pass consumes
# this file instead.
-dontwarn javax.lang.model.**
