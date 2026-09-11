plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so com.android.library's version is known when :freerdp
    // requests it: the library and application plugins ship in the same
    // artifact, and a subproject request for an id whose implementation is
    // already on the classpath (via the application id) with an untracked
    // version is rejected with "already on the classpath with an unknown
    // version".
    alias(libs.plugins.android.library) apply false
    // No org.jetbrains.kotlin.android declaration: AGP 9 ships built-in Kotlin
    // support, and the KGP android plugin must no longer be applied (or declared
    // for the classpath) anywhere in this build.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
