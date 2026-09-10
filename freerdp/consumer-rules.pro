# The native bridge (libfreerdp-android.so) resolves this class reflectively -
# JNI_OnLoad instantiates it via FindClass + NewObject, and every C->Java event
# is dispatched to a *static* method looked up by exact name and signature with
# GetStaticMethodID. R8 must not rename, merge or strip any of it. Keeping the
# whole class (not selected members) is deliberate: the Java side has no static
# callers for most of these members, so no keep rule narrower than {*} would be
# honest about what the JNI bridge can reach.
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }

# The listener interfaces are the dispatch targets of those static callbacks;
# their method names are not JNI-reachable, but keeping them whole stops R8
# from merging them into single implementations in ways the callback dispatch
# has not been verified against.
-keep interface com.freerdp.freerdpcore.services.LibFreeRDP$EventListener { *; }
-keep interface com.freerdp.freerdpcore.services.LibFreeRDP$UIEventListener { *; }
