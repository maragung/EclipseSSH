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
# A second port on the same server whose only difference is that it never probes the client. See the
# Match block below for what it is for.
SILENT_PORT="${ECLIPSE_TEST_SSHD_SILENT_PORT:-$((PORT + 1))}"
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
    # `stop` leaves the directory in place - it holds the log somebody may still want to read - so a
    # second `start` finds yesterday's keys and ssh-keygen stops on an interactive overwrite prompt.
    # A fresh key pair every start is also the honest thing for a throwaway sandbox.
    rm -f "$DIR/host_ed25519" "$DIR/host_ed25519.pub" \
          "$DIR/client_ed25519" "$DIR/client_ed25519.pub" "$DIR/known_hosts"
    ssh-keygen -t ed25519 -f "$DIR/host_ed25519" -N '' -q -C eclipse-test-host
    ssh-keygen -t ed25519 -f "$DIR/client_ed25519" -N '' -q -C eclipse-test-client
    cp "$DIR/client_ed25519.pub" "$DIR/authorized_keys"
    chmod 600 "$DIR/authorized_keys" "$DIR/host_ed25519" "$DIR/client_ed25519"
    printf 'Eclipse local test banner\n' > "$DIR/banner.txt"
    # StrictModes off because the sandbox lives under the build directory rather than in a home
    # directory sshd would insist on owning; PAM and password auth off because both need root and
    # neither is what the test exercises.
    #
    # LogLevel is DEBUG rather than VERBOSE for one line only: sshd reports the global requests a
    # client sends it ("server_input_global_request: rtype keepalive@openssh.com want_reply 1") at
    # debug1, which VERBOSE is below. That line is the only direct evidence that the *app's own*
    # outbound heartbeat is firing - answering the server's probes proves the opposite direction, and a
    # session that merely stays up proves nothing while ClientAliveInterval is set, because the replies
    # keep the app's idle timer alive too. sshd's manual warns that DEBUG logging violates user privacy;
    # here the only user is a throwaway key-only account inside a sandbox under .tmp-build, which is
    # gitignored, so the log holds nothing that is not already local scratch.
    #
    # The Match block gives the same server a second port that never probes the client, because
    # `ClientAliveInterval 0` - a server that sends nothing and waits forever - is OpenSSH's *default*
    # and therefore what most VPS images actually run. On such a server the app's own heartbeat is the
    # only traffic on an idle link, and it is the only thing standing between an idle session and the
    # app's own idle timeout. The other port cannot show that: with ClientAliveInterval set,
    # the server's probes and the client's replies keep the idle timer alive whether the app's heartbeat
    # fires or not, so a broken heartbeat still looks healthy. Being the same sshd process, the same
    # host key and the same account, a session here differs from one on $PORT in exactly one property.
    #
    # ClientAliveInterval is on, and aggressive, because it is the half of keep-alive the app does not
    # control: sshd sends keepalive@openssh.com *to the client* with want-reply set and disconnects a
    # client that fails to answer ClientAliveCountMax of them. Practically every hardened VPS image
    # sets it, so a client that answered nothing would be dropped by a real server a few minutes after
    # login with nothing wrong at either end - which is what "connects, prints the motd, then keeps
    # reconnecting" looks like from the outside. Five seconds with two strikes puts that failure inside
    # a test's patience instead of three minutes away, and MINA's own server never sends the request at
    # all. Note that this heredoc is unquoted (it interpolates \$DIR and \$PORT), so nothing inside it
    # may contain backticks.
    cat > "$DIR/sshd_config" <<EOF
Port $PORT
Port $SILENT_PORT
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
LogLevel DEBUG
ClientAliveInterval 5
ClientAliveCountMax 2
TCPKeepAlive yes
Match LocalPort $SILENT_PORT
ClientAliveInterval 0
EOF
    "$sshd" -t -f "$DIR/sshd_config"
    nohup nice -n 10 "$sshd" -D -e -f "$DIR/sshd_config" > "$DIR/sshd.log" 2>&1 &
    printf '%s\n' "$PORT" > "$DIR/port"
    printf '%s\n' "$SILENT_PORT" > "$DIR/port-silent"
    id -un > "$DIR/user"
    for _ in $(seq 1 50); do
        if ssh -i "$DIR/client_ed25519" -p "$PORT" -o StrictHostKeyChecking=no \
               -o UserKnownHostsFile="$DIR/known_hosts" -o BatchMode=yes \
               -o ConnectTimeout=2 "$(id -un)@127.0.0.1" true 2>/dev/null; then
            echo "local sshd ready on 127.0.0.1:$PORT (silent on $SILENT_PORT) as $(id -un)"
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
