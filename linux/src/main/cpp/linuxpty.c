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

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include <pthread.h>

/* The logcat tag the Kotlin side's UserspaceDiagnostics already owns; the
 * E2E pipeline greps this tag, so the pty bridge reports under it too. */
#define PTY_LOG_TAG "EclipseSSH"

/* Hard cap on simultaneously open local terminals. The userspace opens at
 * most a handful; a runaway leak hits this ceiling and fails loudly instead
 * of exhausting the fd table. */
#define MAX_PTYS 16

/* Child progress reporting, through a pipe whose write end is CLOEXEC:
 * the child writes one byte per stage it passes, and a fail marker
 * (0x80 | stage) plus the errno when a stage fails. The write end closing
 * (EOF at the parent) without a fail marker means execve() happened - the
 * one fact nothing else can prove, because a child that dies before
 * open(slave) produces no hangup, and one that dies by SIG_DFL signal
 * leaves no tombstone. That silence was three E2E runs of "the proot
 * child vanished": this pipe turns it into a named stage and errno. */
#define PTY_STAGE_SIGMASK 1
#define PTY_STAGE_SETSID 2
#define PTY_STAGE_OPEN_SLAVE 3
#define PTY_STAGE_CTTY 4
#define PTY_STAGE_STDIO 5
#define PTY_STAGE_CHDIR 6
#define PTY_STAGE_EXECVE 7
/* Reported when the TIOCGPTPEER route failed and the child fell back to
 * opening the slave by path - the pair of facts (this plus which errno the
 * open itself then produced) is the evidence for debugging devpts layouts. */
#define PTY_STAGE_PEER_FALLBACK 8

/* How long the parent waits for the child's verdict. A healthy child
 * reaches execve in single-digit milliseconds; only a wedged child costs
 * the full budget, and a wedged child means a failed command anyway. */
#define PTY_DIAG_BUDGET_MS 750

/* Peer-of-master slave acquisition, Linux 4.13+. */
#ifndef TIOCGPTPEER
#define TIOCGPTPEER 0x5414
#endif

static void child_stage(int fd, int stage) {
    uint8_t code = (uint8_t) stage;
    ssize_t n = write(fd, &code, 1);
    (void) n;
}

static void child_stage_failed(int fd, int stage, int error) {
    uint8_t code = (uint8_t) (0x80 | stage);
    int32_t reported = error;
    ssize_t n = write(fd, &code, 1);
    n = write(fd, &reported, sizeof(reported));
    (void) n;
}

/* An informational stage report that carries an errno - used by
 * PTY_STAGE_PEER_FALLBACK so the parent can log WHY the ioctl route was
 * skipped, not just that it was. The parser knows this one stage is followed
 * by an int32 payload. */
static void child_stage_errno(int fd, int stage, int error) {
    uint8_t code = (uint8_t) stage;
    int32_t reported = error;
    ssize_t n = write(fd, &code, 1);
    n = write(fd, &reported, sizeof(reported));
    (void) n;
}

static const char *stage_name(int stage) {
    switch (stage) {
        case PTY_STAGE_SIGMASK: return "unblocking the signal mask";
        case PTY_STAGE_SETSID: return "setsid";
        case PTY_STAGE_OPEN_SLAVE: return "opening the slave pty";
        case PTY_STAGE_CTTY: return "claiming the controlling terminal";
        case PTY_STAGE_STDIO: return "redirecting stdio";
        case PTY_STAGE_CHDIR: return "chdir";
        case PTY_STAGE_PEER_FALLBACK: return "the slave-path fallback";
        case PTY_STAGE_EXECVE: return "execve";
        default: return "an unnamed stage";
    }
}

/* Reads the child's progress report, logs the verdict, and returns the
 * failing stage (0 when the child reported no failure - the errno lands in
 * *failure_errno when there was one). Bounded by [PTY_DIAG_BUDGET_MS]: EOF
 * means the child exec'd (or exited after reporting a failure); a timeout
 * with the pipe still open means the child is wedged - and the last stage
 * it reported names where. */
static int report_child_progress(int fd, const char *program, int *failure_errno) {
    uint8_t buf[64];
    size_t used = 0;
    int eof = 0;
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    while (used < sizeof(buf)) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t elapsed_ms = (now.tv_sec - start.tv_sec) * 1000 +
                             (now.tv_nsec - start.tv_nsec) / 1000000;
        int remaining_ms = (int) (PTY_DIAG_BUDGET_MS - elapsed_ms);
        if (remaining_ms <= 0) break;
        struct pollfd pfd = { .fd = fd, .events = POLLIN, .revents = 0 };
        int ready = poll(&pfd, 1, remaining_ms);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ready == 0) break;
        ssize_t n = read(fd, buf + used, sizeof(buf) - used);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) {
            eof = 1;
            break;
        }
        used += (size_t) n;
    }
    /* Parse: stage bytes in order, possibly ending in a fail marker. The
     * PEER_FALLBACK stage byte is followed by an int32 - the errno the
     * TIOCGPTPEER ioctl itself returned - so that even a successful fallback
     * leaves the ioctl's own failure reason in the log. */
    int last_stage = 0;
    int failed_stage = 0;
    int failure_errno_ = 0;
    int peer_errno = 0;
    for (size_t i = 0; i < used; i++) {
        if (buf[i] >= 0x80) {
            failed_stage = buf[i] & 0x7f;
            if (i + 1 + sizeof(int32_t) <= used) {
                memcpy(&failure_errno_, buf + i + 1, sizeof(int32_t));
            }
            break;
        }
        if (buf[i] == PTY_STAGE_PEER_FALLBACK) {
            if (i + 1 + sizeof(int32_t) <= used) {
                memcpy(&peer_errno, buf + i + 1, sizeof(int32_t));
                i += sizeof(int32_t);
            }
            last_stage = buf[i];
            continue;
        }
        last_stage = buf[i];
    }
    if (peer_errno != 0) {
        __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                            "pty child's TIOCGPTPEER failed with errno %d; "
                            "it fell back to opening the slave by path",
                            peer_errno);
    }
    if (failed_stage != 0) {
        __android_log_print(ANDROID_LOG_WARN, PTY_LOG_TAG,
                            "pty child failed at %s (errno %d) launching %s",
                            stage_name(failed_stage), failure_errno_, program);
        *failure_errno = failure_errno_;
        return failed_stage;
    }
    if (last_stage >= PTY_STAGE_EXECVE) {
        /* The write end closed after the pre-execve report: execve happened.
         * Errors past this point are the exec'd program's to report. */
        __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                            "pty child reached execve: %s", program);
        return 0;
    }
    if (!eof) {
        __android_log_print(ANDROID_LOG_WARN, PTY_LOG_TAG,
                            "pty child stalled before execve at %s, launching %s",
                            last_stage == 0 ? "its first instruction" : stage_name(last_stage),
                            program);
        return 0;
    }
    /* EOF with stages but no execve report and no failure record: the child
     * died between stages - a signal, since every _exit path reports. */
    __android_log_print(ANDROID_LOG_WARN, PTY_LOG_TAG,
                        "pty child died silently after %s, launching %s (killed by a signal?)",
                        last_stage == 0 ? "fork" : stage_name(last_stage), program);
    return 0;
}

struct pty_slot {
    int master;
    pid_t pid;
    int in_use;
};

static struct pty_slot ptys[MAX_PTYS];
static pthread_mutex_t ptys_lock = PTHREAD_MUTEX_INITIALIZER;

/* One-per-process dump of the device-side pty landscape: kernel release, what
 * /dev/ptmx actually is (symlink target or major:minor+mode), the mode of the
 * devpts-internal ptmx, and every devpts line of this process's mountinfo.
 * The API 35 emulator failed BOTH slave-acquisition routes with EIO while
 * serving TIOCGPTN on the same master; every hypothesis that is left needs
 * these facts to be checkable. Logged once so a spawn loop stays readable. */
static void log_devpts_layout_once(void) {
    static volatile int done = 0;
    if (done) return;
    done = 1;
    struct utsname uts;
    if (uname(&uts) == 0) {
        __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                            "pty landscape: kernel %s (%s)", uts.release, uts.machine);
    }
    char link_target[128];
    ssize_t n = readlink("/dev/ptmx", link_target, sizeof(link_target) - 1);
    if (n >= 0) {
        link_target[n] = '\0';
        __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                            "pty landscape: /dev/ptmx is a symlink to %s", link_target);
    } else {
        struct stat st;
        if (stat("/dev/ptmx", &st) == 0) {
            __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                                "pty landscape: /dev/ptmx is device %u:%u mode %o",
                                major(st.st_rdev), minor(st.st_rdev), st.st_mode & 0777);
        }
    }
    struct stat st;
    if (stat("/dev/pts/ptmx", &st) == 0) {
        __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                            "pty landscape: /dev/pts/ptmx is device %u:%u mode %o",
                            major(st.st_rdev), minor(st.st_rdev), st.st_mode & 0777);
    }
    FILE *f = fopen("/proc/self/mountinfo", "r");
    if (f != NULL) {
        char line[1024];
        while (fgets(line, sizeof(line), f) != NULL) {
            if (strstr(line, "devpts") == NULL) continue;
            size_t len = strlen(line);
            while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
                line[--len] = '\0';
            }
            __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                                "pty landscape: mountinfo: %s", line);
        }
        fclose(f);
    }
}

static struct pty_slot *slot_for_master(int master) {
    for (int i = 0; i < MAX_PTYS; i++) {
        if (ptys[i].in_use && ptys[i].master == master) return &ptys[i];
    }
    return NULL;
}

/* How long one lap of the read loop may park before re-checking whether the
 * pty is still alive. Purely an upper bound on teardown-detection latency;
 * data still arrives the moment poll() sees it. */
#define PTY_READ_POLL_MS 250

/* Un-blocks every signal the forking thread had blocked, through the RAW
 * syscall. Both libc signal entry points - sigaction(3) and sigprocmask(3) -
 * are interposed by ART's libsigchain, and the interposer is not fork-safe:
 * it takes its own locks and unwinds stacks, and a fork inherits only the
 * calling thread, so any lock another thread held at fork() is held forever.
 * In the half-forked child that is where a spawn wedges or dies - and the
 * death is silent, because the disposition change under discussion (SIGSEGV
 * to SIG_DFL) removes the very handler that would have reported it. That was
 * the 2026-09 emulator install hang: every first proot spawn stopped inside
 * the interposer before open(slave) or execve ever ran, the master side
 * never saw a hangup, and the install sat on "Configuring DNS" until the
 * harness's own timeout. The raw syscall enters no interposer, and needs no
 * ABI guesswork: rt_sigprocmask's only nonstandard argument is the sigset
 * size, which for the kernel is always 8 bytes (64 signals, one long).
 *
 * Nothing else is reset because nothing else needs it: execve(2) itself
 * resets every *caught* handler to SIG_DFL, and only dispositions set to
 * SIG_IGN (plus the blocked mask) can survive an exec - ART installs
 * handlers, never SIG_IGN, for the signals it manages. The mask is the one
 * thing worth fixing, and this is the only fork-safe way to do it. */
static void unblock_all_signals(void) {
    unsigned long empty_mask = 0;
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &empty_mask, NULL,
            sizeof(empty_mask));
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
    int used_pts_ptmx = 0;
    int pts_ptmx_errno = 0;
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

    /* Open the master INSIDE the devpts instance the child will open slaves
     * from. /dev/ptmx can be the legacy misc device, tied to the kernel's
     * initial devpts mount - a different instance than the one at /dev/pts.
     * On the API 35 emulator that is exactly the split: opening /dev/ptmx
     * succeeds, ptsname_r dutifully reports /dev/pts/N, and the child's
     * open() of that path returns EIO because in ITS instance that slave
     * belongs to no master (pty_open's !tty->link). Every proot spawn died
     * there, silently - the child exits before the slave is ever opened, so
     * the master never hangs up and the JVM read parks until its timeout.
     * /dev/pts/ptmx is the ptmx of the instance itself, which cannot be
     * mismatched; the /dev/ptmx fallback keeps real devices that only have
     * the legacy node working exactly as before. */
    master = open("/dev/pts/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    used_pts_ptmx = master >= 0;
    if (master < 0) {
        pts_ptmx_errno = errno;
        master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    }
    if (master < 0) {
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "open(ptmx) failed");
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

    /* One evidence line per spawn: which ptmx this device answered, why the
     * instance-local one was skipped when it was, and whether the allocated
     * slave node is even present in the /dev/pts the child would open it
     * from. The API 35 emulator produced open(slave) EIO with no other clue
     * anywhere; these facts discriminate between the "master and slave live
     * in different devpts instances" explanation (node absent) and
     * flag-state explanations (node present). */
    {
        struct stat st;
        int slave_present = stat(slave_path, &st) == 0;
        if (used_pts_ptmx) {
            __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                                "pty master via /dev/pts/ptmx, slave %s %s",
                                slave_path,
                                slave_present ? "present in /dev/pts"
                                              : "ABSENT from /dev/pts (devpts instances disagree?)");
        } else {
            __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                                "pty master via /dev/ptmx (legacy; open(/dev/pts/ptmx) errno %d), "
                                "slave %s %s",
                                pts_ptmx_errno, slave_path,
                                slave_present ? "present in /dev/pts"
                                              : "ABSENT from /dev/pts (devpts instances disagree?)");
        }
    }

    log_devpts_layout_once();

    /* Acquire the slave HERE, in the parent, before the fork. On the API 35
     * emulator every in-child route failed with EIO - TIOCGPTPEER on the
     * master AND open(slave_path) - while the same master serves TIOCGPTN and
     * the direct-exec A/B runs proot fine. Probing both routes from the
     * parent splits the world in two: if a route works here the child simply
     * inherits the fd (the controlling terminal is claimed with TIOCSCTTY
     * after setsid, so acquiring the slave pre-fork is safe), and the whole
     * child context - fork, signal mask, session - is out of the equation;
     * if a route fails here too, its errno lands in the log with no fork in
     * the picture at all. O_NOCTTY because THIS process must not acquire a
     * controlling terminal even if it somehow is a session leader. */
    int slave_fd = ioctl(master, TIOCGPTPEER, O_NOCTTY);
    int parent_peer_errno = slave_fd >= 0 ? 0 : errno;
    if (slave_fd < 0) {
        slave_fd = open(slave_path, O_RDWR | O_NOCTTY);
    }
    __android_log_print(ANDROID_LOG_INFO, PTY_LOG_TAG,
                        "pty parent slave probe: TIOCGPTPEER errno %d, open(slave) errno %d, %s",
                        parent_peer_errno,
                        (slave_fd >= 0 || parent_peer_errno == 0) ? 0 : errno,
                        slave_fd >= 0 ? "the parent hands the slave fd to the child"
                                      : "the child must acquire one itself");

    pthread_mutex_lock(&ptys_lock);
    struct pty_slot *slot = NULL;
    for (int i = 0; i < MAX_PTYS; i++) {
        if (!ptys[i].in_use) { slot = &ptys[i]; break; }
    }
    if (slot == NULL) {
        pthread_mutex_unlock(&ptys_lock);
        if (slave_fd >= 0) close(slave_fd);
        close(master);
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "too many open local terminals");
        goto out;
    }
    slot->in_use = 1;
    slot->master = master;
    slot->pid = -1;
    pthread_mutex_unlock(&ptys_lock);

    /* The child's progress pipe, created last so no earlier failure path has
     * to clean it up. O_CLOEXEC on both ends is the whole trick: the child's
     * write end closes itself the moment execve succeeds, so EOF at this side
     * IS the proof the exec happened. Degrades to yesterday's silence if the
     * pipe cannot be created - a missing diagnostic never breaks a spawn. */
    int diag[2];
    int have_diag = pipe2(diag, O_CLOEXEC) == 0;

    pid_t pid = fork();
    if (pid < 0) {
        if (have_diag) {
            close(diag[0]);
            close(diag[1]);
        }
        pthread_mutex_lock(&ptys_lock);
        slot->in_use = 0;
        pthread_mutex_unlock(&ptys_lock);
        if (slave_fd >= 0) close(slave_fd);
        close(master);
        jclass e = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, e, "fork failed");
        goto out;
    }

    if (pid == 0) {
        /* Child. Async-signal-safe calls only - write(2) included, which is
         * what the stage reports below use. */
        if (have_diag) close(diag[0]);
        int diag_fd = have_diag ? diag[1] : -1;
        unblock_all_signals();
        child_stage(diag_fd, PTY_STAGE_SIGMASK);
        setsid();
        child_stage(diag_fd, PTY_STAGE_SETSID);
        /* Preferred: the slave fd the parent already acquired (see the probe
         * before the fork). Inheriting it sidesteps every acquisition route
         * that failed on the API 35 emulator - the child only claims the
         * controlling terminal below. */
        int slave = slave_fd;
        if (slave < 0) {
            /* Ask the kernel for the peer of the master this child already
             * holds, instead of resolving the /dev/pts path. The path is a
             * guess about which devpts instance the master belongs to - on
             * the API 35 emulator that guess fails (the slave open returns
             * EIO, pty_open's !tty->link), while TIOCGPTPEER cannot be wrong
             * about instances: it is the master fd's own peer, handed over by
             * the kernel. The flags argument admits ONLY O_CLOEXEC and
             * O_NONBLOCK - the kernel returns EINVAL for anything else,
             * including the O_RDWR a plain open would use (that exact mistake
             * cost one E2E run). Zero means neither: the returned fd may
             * become this fresh session's controlling terminal, which is
             * exactly what open(slave) after setsid did. */
            slave = ioctl(master, TIOCGPTPEER, 0);
            if (slave < 0) {
                child_stage_errno(diag_fd, PTY_STAGE_PEER_FALLBACK, errno);
                slave = open(slave_path, O_RDWR);
            }
            if (slave < 0) {
                child_stage_failed(diag_fd, PTY_STAGE_OPEN_SLAVE, errno);
                _exit(126);
            }
        }
        child_stage(diag_fd, PTY_STAGE_OPEN_SLAVE);
        /* Both slave routes already claim the controlling terminal for a
         * session leader without one - TIOCGPTPEER by not passing O_NOCTTY,
         * open-after-setsid by tty open semantics. The ioctl is belt-and-
         * braces for any kernel that disagrees. */
        ioctl(slave, TIOCSCTTY, 0);
        child_stage(diag_fd, PTY_STAGE_CTTY);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        child_stage(diag_fd, PTY_STAGE_STDIO);
        if (cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                child_stage_failed(diag_fd, PTY_STAGE_CHDIR, errno);
                _exit(126);
            }
        }
        child_stage(diag_fd, PTY_STAGE_CHDIR);
        child_stage(diag_fd, PTY_STAGE_EXECVE);
        execve(argv[0], argv, child_env);
        child_stage_failed(diag_fd, PTY_STAGE_EXECVE, errno);
        _exit(127);
    }

    /* Parent. The child holds its own inherited copy of the slave (or dup2'd
     * one it acquired itself); this copy must go, or the pair would never
     * hang up on the master side when the child exits - the reader would see
     * neither EOF nor EIO, exactly the old install-hang shape. */
    if (slave_fd >= 0) {
        close(slave_fd);
    }

    if (have_diag) {
        close(diag[1]);
        const char *slash = strrchr(argv[0], '/');
        int failure_errno = 0;
        int failed_stage = report_child_progress(
                diag[0], slash != NULL ? slash + 1 : argv[0], &failure_errno);
        close(diag[0]);
        if (failed_stage != 0) {
            /* The child named the stage it died at and is gone. A dead child
             * produces no output and no hangup, so waiting would only burn
             * the caller's whole timeout - fail now, typed, instead. The
             * child _exits immediately after writing its fail record; reap
             * it briefly so it does not linger as a zombie. */
            for (int i = 0; i < 50; i++) {
                int status;
                if (waitpid(pid, &status, WNOHANG) == pid) break;
                struct timespec nap = { 0, 2 * 1000 * 1000 };
                nanosleep(&nap, NULL);
            }
            pthread_mutex_lock(&ptys_lock);
            slot->in_use = 0;
            pthread_mutex_unlock(&ptys_lock);
            close(master);
            master = -1;
            char message[128];
            snprintf(message, sizeof(message),
                     "the pty child failed at %s (errno %d)",
                     stage_name(failed_stage), failure_errno);
            jclass e = (*env)->FindClass(env, "java/io/IOException");
            (*env)->ThrowNew(env, e, message);
            goto out;
        }
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
 * Read from the master side, into a stack buffer that is then copied into
 * the Java array (at most 4096 bytes per call - a read is allowed to return
 * short; callers looping until their buffer is full is normal stream
 * semantics). Returns the byte count, or -1 on end-of-stream: when the child
 * side exits, reads fail with EIO (Linux pty semantics), which is the signal
 * the JVM read loop treats as EOF. EINTR is retried internally.
 *
 * The wait runs through poll() with a timeout, not a bare blocking read,
 * because a blocked read is un-interruptible by anything but the pty itself:
 * close() on the descriptor does not wake it, and a child that died before
 * ever opening the slave generates no master-side hangup at all - both
 * shapes left the reader parked forever (the 2026-09 install hang). Each
 * poll timeout re-checks the slot, so a teardown by any thread becomes an
 * end-of-stream within one interval, whatever state the pty pair is in.
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
    for (;;) {
        struct pollfd pfd = { .fd = fd, .events = POLLIN, .revents = 0 };
        int ready = poll(&pfd, 1, PTY_READ_POLL_MS);
        if (ready < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (ready == 0) {
            /* A full interval with nothing readable. The pty may have been
             * torn down by another thread while this read was parked - the
             * re-check below is the only thing that turns that teardown into
             * an end-of-stream for a reader the pty itself cannot wake. */
            pthread_mutex_lock(&ptys_lock);
            live = slot_for_master(fd) != NULL;
            pthread_mutex_unlock(&ptys_lock);
            if (!live) return -1;
            continue;
        }
        if (pfd.revents & POLLNVAL) {
            /* The descriptor number is closed - only our own teardown closes
             * it. Reading through the number now could hit a descriptor
             * another thread has since reused. */
            return -1;
        }
        /* Data or hangup readable; a hangup reads as the EIO below, the
         * documented end-of-stream for a pty master. */
        n = read(fd, buf, (size_t) chunk);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        break;
    }
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
