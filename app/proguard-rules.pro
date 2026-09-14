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
# Room entities and DAOs live in dev.eclipse.ssh.data.local (the previous rule
# pointed at a ".db" sub-package that does not exist and matched nothing).
-keep class dev.eclipse.ssh.data.local.** { *; }
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

# Truth's error-prone annotations reference the javac model API, which Android
# does not ship; compile-time-only references, never evaluated on a device.
# Belt under test-proguard-rules.pro in case an androidTest R8 pass consumes
# this file instead.
-dontwarn javax.lang.model.**
