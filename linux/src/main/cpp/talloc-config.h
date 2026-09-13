/* Hand-written configuration for building talloc with the Android NDK.
 *
 * The waf build talloc ships with cannot cross-configure for bionic (it wants
 * to execute target test programs), and libtalloc needs exactly one source
 * file (talloc.c - it references no replace.c symbols, verified by nm on a
 * native waf build). This file is the minimal feature set that talloc.c and
 * lib/replace/replace.h need to parse for a modern Linux libc like bionic.
 *
 * Only features bionic provably has are defined: replace.h guards every
 * optional include and fallback typedef behind a HAVE_ check, and talloc.c's
 * own libc needs are plain ANSI, so an omitted define costs nothing while a
 * wrong one would be a link error caught at build time. No SIZEOF_* macros
 * are defined because nothing in talloc.c / talloc.h / replace.h reads them.
 *
 * Validated end to end on a host build (talloc compiled with this header,
 * archived, statically linked into proot, proot running an Ubuntu Base
 * rootfs) before it entered CI.
 */
#ifndef _TALLOC_ANDROID_CONFIG_H_
#define _TALLOC_ANDROID_CONFIG_H_

/* replace.h includes this file before any system header, so the
 * STDC_WANT_LIB_EXT1__ request must ride along with the config rather than
 * the command line (replace.h errors out without it).
 */
#define __STDC_WANT_LIB_EXT1__ 1

/* replace.h's dlsym(RTLD_DEFAULT, ...) probe needs the GNU dlfcn surface on
 * glibc hosts; bionic always provides RTLD_DEFAULT and ignores the macro. */
#define _GNU_SOURCE 1

/* Standard headers bionic provides. */
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_CTYPE_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_ASSERT_H 1
#define HAVE_LIMITS_H 1
#define HAVE_FLOAT_H 1
#define HAVE_ERRNO_H 1
#define HAVE_UNISTD_H 1
#define HAVE_FCNTL_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_SYS_WAIT_H 1
#define HAVE_SYS_UIO_H 1
#define HAVE_DIRENT_H 1
#define HAVE_DLFCN_H 1
#define HAVE_MALLOC_H 1
#define HAVE_SIGNAL_H 1
#define HAVE_ENDIAN_H 1

/* Types stdint.h / stddef.h / sys/types.h already provide; without these
 * defines replace.h re-declares them as `long long` and clashes with the
 * real ones. */
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1

/* Functions bionic provides that talloc's code paths test for. Without
 * HAVE_C99_VSNPRINTF the entire printf family would remap to undefined
 * rep_* stubs (replace.h wires fprintf to rep_fprintf etc.); without
 * HAVE_VA_COPY talloc.c would redefine va_copy as a struct assignment,
 * which does not compile. */
#define HAVE_C99_VSNPRINTF 1
#define HAVE_MEMMOVE 1
#define HAVE_VA_COPY 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_DECL_ASPRINTF 1
#define HAVE_DECL_VASPRINTF 1
#define HAVE_DECL_SNPRINTF 1
#define HAVE_DECL_VSNPRINTF 1
#define HAVE_DECL_EWOULDBLOCK 1
#define HAVE_DECL_ENVIRON 1
#define HAVE_ENVIRON_DECL 1
#define HAVE_STRTOK_R 1
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_GETPAGESIZE 1
#define HAVE_MMAP 1
#define HAVE_MUNMAP 1
#define HAVE_WAITPID 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_RANDOM 1
#define HAVE_SRANDOM 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_REALPATH 1
#define HAVE_FDATASYNC 1
#define HAVE_DUP2 1
#define HAVE_CHOWN 1
#define HAVE_GETPWUID 1
#define HAVE_USLEEP 1

/* Attributes clang understands. */
#define HAVE___ATTRIBUTE__ 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE_FALLTHROUGH_ATTRIBUTE 1
#define HAVE_UNUSED_ATTRIBUTE 1

/* replace.h errors out without a real boolean type once stdbool.h is in. */
#define HAVE_BOOL 1
#define BOOL_DEFINED 1

#define STDC_HEADERS 1
#define TIME_WITH_SYS_TIME 1
#define HAVE_LARGEFILE 1
#define SHLIBEXT "so"
#define LINUX 1
#define RETSIGTYPE void

/* The waf build passes these as -D flags (wscript:41-43); keeping them in
 * the config means the NDK compile line stays minimal. */
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2

#endif /* _TALLOC_ANDROID_CONFIG_H_ */
