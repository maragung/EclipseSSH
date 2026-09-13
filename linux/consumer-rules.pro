# The PTY bridge (liblinuxpty.so) resolves LinuxPty's external functions by
# exact mangled name, and the JVM resolves the class to load the library from
# LinuxPty's own static initializer. AGP's default rules keep native method
# names, but the whole object is kept explicitly - the same posture as
# :freerdp's consumer rules - so R8 cannot rename, merge or strip anything the
# native side reaches, and a future default change cannot silently break the
# loadLibrary <-> JNI name pairing.
-keep class dev.eclipse.ssh.linux.LinuxPty { *; }
