# The PTY bridge: one C file, no dependencies beyond bionic (plus liblog,
# which carries the child-progress reports to logcat under "EclipseSSH").
# Built with the NDK toolchain via the same flags the freerdp bridge uses
# (see linux/build.gradle.kts for the invocation).
cmake_minimum_required(VERSION 3.22)
project(linuxpty C)

add_library(linuxpty SHARED linuxpty.c)

target_link_libraries(linuxpty log)

target_compile_options(linuxpty PRIVATE -Wall -Wextra -O2)
