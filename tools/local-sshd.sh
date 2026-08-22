#!/usr/bin/env bash
# An isolated OpenSSH server for the automated tests, and nothing else.
#
# Why a second SSH server when the suite already has one: the in-JVM server every other test uses is
# Apache MINA, the same library the app's client is built from, so both ends agree about extension
# negotiation, global requests and unsolicited messages by construction. A real `sshd` agrees about
# none of that for free - it sends `ext-info` and `hostkeys-00@openssh.com` unasked, answers an
# unknown global request with SSH_MSG_REQUEST_FAILURE, runs a login shell through a pty, and offers
# `sftp-server` as a subsystem process. Interop bugs live in that gap and cannot be seen from a
# MINA-to-MINA test. See RealOpenSshInteropRobolectricTest.
#
# Everything it uses is created here and thrown away with `stop`: its own host key, its own
# authorized_keys, its own throwaway client key pair, its own config. It reads nothing from ~/.ssh,
# writes nothing there, and binds to the loopback only, so it is unreachable from off the machine and
# shares no credential with anything real. Runs as the invoking user and logs that user in - no root,
# no PAM, no password authentication at all.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.tmp-build/sshd-test"
PORT="${ECLIPSE_TEST_SSHD_PORT:-22022}"
SFTP_SERVER="${ECLIPSE_SFTP_SERVER:-}"

find_sftp_server() {
    for candidate in /usr/lib/openssh/sftp-server /usr/libexec/openssh/sftp-server \
                     /usr/libexec/sftp-server /usr/lib/ssh/sftp-server; do
        [ -x "$candidate" ] && { printf '%s' "$candidate"; return 0; }
    done
    return 1
}

start() {
    command -v sshd >/dev/null 2>&1 || [ -x /usr/sbin/sshd ] || {
        echo "no sshd on this machine; the interop test will skip" >&2
        exit 0
    }
    local sshd; sshd="$(command -v sshd || echo /usr/sbin/sshd)"
    [ -n "$SFTP_SERVER" ] || SFTP_SERVER="$(find_sftp_server)" || {
        echo "no sftp-server binary found; set ECLIPSE_SFTP_SERVER" >&2
        exit 1
    }
    stop
    mkdir -p "$DIR"
    chmod 700 "$DIR"
    ssh-keygen -t ed25519 -f "$DIR/host_ed25519" -N '' -q -C eclipse-test-host
    ssh-keygen -t ed25519 -f "$DIR/client_ed25519" -N '' -q -C eclipse-test-client
    cp "$DIR/client_ed25519.pub" "$DIR/authorized_keys"
    chmod 600 "$DIR/authorized_keys" "$DIR/host_ed25519" "$DIR/client_ed25519"
    printf 'Eclipse local test banner\n' > "$DIR/banner.txt"
    # StrictModes off because the sandbox lives under the build directory rather than in a home
    # directory sshd would insist on owning; PAM and password auth off because both need root and
    # neither is what the test exercises.
    cat > "$DIR/sshd_config" <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $DIR/host_ed25519
AuthorizedKeysFile $DIR/authorized_keys
PidFile $DIR/sshd.pid
UsePAM no
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitUserEnvironment no
PrintMotd yes
PrintLastLog no
Banner $DIR/banner.txt
Subsystem sftp $SFTP_SERVER
LogLevel VERBOSE
ClientAliveInterval 0
TCPKeepAlive yes
EOF
    "$sshd" -t -f "$DIR/sshd_config"
    nohup nice -n 10 "$sshd" -D -e -f "$DIR/sshd_config" > "$DIR/sshd.log" 2>&1 &
    printf '%s\n' "$PORT" > "$DIR/port"
    id -un > "$DIR/user"
    for _ in $(seq 1 50); do
        if ssh -i "$DIR/client_ed25519" -p "$PORT" -o StrictHostKeyChecking=no \
               -o UserKnownHostsFile="$DIR/known_hosts" -o BatchMode=yes \
               -o ConnectTimeout=2 "$(id -un)@127.0.0.1" true 2>/dev/null; then
            echo "local sshd ready on 127.0.0.1:$PORT as $(id -un)"
            return 0
        fi
        sleep 0.2
    done
    echo "local sshd did not come up; see $DIR/sshd.log" >&2
    tail -20 "$DIR/sshd.log" >&2 || true
    exit 1
}

stop() {
    if [ -f "$DIR/sshd.pid" ]; then
        kill "$(cat "$DIR/sshd.pid")" 2>/dev/null || true
        rm -f "$DIR/sshd.pid"
    fi
    # Only this sandbox's server: matched on its own config path, never on sshd generally, so running
    # this on a machine with a real sshd cannot touch it.
    pkill -f "sshd -D -e -f $DIR/sshd_config" 2>/dev/null || true
}

case "${1:-start}" in
    start) start ;;
    stop) stop; echo "local sshd stopped" ;;
    clean) stop; rm -rf "$DIR"; echo "local sshd sandbox removed" ;;
    *) echo "usage: $0 {start|stop|clean}" >&2; exit 2 ;;
esac
