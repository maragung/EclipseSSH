/*
 * The PTY bridge for the local Linux userspace: forks a child with a
 * controlling terminal and execs it, leaving the master side for the JVM.
 *
 * The child is proot (nativeLibraryDir/libproot.so) with the rootfs as its
 * argument vector; this file deliberately knows nothing about that - it
 * spawns whatever it is handed, exactly like a terminal emulator would.
 *
 * Fork discipline: after fork() the child only calls async-signal-safe
 * functions (close, dup2, chdir, setsid, execve, _exit) plus the ioctl that
 * makes the slave its controlling terminal. All JVM work - string
 * conversion, the pty table - happens before fork() in the parent, so the
 * child never touches the VM. This is the same shape Termux's terminal uses.
 *
 * The table maps master fds to child pids because a bare master fd is all
 * the JVM needs for I/O; the pid only matters for reaping, which the JVM
 * asks for explicitly when its read loop reports end-of-stream.
 */

#define _GNU_SOURCE /* ptsname_r */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <pthread.h>

/* Hard cap on simultaneously open local terminals. The userspace opens at
 * most a handful; a runaway leak hits this ceiling and fails loudly instead
 * of exhausting the fd table. */
#define MAX_PTYS 16

struct pty_slot {
    int master;
    pid_t pid;
    int in_use;
};

static struct pty_slot ptys[MAX_PTYS];
static pthread_mutex_t ptys_lock = PTHREAD_MUTEX_INITIALIZER;

static struct pty_slot *slot_for_master(int master) {
    for (int i = 0; i < MAX_PTYS; i++) {
        if (ptys[i].in_use && ptys[i].master == master) return &ptys[i];
    }
    return NULL;
}

/* Resets every catchable signal to its default and unblocks all masks. ART
 * installs handlers and blocks signals on its own threads; dispositions set
 * to SIG_IGN survive execve(), so a plain fork without this would leave the
 * child ignoring signals proot and the rootfs programs rely on. */
static void reset_signals(void) {
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    for (int sig = 1; sig < 64; sig++) {
        sigaction(sig, &sa, NULL);
    }
}

/*
 * Class:     dev_eclipse_ssh_linux_LinuxPty
 * Method:    spawn
 * Signature: ([Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;II)I
 *
 * Opens a pty pair, forks, and execs argv[0] with envp and cwd in the child,
 * with the slave as the child's controlling terminal and stdio. Returns the
 * master fd; throws IOException if anything before the fork fails. If the
 * exec itself fails the child exits with status 127, which the caller sees
 * as an immediate end-of-stream plus wait() == 127.
 */
JNIEXPORT jint JNICALL
Java_dev_eclipse_ssh_linux_LinuxPty_spawn(
        JNIEnv *env, jclass clazz,
        jobjectArray jargv, jobjectArray jenvp, jstring jcwd,
        jint rows, jint cols) {
    (void) clazz;

    /* Convert every string before the fork: JNI calls are not
     * async-signal-safe, and the child must not touch the VM. */
    jsize argc = (*env)->GetArrayLength(env, jargv);
    jsize envc = (*env)->GetArrayLength(env, jenvp);
    if (argc < 1) {
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "argv must contain at least the program");
        return -1;
    }
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    char **child_env = calloc((size_t) envc + 1, sizeof(char *));
    const char *cwd = NULL;
    /* Declared and defaulted before any goto: the convert_failed path jumps
     * over the open() below, and the pending exception there makes the -1
     * moot - but an indeterminate value would be a real bug. */
    int master = -1;
    if (argv == NULL || child_env == NULL) {
        free(argv);
        free(child_env);
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "out of memory building argv/envp");
        return -1;
    }
    for (jsize i = 0; i < argc; i++) {
        argv[i] = (char *) (*env)->GetStringUTFChars(env, (*env)->GetObjectArrayElement(env, jargv, i), NULL);
        if (argv[i] == NULL) goto convert_failed;
    }
    for (jsize i = 0; i < envc; i++) {
        child_env[i] = (char *) (*env)->GetStringUTFChars(env, (*env)->GetObjectArrayElement(env, jenvp, i), NULL);
        if (child_env[i] == NULL) goto convert_failed;
    }
    const char *converted_cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    if (converted_cwd == NULL) goto convert_failed;
    cwd = converted_cwd;

    master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "open(/dev/ptmx) failed");
        goto out;
    }
    /* Window size set on the master applies to the pair; the child gets a
     * correct initial size before it ever reads the terminal. */
    struct winsize ws = { (unsigned short) rows, (unsigned short) cols, 0, 0 };
    ioctl(master, TIOCSWINSZ, &ws);

    char slave_path[64];
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        close(master);
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "ptsname_r failed");
        goto out;
    }

    pthread_mutex_lock(&ptys_lock);
    struct pty_slot *slot = NULL;
    for (int i = 0; i < MAX_PTYS; i++) {
        if (!ptys[i].in_use) { slot = &ptys[i]; break; }
    }
    if (slot == NULL) {
        pthread_mutex_unlock(&ptys_lock);
        close(master);
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "too many open local terminals");
        goto out;
    }
    slot->in_use = 1;
    slot->master = master;
    slot->pid = -1;
    pthread_mutex_unlock(&ptys_lock);

    pid_t pid = fork();
    if (pid < 0) {
        pthread_mutex_lock(&ptys_lock);
        slot->in_use = 0;
        pthread_mutex_unlock(&ptys_lock);
        close(master);
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "fork failed");
        goto out;
    }

    if (pid == 0) {
        /* Child. Async-signal-safe calls only. */
        reset_signals();
        setsid();
        int slave = open(slave_path, O_RDWR);
        if (slave < 0) _exit(126);
        /* Opening a tty after setsid() already makes it the controlling
         * terminal on Linux; the ioctl is belt-and-braces for any kernel
         * that disagrees. */
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        if (cwd[0] != '\0') {
            if (chdir(cwd) != 0) _exit(126);
        }
        execve(argv[0], argv, child_env);
        _exit(127);
    }

    pthread_mutex_lock(&ptys_lock);
    slot->pid = pid;
    pthread_mutex_unlock(&ptys_lock);

convert_failed:
    /* Reached both from a failed string conversion (partial arrays: the
     * calloc'd tails are NULL, so the release loops below stop exactly at
     * whatever was actually converted) and, via the fallthrough from the
     * success path, after a completed spawn. A pending exception from the
     * conversion failure is simply carried out with the returned -1. */
out:
    for (jsize i = 0; i < argc && argv[i] != NULL; i++) {
        (*env)->ReleaseStringUTFChars(env, (*env)->GetObjectArrayElement(env, jargv, i), argv[i]);
    }
    for (jsize i = 0; i < envc && child_env[i] != NULL; i++) {
        (*env)->ReleaseStringUTFChars(env, (*env)->GetObjectArrayElement(env, jenvp, i), child_env[i]);
    }
    if (cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    }
    free(argv);
    free(child_env);
    return master;
}
/*
 * Class:     dev_eclipse_ssh_linux_LinuxPty
 * Method:    read
 *
 * Blocking read from the master side, into a stack buffer that is then
 * copied into the Java array (at most 4096 bytes per call - a read is
 * allowed to return short; callers looping until their buffer is full is
 * normal stream semantics). Returns the byte count, or -1 on
 * end-of-stream: when the child side exits, reads fail with EIO (Linux pty
 * semantics), which is the signal the JVM read loop treats as EOF. EINTR is
 * retried internally.
 *
 * A pty whose slot is already freed was closed or reaped by another thread;
 * it reports end-of-stream rather than reading through a descriptor number
 * the process may have handed to something else. The liveness check shares
 * the table lock with close/awaitExit; the read itself runs without it,
 * because it blocks.
 */
JNIEXPORT jint JNICALL
Java_dev_eclipse_ssh_linux_LinuxPty_read(
        JNIEnv *env, jclass clazz, jint fd,
        jbyteArray buffer, jint offset, jint length) {
    (void) clazz;
    pthread_mutex_lock(&ptys_lock);
    int live = slot_for_master(fd) != NULL;
    pthread_mutex_unlock(&ptys_lock);
    if (!live) return -1;
    jbyte buf[4096];
    jint chunk = length < (jint) sizeof(buf) ? length : (jint) sizeof(buf);
    ssize_t n;
    do {
        n = read(fd, buf, (size_t) chunk);
    } while (n < 0 && errno == EINTR);
    if (n <= 0) return -1;
    (*env)->SetByteArrayRegion(env, buffer, offset, (jsize) n, buf);
    return (jint) n;
}

/*
 * Class:     dev_eclipse_ssh_linux_LinuxPty
 * Method:    write
 *
 * Full write (loops over partial writes). Returns the byte count written or
 * -1 on a dead pty (EIO/EPIPE: the child is gone) - including a pty whose
 * slot was already freed, which refuses rather than writing through a
 * possibly reused descriptor.
 */
JNIEXPORT jint JNICALL
Java_dev_eclipse_ssh_linux_LinuxPty_write(
        JNIEnv *env, jclass clazz, jint fd,
        jbyteArray buffer, jint offset, jint length) {
    (void) clazz;
    pthread_mutex_lock(&ptys_lock);
    int live = slot_for_master(fd) != NULL;
    pthread_mutex_unlock(&ptys_lock);
    if (!live) return -1;
    jbyte buf[4096];
    jint written = 0;
    while (written < length) {
        jint chunk = length - written;
        if (chunk > (jint) sizeof(buf)) chunk = (jint) sizeof(buf);
        (*env)->GetByteArrayRegion(env, buffer, offset + written, chunk, buf);
        ssize_t n = write(fd, buf, (size_t) chunk);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        written += (jsize) n;
    }
    return written;
}

/*
 * Class:     dev_eclipse_ssh_linux_LinuxPty
 * Method:    resize
 *
 * Updates the pty window size and delivers SIGWINCH to the child's
 * foreground process group, exactly like resizing a terminal window. A pty
 * whose slot is already freed is a no-op: the ioctl would land on whatever
 * the process has since reused the descriptor number for. The lookup runs
 * under the table lock like close/awaitExit's, so a resize racing a close
 * either sees the live slot or does nothing - never a half-torn-down one.
 */
JNIEXPORT void JNICALL
Java_dev_eclipse_ssh_linux_LinuxPty_resize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&ptys_lock);
    struct pty_slot *slot = slot_for_master(fd);
    pthread_mutex_unlock(&ptys_lock);
    if (slot == NULL) return;
    struct winsize ws = { (unsigned short) rows, (unsigned short) cols, 0, 0 };
    ioctl(fd, TIOCSWINSZ, &ws);
}

/*
 * Class:     dev_eclipse_ssh_linux_LinuxPty
 * Method:    awaitExit
 *
 * Blocks until the child exits, closes the master fd, frees the slot and
 * returns the wait status: the exit code (0-255), or 128+signal. Call after
 * the read loop saw end-of-stream - the kernel hands the child's exit to
 * the first waitpid, so there is no race between EOF and the reap.
 *
 * An fd with no live slot has already been closed (or is being closed by
 * another thread): it returns -1 without touching the descriptor, because
 * a close here would land on whatever the process has since reused the
 * number for.
 */
JNIEXPORT jint JNICALL
Java_dev_eclipse_ssh_linux_LinuxPty_awaitExit(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&ptys_lock);
    struct pty_slot *slot = slot_for_master(fd);
    pid_t pid = slot != NULL ? slot->pid : -1;
    if (slot != NULL) slot->in_use = 0;
    pthread_mutex_unlock(&ptys_lock);
    if (slot == NULL) return -1;

    int status = 0;
    if (pid > 0) {
        if (waitpid(pid, &status, 0) < 0) return -1;
    }
    close(fd);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/*
 * Class:     dev_eclipse_ssh_linux_LinuxPty
 * Method:    close
 *
 * Tears the pty down without waiting: closes the master (which delivers
 * SIGHUP to the child's session), reaps the child without blocking when it
 * is already gone, and frees the slot. Callers that want the exit status
 * must use awaitExit; after close the pid is deliberately reaped here so a
 * dropped terminal cannot leak a zombie.
 *
 * Idempotent: an fd with no live slot was already closed or reaped, and
 * closing it again would hit a descriptor the process may have reused.
 */
JNIEXPORT void JNICALL
Java_dev_eclipse_ssh_linux_LinuxPty_close(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&ptys_lock);
    struct pty_slot *slot = slot_for_master(fd);
    pid_t pid = slot != NULL ? slot->pid : -1;
    if (slot != NULL) slot->in_use = 0;
    pthread_mutex_unlock(&ptys_lock);
    if (slot == NULL) return;

    close(fd);
    if (pid > 0) {
        int status;
        waitpid(pid, &status, WNOHANG);
    }
}
