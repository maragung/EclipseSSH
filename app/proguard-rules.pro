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
