# Rules for the androidTest APK's own R8 pass (minifyReleaseAndroidTestWithR8,
# which becomes active once testBuildType = "release"). That pass does not read
# the app's proguard-rules.pro: the test classpath carries Truth's error-prone
# annotations, whose values reference the javac model API - compile-time-only
# classes that do not exist on Android. References like that are warnings-grade
# noise here, not a runtime risk: the annotations are never evaluated on a
# device.
-dontwarn javax.lang.model.**
