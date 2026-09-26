package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The apt-update ladder and everything feeding it, pinned down piece by piece.
 *
 * The ladder is the feature's answer to "Updating package lists" being the step a phone network is
 * most likely to break: instead of one archive and a twenty-minute hang, the step tries the primary
 * archive, the primary over IPv4 (the classic phone-network failure is a black-holed IPv6 route),
 * the official live mirror feed, and finally the built-in mirrors — and only fails when every rung
 * has. That policy is decision logic, so it lives in pure functions these tests drive directly,
 * with one scripted-proot test per end-to-end promise: the rung that wins is the sources.list that
 * stays, exhausting the ladder names every archive tried, and every rung's verdict is a fact about
 * its own archive because the command is scoped to the sources.list under test.
 *
 * The failure taxonomy's end-to-end promises are here too, because they are the manager's to keep:
 * a rung that dies with the launcher's exit code aborts the ladder as a proot failure instead of
 * descending, the runtime smoke step answers before any archive is asked anything, a dead resolver
 * is named as DNS before a single package byte moves, and an offline device never reaches the
 * ladder at all.
 *
 * The install's contents are pinned here too: one apt command naming the twelve base packages, all
 * of them from the archive the ladder picked, and — separately — the packages the install dialog's
 * checkboxes can add. The 2026-09-18 decision this file used to record (no NodeSource entry, no npm
 * globals, no step reaching a registry outside the archive) still holds for every install that
 * ticked nothing, which is what the "and nothing else" test below pins; what the checkboxes add is
 * a step that exists only for a user who asked for it, and whose failures are warnings rather than
 * a failed install.
 */
class UbuntuDistributionManagerTest {

    // ------------------------------------------------------------------ sources.list

    @Test
    fun `sources list content carries the marker, the three suites and one base`() {
        val distro = distro(arch = "arm64")
        val content = sourcesListContent(distro, "http://ports.ubuntu.com/ubuntu-ports")

        // The marker is what isConfigured() looks for; without it a re-run setup looks unconfigured.
        assertThat(content).contains("# managed by Eclipse SSH")

        // Every suite the app pins, named by the codename the catalog entry carries — a mismatch
        // here is a 404 on the first update of a fresh install.
        assertThat(content)
            .contains("deb http://ports.ubuntu.com/ubuntu-ports jammy main restricted universe multiverse")
        assertThat(content)
            .contains("deb http://ports.ubuntu.com/ubuntu-ports jammy-updates main restricted universe multiverse")
        assertThat(content)
            .contains("deb http://ports.ubuntu.com/ubuntu-ports jammy-security main restricted universe multiverse")

        // One base URL for all three suites — the property that makes a rung swappable: any mirror
        // in the ladder serves release, -updates and -security from the same base.
        val bases = content.lines().filter { it.startsWith("deb ") }.map { it.split(" ")[1] }
        assertThat(bases.distinct()).containsExactly("http://ports.ubuntu.com/ubuntu-ports")
    }

    @Test
    fun `sources list content names the suites after the distro it is given`() {
        // The codename travels with the distro, not with a constant: 26.04 must get resolute
        // suites or its update 404s, and the only way that can go wrong is the content builder
        // ignoring the distro it was handed.
        val content = sourcesListContent(distro(arch = "amd64", release = "noble"), "http://archive.ubuntu.com/ubuntu")
        assertThat(content).contains(" noble ")
        assertThat(content).contains(" noble-updates ")
        assertThat(content).contains(" noble-security ")
        assertThat(content).doesNotContain(" jammy")
    }

    // ------------------------------------------------------------------ the ladder

    @Test
    fun `the ladder tries the primary twice with ipv4 forced the second time, then the alternates`() {
        val distro = distro(arch = "arm64")
        val candidates =
            aptUpdateCandidates(
                distro,
                listOf(
                    // The primary itself, sneaking in via the feed: already failed twice, must not
                    // run a third time.
                    primaryArchiveUrl(distro),
                    // A duplicate in the feed, trailing slash variant included: one rung, not two.
                    "http://mirror.a.example/ubuntu-ports/",
                    "http://mirror.a.example/ubuntu-ports",
                ),
            )

        assertThat(candidates.map { it.baseUri }).containsExactly(
            "http://ports.ubuntu.com/ubuntu-ports",
            "http://ports.ubuntu.com/ubuntu-ports",
            "http://mirror.a.example/ubuntu-ports/",
            "http://mirrors.ustc.edu.cn/ubuntu-ports",
            "http://mirror.nju.edu.cn/ubuntu-ports",
        ).inOrder()
        // Rung two's only difference: IPv4 forced, for the black-holed-IPv6 phone network.
        assertThat(candidates[0].forceIpv4).isFalse()
        assertThat(candidates[1].forceIpv4).isTrue()
        assertThat(candidates.drop(2).map { it.forceIpv4 }).containsExactly(false, false, false).inOrder()
    }

    @Test
    fun `amd64 devices get the archive and its mirror family, not the ports one`() {
        val distro = distro(arch = "amd64")
        val candidates = aptUpdateCandidates(distro, emptyList())

        assertThat(candidates.map { it.baseUri }).containsExactly(
            "http://archive.ubuntu.com/ubuntu",
            "http://archive.ubuntu.com/ubuntu",
            "http://mirrors.edge.kernel.org/ubuntu",
        ).inOrder()
    }

    @Test
    fun `the fallback rungs are what remains after the two primary rungs`() {
        val distro = distro(arch = "arm64")
        val all = aptUpdateCandidates(distro, listOf("http://mirror.a.example/ubuntu-ports"))
        val fallbacks = fallbackCandidates(distro, listOf("http://mirror.a.example/ubuntu-ports"))
        assertThat(fallbacks).isEqualTo(all.drop(2))
    }

    @Test
    fun `the update command pins retries, languages, a connect timeout and its own sources`() {
        // The exact strings the pty receives, asserted so a flag nobody can test end to end is at
        // least pinned as written.
        val attempt = AptUpdateAttempt("http://ports.ubuntu.com/ubuntu-ports", forceIpv4 = false)
        val scoped = aptUpdateCommand(attempt)
        assertThat(scoped).isEqualTo(
            "apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30" +
                " -o Dir::Etc::sourcelist=/etc/apt/sources.list -o Dir::Etc::sourceparts=/dev/null",
        )
        assertThat(aptUpdateCommand(AptUpdateAttempt("http://ports.ubuntu.com/ubuntu-ports", forceIpv4 = true)))
            .isEqualTo("$scoped -o Acquire::ForceIPv4=true")
        // The winner's confirmation runs unscoped on purpose: it is there to pick up the lists the
        // rung was told not to look at.
        assertThat(aptUpdateCommand(attempt, scoped = false)).isEqualTo(
            "apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30",
        )
    }

    // ------------------------------------------------------------------ the mirror feed

    @Test
    fun `the mirror feed parses to http-only, deduped, primary-free, capped at three`() {
        val primary = "http://ports.ubuntu.com/ubuntu-ports"
        val body =
            listOf(
                // Trailing slash: trimmed, so it dedupes against its no-slash twin further down.
                "http://mirror.a.example/ubuntu-ports/",
                // https-only mirrors are dropped: the guest apt has no CA store until the base
                // packages install — which is what this very update unlocks.
                "https://mirror.secure.example/ubuntu-ports",
                // The primary again: it has already failed twice by the time the feed is consulted.
                primary,
                "",
                "http://mirror.a.example/ubuntu-ports",
                "http://mirror.b.example/ubuntu-ports",
                "http://mirror.c.example/ubuntu-ports",
                "http://mirror.d.example/ubuntu-ports",
            ).joinToString("\n")

        val mirrors = parseMirrorList(body, primary)

        // Feed order kept (the feed is geo-sorted for the asking IP), three only, https and the
        // primary gone.
        assertThat(mirrors).containsExactly(
            "http://mirror.a.example/ubuntu-ports",
            "http://mirror.b.example/ubuntu-ports",
            "http://mirror.c.example/ubuntu-ports",
        ).inOrder()
    }

    @Test
    fun `a feed entry carrying anything but a bare http url is not a mirror`() {
        // The feed is network input. Whatever it says must not smuggle suite or component fields,
        // a fragment, userinfo, a query or an https URL past trim() and into a generated deb line.
        val primary = "http://ports.ubuntu.com/ubuntu-ports"
        val body =
            listOf(
                "http://evil.example/u jammy main # injected suite fields",
                "http://user:pass@evil.example/ubuntu-ports",
                "http://evil.example/ubuntu-ports#fragment",
                "http://evil.example/ubuntu-ports?suite=jammy",
                "http://[::1]/ubuntu-ports",
                "https://secure.example/ubuntu-ports",
                "http://mirror.ok.example:8080/ubuntu-ports/",
            ).joinToString("\n")

        val mirrors = parseMirrorList(body, primary)

        assertThat(mirrors).containsExactly("http://mirror.ok.example:8080/ubuntu-ports")
    }

    @Test
    fun `an empty or unusable feed body yields no mirrors`() {
        val primary = "http://ports.ubuntu.com/ubuntu-ports"
        assertThat(parseMirrorList("", primary)).isEmpty()
        assertThat(parseMirrorList("https://only.example/ubuntu\nnot a url\n", primary)).isEmpty()
    }

    // ------------------------------------------------------------------ resolver filtering

    @Test
    fun `resolvers are filtered to literals, ipv4 first, capped at three with a public fallback`() {
        // A scope-suffixed link-local is unparseable by glibc's res_init: it would sit in
        // resolv.conf as a dead nameserver eating the lookup budget.
        assertThat(filterDnsServers(listOf("fe80::1%wlan0", "192.168.1.1", "not-a-server")))
            .containsExactly("192.168.1.1", "1.1.1.1")
            .inOrder()
        // IPv4 before IPv6, duplicates gone, one public fallback appended last.
        assertThat(filterDnsServers(listOf("2001:db8::1", "10.0.0.1", "10.0.0.1")))
            .containsExactly("10.0.0.1", "2001:db8::1", "1.1.1.1")
            .inOrder()
        // More platform servers than glibc reads: capped at two, so the fallback makes three.
        assertThat(filterDnsServers(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")))
            .containsExactly("10.0.0.1", "10.0.0.2", "1.1.1.1")
            .inOrder()
        // Both fallbacks already present: nothing appended, still three at most.
        assertThat(filterDnsServers(listOf("8.8.8.8", "1.1.1.1", "10.0.0.1")))
            .containsExactly("8.8.8.8", "1.1.1.1", "10.0.0.1")
            .inOrder()
        // A degenerate platform list degrades to the fallbacks alone, never to an empty file.
        assertThat(filterDnsServers(emptyList())).containsExactly("1.1.1.1").inOrder()
        assertThat(filterDnsServers(listOf("garbage"))).containsExactly("1.1.1.1").inOrder()
    }

    // ------------------------------------------------------------------ end to end, scripted proot

    @Test
    fun `a failing primary archive walks the ladder to a mirror that works and stays`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        var scopedUpdates = 0
        harness.scripted.respond = { command ->
            when {
                // A ladder rung: scoped to the sources.list under test. The NodeSource update is
                // scoped to its own list and never matches this branch.
                command.startsWith("apt-get update") && isScopedRung(command) -> {
                    scopedUpdates++
                    // Primary, primary over IPv4, first built-in: all fail. The second built-in
                    // works, and its sources.list is the one that must remain on disk.
                    if (scopedUpdates <= 3) 100 to "Err:1 http://ports.ubuntu.com jammy Release\n" else 0 to ""
                }
                // The winner's unscoped confirmation, and the NodeSource update: neither is a rung.
                command.startsWith("apt-get update") -> 0 to ""
                else -> baseline(command)
            }
        }

        harness.distribution.setup()

        // Four rungs ran scoped — the two primaries and the two built-ins — and rung two alone
        // forced IPv4.
        val scoped =
            harness.scripted.commands
                .filter { it.startsWith("apt-get update") && isScopedRung(it) }
        assertThat(scoped).hasSize(4)
        assertThat(scoped[0]).doesNotContain("ForceIPv4")
        assertThat(scoped[1]).contains("ForceIPv4=true")
        assertThat(scoped.drop(2).map { it.contains("ForceIPv4") }).containsExactly(false, false).inOrder()

        // Exactly one unscoped update: the confirmation after the win.
        assertThat(
            harness.scripted.commands
                .count { it.startsWith("apt-get update") && !it.contains("Dir::Etc::sourcelist") },
        ).isEqualTo(1)

        // The winning rung's base is what Repair and every later install reuse — on disk and in
        // the sidecar an exhausted ladder restores from.
        val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
        assertThat(sources).contains("mirror.nju.edu.cn/ubuntu-ports")
        assertThat(sources).doesNotContain("ports.ubuntu.com")
        assertThat(File(harness.rootDir, "last-good-mirror").readText())
            .isEqualTo("http://mirror.nju.edu.cn/ubuntu-ports")
    }

    @Test
    fun `a feed mirror that works wins the ladder and is recorded as the last known good`() = runTest {
        // A file: feed, so the test injects the feed's answer without touching the network.
        val feed =
            File.createTempFile("mirrors", ".txt").apply {
                deleteOnExit()
                writeText(
                    listOf(
                        "http://mirror.feed.example/ubuntu-ports/",
                        "http://mirror.other.example/ubuntu-ports",
                    ).joinToString("\n"),
                )
            }
        val harness = Harness(distro(arch = "arm64"), mirrorListUrl = "file://${feed.absolutePath}")
        var scopedUpdates = 0
        harness.scripted.respond = { command ->
            when {
                command.startsWith("apt-get update") && isScopedRung(command) -> {
                    scopedUpdates++
                    // Both primary rungs fail; the feed's first mirror wins.
                    if (scopedUpdates <= 2) 100 to "Err:1 … Could not connect" else 0 to ""
                }
                command.startsWith("apt-get update") -> 0 to ""
                else -> baseline(command)
            }
        }

        harness.distribution.setup()

        assertThat(scopedUpdates).isEqualTo(3)
        val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
        assertThat(sources).contains("mirror.feed.example/ubuntu-ports")
        assertThat(File(harness.rootDir, "last-good-mirror").readText())
            .isEqualTo("http://mirror.feed.example/ubuntu-ports")
    }

    @Test
    fun `exhausting the ladder fails the step naming every archive tried`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update")) {
                100 to "Err:1 … Could not connect"
            } else {
                baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        // The evidence names a timed-out archive, and the type carries it.
        assertThat(thrown).isInstanceOf(UserspaceFailure.MirrorUnreachable::class.java)
        assertThat((thrown as UserspaceFailure.MirrorUnreachable).kind)
            .isEqualTo(UserspaceFailure.Kind.Timeout)
        // "Could not update" alone would hide which mirror said why; every rung is named.
        assertThat(thrown.message).contains("ports.ubuntu.com")
        assertThat(thrown.message).contains("mirrors.ustc.edu.cn")
        assertThat(thrown.message).contains("mirror.nju.edu.cn")
        assertThat(thrown.message).contains("Could not connect")
        // The feed was unreachable (nothing listens on the harness's port), and the message says
        // so — "unreachable" and "named nothing usable" are different facts.
        assertThat(thrown.message).contains("mirror feed: unreachable")
        // No rung won, so the restore is the primary, never the last failed rung.
        val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
        assertThat(sources).contains("ports.ubuntu.com/ubuntu-ports")
    }

    @Test
    fun `a feed that names no usable mirror is named as such in the exhaustion message`() = runTest {
        val feed =
            File.createTempFile("mirrors", ".txt").apply {
                deleteOnExit()
                // https-only entries parse to nothing: the feed answered, but unusably.
                writeText("https://secure.example/ubuntu-ports\n")
            }
        val harness = Harness(distro(arch = "arm64"), mirrorListUrl = "file://${feed.absolutePath}")
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update")) {
                100 to "Err:1 … Could not connect"
            } else {
                baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(thrown!!.message).contains("mirror feed: fetched, but it named no usable mirror")
    }

    @Test
    fun `a feed whose body is not a plausible mirror list is named as too large`() = runTest {
        // Past the 256 KiB ceiling: not a mirror list but a misredirect or hostile body, refused
        // before it can be buffered — and named as "too large", which is a different fact from
        // "unreachable" when someone reads the exhaustion message.
        val feed =
            File.createTempFile("mirrors", ".txt").apply {
                deleteOnExit()
                writeText(buildString {
                    repeat(64 * 1024) { append("http://mirror.example/ubuntu-ports\n") } // 2 MiB
                })
            }
        val harness = Harness(distro(arch = "arm64"), mirrorListUrl = "file://${feed.absolutePath}")
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update")) {
                100 to "Err:1 … Could not connect"
            } else {
                baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UserspaceFailure.MirrorUnreachable::class.java)
        assertThat(thrown!!.message).contains("mirror feed: too large (over 256 KiB)")
    }

    @Test
    fun `a failing rung surfaces the blocked-syscall log the proot fork kept`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        // The shape the rename ENOSYS left behind: apt output that says nothing about why, and a
        // SIGSYS log naming the syscall the fork could not downgrade (patch 0001's evidence).
        // Written while the run is in flight, which is where the log comes from: setup empties it
        // at the start, so anything written before it is another run's evidence and is dropped.
        val log = File(harness.runtime.rootDir, "sigsys-log.txt")
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update") && isScopedRung(command)) {
                log.appendText(
                    listOf(
                        "SIGSYS: time=15:57:49 pid=1234 comm=apt-get kernel_num=82 pr=82",
                        "SIGSYS: time=15:57:50 pid=1234 comm=dpkg kernel_num=82 pr=82",
                    ).joinToString("\n") + "\n",
                )
                100 to "E: Failed to fetch … rename failed, Function not implemented\n"
            } else {
                baseline(command)
            }
        }

        runCatching { harness.distribution.setup() }

        // The diagnostic ring names the trapped syscall, which is the difference between
        // "apt failed" and "rename (82) was blocked and unmapped".
        val export = harness.distribution.diagnostics.export()
        assertThat(export).contains("blocked-syscall log")
        assertThat(export).contains("kernel_num=82")
    }

    @Test
    fun `a failing rung with no blocked syscalls records no syscall-log event`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update") && isScopedRung(command)) {
                100 to "Err:1 … Could not connect\n"
            } else {
                baseline(command)
            }
        }

        runCatching { harness.distribution.setup() }

        // No SIGSYS happened, so the event must not appear either — silence is not evidence.
        assertThat(harness.distribution.diagnostics.export())
            .doesNotContain("blocked-syscall log")
    }

    @Test
    fun `a rung dying with the launcher's exit code aborts the ladder as a proot failure`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update") && isScopedRung(command)) {
                // The historical bug's shape: linuxpty.c _exit(127) on exec failure, no output.
                127 to ""
            } else {
                baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        assertThat((thrown as UserspaceFailure.ProotLaunchFailed).exitCode).isEqualTo(127)
        // The message names the runtime, never a mirror.
        assertThat(thrown.message).contains("proot")
        assertThat(thrown.message).doesNotContain("mirror")
        // One rung only: descending would burn every remaining rung against a broken runtime —
        // and the feed would never be fetched either.
        assertThat(
            harness.scripted.commands
                .count { it.startsWith("apt-get update") },
        ).isEqualTo(1)
        // The aborted rung's mirror is not left as the standing configuration: no rung ever won,
        // so the restore is the primary.
        val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
        assertThat(sources).contains("ports.ubuntu.com/ubuntu-ports")
    }

    @Test
    fun `a smoke command that dies silently fails the step before any archive is asked`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command == "echo eclipse-runtime-ok") {
                // Same shape as the rung killer above: a launcher exit code and not a word.
                127 to ""
            } else {
                baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UserspaceFailure.ProotLaunchFailed::class.java)
        assertThat((thrown as UserspaceFailure.ProotLaunchFailed).exitCode).isEqualTo(127)
        // The old pipeline's first proot execution was the apt rung itself, which is exactly how
        // a broken runtime read as "every mirror is down". Nothing apt-shaped ran.
        assertThat(
            harness.scripted.commands
                .none { it.startsWith("apt-get") || it.startsWith("dpkg") },
        ).isTrue()
    }

    @Test
    fun `a resolver that cannot name the archive host fails as DNS before any package byte`() = runTest {
        val harness =
            Harness(
                distro(arch = "arm64"),
                // The scope-suffixed link-local is exactly the platform entry the filter exists to
                // drop; the resolver that survives is the one the failure message must name.
                dnsServers = { listOf("192.168.1.1", "fe80::1%wlan0") },
            )
        harness.scripted.respond = { command ->
            if (command.startsWith("getent hosts ")) {
                2 to "" // getent's own "name not found" exit
            } else {
                baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UserspaceFailure.DnsUnresolved::class.java)
        assertThat((thrown as UserspaceFailure.DnsUnresolved).servers)
            .containsExactly("192.168.1.1", "1.1.1.1")
            .inOrder()
        assertThat(thrown.message).contains("192.168.1.1")
        // resolv.conf holds the filtered list: the platform resolver plus the public fallback,
        // and never the unparseable link-local.
        val resolv = File(harness.runtime.rootfsDir, "etc/resolv.conf").readText()
        assertThat(resolv).contains("nameserver 192.168.1.1")
        assertThat(resolv).contains("nameserver 1.1.1.1")
        assertThat(resolv).doesNotContain("fe80::1")
        // Discovered here, not ten minutes later by an exhausted ladder: no apt command ran.
        assertThat(
            harness.scripted.commands
                .none { it.startsWith("apt-get") },
        ).isTrue()
    }

    @Test
    fun `the resolver list is read when resolv conf is written, not when the manager is built`() = runTest {
        // The graph is built once; the device's network keeps changing. A captured list would
        // freeze the rootfs to whatever network it was built on, and Repair could never fix a
        // DNS breakage by changing networks.
        var liveServers = listOf("10.0.0.1")
        val harness = Harness(distro(arch = "arm64"), dnsServers = { liveServers })
        liveServers = listOf("10.0.0.2")

        harness.distribution.setup()

        val resolv = File(harness.runtime.rootfsDir, "etc/resolv.conf").readText()
        assertThat(resolv).contains("nameserver 10.0.0.2")
        assertThat(resolv).doesNotContain("10.0.0.1")
    }

    @Test
    fun `an offline device fails before any command runs`() = runTest {
        val harness = Harness(distro(arch = "arm64"), networkOnline = { false })

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UserspaceFailure.Offline::class.java)
        assertThat(harness.scripted.commands).isEmpty()
    }

    @Test
    fun `the offline gate re-checks before the network-heavy phases`() = runTest {
        var online = true
        val harness = Harness(distro(arch = "arm64"), networkOnline = { online })
        harness.scripted.respond = { command ->
            // The network drops while the install is already running: the gate must catch it
            // before the ladder, not after every rung has failed for no mirror's reason.
            if (command == "echo eclipse-runtime-ok") online = false
            baseline(command)
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(UserspaceFailure.Offline::class.java)
        assertThat(
            harness.scripted.commands
                .none { it.startsWith("apt-get update") },
        ).isTrue()
    }

    @Test
    fun `a full disk fails the apt phase before the first archive is asked`() = runTest {
        // The installer's gate passed (the rootfs got this far), but the disk filled since —
        // or the budget was spent on the extraction itself. The re-check must refuse before
        // apt burns minutes downloading into a disk that cannot hold the packages.
        val harness = Harness(
            distro(arch = "arm64"),
            // 0 means "unknown" everywhere else in this cluster; a small positive number is
            // "known, and below every budget".
            freeBytes = { 100L * 1024 * 1024 },
        )

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        // The DiskFull contract: the gate's own message, mapped by the "Ubuntu needs" prefix.
        assertThat(thrown).isInstanceOf(UserspaceFailure.DiskFull::class.java)
        assertThat(thrown!!.message).contains("only about 100 MB is free")
        // No rung ran: the refusal happened before the ladder, not inside it.
        assertThat(
            harness.scripted.commands
                .none { it.startsWith("apt-get update") },
        ).isTrue()
    }

    @Test
    fun `an unknown free-space answer never blocks the apt phase`() = runTest {
        // 0 is "the probe could not answer", never "full" — the same contract as the
        // installer's gate. Setup must run to completion on an unmeasurable disk.
        val harness = Harness(distro(arch = "arm64"), freeBytes = { 0L })

        val report = harness.distribution.setup()

        assertThat(report.warnings).isEmpty()
    }

    @Test
    fun `one unavailable base package is a warning, not a failed install`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        var fullListAttempts = 0
        harness.scripted.respond = { command ->
            when {
                // The whole-list command is the only one naming two packages in a row.
                command.contains("openssh-client sudo") -> {
                    fullListAttempts++
                    100 to "E: Unable to locate package some-package"
                }
                // One package is simply not available on this mirror: the per-package fallback
                // turns it into a warning while the other six install.
                command == "apt-get install -y --no-install-recommends git" ->
                    100 to "E: Unable to locate package git"
                else -> baseline(command)
            }
        }

        val report = harness.distribution.setup()

        // The list ran whole, once more with --fix-missing, and once more after the recovery
        // repair pass cleared the interruption that the failed install itself left behind —
        // before the per-package fallback got its turn.
        assertThat(fullListAttempts).isEqualTo(3)
        assertThat(report.warnings.any { it.contains("package 'git' was not installed") }).isTrue()
    }

    @Test
    fun `a failed maintainer script is recorded with the line that names its cause`() = runTest {
        // E2E run 35058820878, in the shape the app saw it: the bulk install got seventy seconds
        // in and died configuring openssh-client — whose postinst runs addgroup, a setgid chmod and
        // update-alternatives, the steps Android is likeliest to refuse — and then every later apt
        // command failed in a second or two, because dpkg retries a half-configured package before
        // it does anything else and dies on it again. All of them print the same sentence, and what
        // the report carried was dpkg's summary block, which names no step at all: "Errors were
        // encountered while processing: openssh-client". The line that diagnoses it is the one
        // dpkg prints *above* that block, and the ring is where it has to land.
        val harness = Harness(distro(arch = "arm64"))
        val refused =
            "Setting up openssh-client (1:8.9p1-3ubuntu0.17) ...\n" +
                "update-alternatives: error: cannot create /etc/alternatives/rsh: Permission denied\n" +
                "dpkg: error processing package openssh-client (--configure):\n" +
                " installed openssh-client package post-installation script subprocess returned error exit status 2\n" +
                "Errors were encountered while processing:\n" +
                " openssh-client\n" +
                "E: Sub-process /usr/bin/dpkg returned an error code (1)\n"
        harness.scripted.respond = { command ->
            when {
                command.contains("openssh-client sudo") -> 100 to refused
                command.startsWith("apt-get install -y --no-install-recommends ") -> 100 to refused
                else -> baseline(command)
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()
        val export = harness.distribution.diagnostics.export()

        assertThat(thrown).isNotNull()
        assertThat(export).contains("update-alternatives: error: cannot create /etc/alternatives/rsh")
        assertThat(export).contains("dpkg: error processing package openssh-client")
        // The twelve per-package refusals are one fact, not twelve entries: they all said the
        // same sentence, because it is the same half-configured package every apt command trips on.
        assertThat(export.lines().count { it.contains("base packages refused one by one") }).isEqualTo(1)
        assertThat(export).contains("12/12 refused")
    }

    @Test
    fun `the cause is recorded, not the dependents that report it`() = runTest {
        // The shape the real failure has and the single-package fixture above does not: a failed
        // maintainer script is followed by *every* package that depends on it, each with its own
        // dpkg verdict, so the last verdict in the output describes a consequence. Run 35058820878
        // died configuring openssh-client; ssh and openssh-sftp-server then reported that they
        // cannot be configured because of it. The verdict that diagnoses is the first one whose
        // script failed, and dpkg says so on the line under it.
        val harness = Harness(distro(arch = "arm64"))
        val cascade =
            "Setting up openssh-client (1:8.9p1-3ubuntu0.17) ...\n" +
                "chgrp: invalid group: '_ssh'\n" +
                "dpkg: error processing package openssh-client (--configure):\n" +
                " installed openssh-client package post-installation script subprocess returned error exit status 1\n" +
                "dpkg: dependency problems prevent configuration of openssh-sftp-server:\n" +
                " openssh-sftp-server depends on openssh-client (>= 1:8.9p1-3); however:\n" +
                "  Package openssh-client is not configured yet.\n" +
                "dpkg: error processing package openssh-sftp-server (--configure):\n" +
                " dependency problems - leaving unconfigured\n" +
                "Errors were encountered while processing:\n" +
                " openssh-client\n" +
                " openssh-sftp-server\n" +
                "E: Sub-process /usr/bin/dpkg returned an error code (1)\n"
        harness.scripted.respond = { command ->
            when {
                command.contains("openssh-client sudo") -> 100 to cascade
                command.startsWith("apt-get install -y --no-install-recommends ") -> 100 to cascade
                else -> baseline(command)
            }
        }

        runCatching { harness.distribution.setup() }
        val export = harness.distribution.diagnostics.export()
        val reason = export.lines()
            .first { it.contains("bulk base-package install failed") }

        // The step Android refused, and the verdict naming the package it belonged to.
        assertThat(reason).contains("chgrp: invalid group: '_ssh'")
        assertThat(reason).contains("dpkg: error processing package openssh-client")
        // Not the cascade: a dependent's complaint is not the reason the install died, and a
        // report that leads with it sends the reader to the wrong package.
        assertThat(reason).doesNotContain("dependency problems")
    }

    @Test
    fun `a failed bulk install is repaired before the per-package fallback`() = runTest {
        // A bulk install that dies part-way is what interrupts dpkg, and apt then refuses every
        // command for about a second without touching a mirror. The per-package fallback is such
        // a command, so without a repair pass between them it measures the database rather than
        // the packages and loses all of them — E2E run 35056615874: 33 packages, every one
        // refused in 1-2s, while `apt-get check` exited 0 before and after.
        val harness = Harness(distro(arch = "arm64"))
        var bulkAttempts = 0
        var repairPasses = 0
        harness.scripted.respond = { command ->
            when {
                command.contains("openssh-client sudo") -> {
                    bulkAttempts++
                    // The attempt after the recovery pass is the one that works, and it is the
                    // only reason three attempts are spent: the retry is one command where the
                    // fallback is one per package.
                    if (bulkAttempts == 3) 0 to "" else 100 to "E: dpkg was interrupted, you must manually run 'dpkg --configure -a'"
                }
                command.startsWith("dpkg --configure -a") -> {
                    repairPasses++
                    0 to ""
                }
                else -> baseline(command)
            }
        }

        val report = harness.distribution.setup()

        // The prologue's pass and the recovery pass. The third bulk attempt exists only because
        // the second of those ran and exited 0, which is also what makes the order a fact rather
        // than a coincidence of the fake.
        assertThat(repairPasses).isEqualTo(2)
        assertThat(bulkAttempts).isEqualTo(3)
        assertThat(report.warnings).contains("the base packages needed a dpkg repair pass to install")
        // And the fallback never ran: the recovery rung answered first.
        assertThat(
            harness.scripted.commands
                .none { it == "apt-get install -y --no-install-recommends git" },
        ).isTrue()
    }

    @Test
    fun `the dpkg repair pass runs before the first archive is asked anything`() = runTest {
        val harness = Harness(distro(arch = "arm64"))

        harness.distribution.setup()

        val commands = harness.scripted.commands
        val repairIndex = commands.indexOfFirst { it.startsWith("dpkg --configure -a") }
        val firstUpdateIndex = commands.indexOfFirst { it.startsWith("apt-get update") }
        assertThat(repairIndex).isAtLeast(0)
        // An interrupted earlier install leaves dpkg half-configured, and apt refuses to proceed
        // until the repair pass has run — so the pass must come first, every time.
        assertThat(repairIndex).isLessThan(firstUpdateIndex)
    }

    @Test
    fun `a rung that never answers is timed out and the ladder moves on`() = runTest {
        var wedgesLeft = 1
        val harness =
            Harness(
                distro(arch = "arm64"),
                wedgeOn = { command ->
                    // The first ladder rung wedges: a pty read that produces nothing and ends
                    // nothing — the shape of an apt-get stuck on a dead network.
                    if (wedgesLeft > 0 && command.startsWith("apt-get update") && isScopedRung(command)) {
                        wedgesLeft--
                        true
                    } else {
                        false
                    }
                },
                aptUpdateAttemptTimeoutMs = 250,
            )
        val startedAt = System.currentTimeMillis()

        harness.distribution.setup()

        val elapsed = System.currentTimeMillis() - startedAt
        // The wedged rung did not end the install: the ladder descended to the IPv4 rung and won,
        // and the timeout is on the record.
        val scoped =
            harness.scripted.commands
                .filter { it.startsWith("apt-get update") && isScopedRung(it) }
        assertThat(scoped).hasSize(2)
        assertThat(harness.distribution.diagnostics.export()).contains("rung timed out")
        val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
        assertThat(sources).contains("ports.ubuntu.com/ubuntu-ports")
        // Until the runtime's read loop becomes cancellable (the storage fork's contract), a
        // wedged read outlives its own timeout: this branch's rung parks for five real seconds
        // even though its budget was 250ms. The park is bounded so this test always terminates;
        // the duration assertion below is the part that turns green once cancellation lands.
        assertThat(elapsed).isLessThan(WEDGE_PARK_MS - 1_000)
    }

    @Test
    fun `the install adds the base packages and nothing else`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.distribution.setup()

        val commands = harness.scripted.commands
        // The whole of what the install adds, in one command, all twelve from the pinned archive.
        assertThat(commands).contains(
            "apt-get install -y --no-install-recommends " +
                "apt-utils bash-completion ca-certificates cron curl git htop openssh-client sudo unzip wget zip",
        )
        // And no third-party registry in the path, because nothing was ticked on the dialog: the
        // NodeSource route and the npm globals exist only for an install that asked for them, so a
        // `deb.nodesource.com` outage still cannot decide whether "Ubuntu" installed.
        assertThat(commands.none { "nodesource" in it || it.startsWith("npm install") }).isTrue()
        assertThat(commands.none { "python3" in it || "pnpm" in it || "opencode" in it }).isTrue()
        // Nor a vendor's own installer. The empty-selection promise now covers a third source, so it
        // is asserted over the third one too: an unticked install fetches exactly one thing from
        // outside the archive, and that is the rootfs it is installing.
        assertThat(commands.none { "claude.ai" in it || "vendor-install" in it }).isTrue()
    }

    // ------------------------------------------------------------------ the preinstall checkboxes

    /**
     * A tick is the only thing that starts the extras step, and no tick means the step is not even
     * announced: the percentage the user watches must not include a step that holds no work.
     */
    @Test
    fun `an unticked install neither runs nor announces the extras step`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        val steps = mutableListOf<SetupStep>()

        harness.distribution.setup(onStep = { steps += it })

        assertThat(steps).contains(SetupStep.INSTALL_BASE_PACKAGES)
        assertThat(steps).doesNotContain(SetupStep.INSTALL_EXTRA_PACKAGES)
        assertThat(harness.scripted.commands.any { it.startsWith("npm ") }).isFalse()
    }

    /**
     * The whole path for one ticked agent: its packages from the archive, its runtime measured, and
     * itself from npm — with the archive's Node.js accepted when it is new enough, which is the
     * branch that must not reach NodeSource at all.
     */
    @Test
    fun `a ticked agent installs its packages and its npm global`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command == "node --version") 0 to "v24.4.1\n" else baseline(command)
        }
        val steps = mutableListOf<SetupStep>()

        val report = harness.distribution.setup(
            onStep = { steps += it },
            extras = listOf(OptionalPackage.OPENCODE),
        )

        assertThat(steps).contains(SetupStep.INSTALL_EXTRA_PACKAGES)
        assertThat(harness.scripted.commands)
            .contains("apt-get install -y --no-install-recommends nodejs npm")
        assertThat(harness.scripted.commands).contains("npm install -g opencode-ai")
        // A Node.js that is already new enough is not upgraded: the third-party repository is not
        // touched when the pinned archive has done the job.
        assertThat(harness.scripted.commands.any { "nodesource" in it }).isFalse()
        assertThat(report.warnings).isEmpty()
    }

    @Test
    fun `an archive node too old for the ticked tools is upgraded from nodesource`() = runTest {
        val harness = Harness(distro(arch = "arm64", release = "noble"))
        var versionCalls = 0
        harness.scripted.respond = { command ->
            when {
                // Noble's archive carries 18.19.1 — the measurement that has to be taken, because a
                // `npm install -g cline` under it would report success over a tool that will not run.
                command == "node --version" -> {
                    versionCalls++
                    if (versionCalls == 1) 0 to "v18.19.1\n" else 0 to "v24.4.1\n"
                }
                else -> baseline(command)
            }
        }

        val report = harness.distribution.setup(extras = listOf(OptionalPackage.CLINE))

        val upgrade = harness.scripted.commands.firstOrNull { "nodesource" in it }
        assertThat(upgrade).isNotNull()
        // One command, because each link is a precondition of the next: key, keyring, source line,
        // then an update scoped to that line — never the ladder's own unscoped update.
        assertThat(upgrade).contains("gpg --dearmor")
        assertThat(upgrade).contains("sources.list.d/nodesource.list")
        assertThat(upgrade).contains("-o Dir::Etc::sourceparts=/dev/null")
        // The global is installed only after the upgrade, never before it.
        val commands = harness.scripted.commands
        assertThat(commands.indexOfFirst { "nodesource" in it })
            .isLessThan(commands.indexOf("npm install -g cline"))
        assertThat(report.warnings).isEmpty()
    }

    /**
     * The version is read off the version's own line. The command runs under `bash --login`, so
     * anything the profile scripts print arrives ahead of it — and `Ubuntu 22.04.5` is a major this
     * code would otherwise have believed, on a guest whose Node.js is really 18.
     */
    @Test
    fun `a banner line does not stand in for the node version`() = runTest {
        val harness = Harness(distro(arch = "arm64", release = "jammy"))
        var versionCalls = 0
        harness.scripted.respond = { command ->
            when {
                command == "node --version" -> {
                    versionCalls++
                    if (versionCalls == 1) 0 to "Ubuntu 22.04.5 LTS\nv18.19.1\n" else 0 to "v24.4.1\n"
                }
                else -> baseline(command)
            }
        }

        harness.distribution.setup(extras = listOf(OptionalPackage.CLINE))

        // Read off the banner, 22 clears the tools' floor and this install would have reported
        // success over a `cline` that cannot run on the 18 underneath it.
        assertThat(harness.scripted.commands.any { "nodesource" in it }).isTrue()
    }

    @Test
    fun `an npm global that refuses is a warning, never a failed install`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            when {
                command == "node --version" -> 0 to "v24.4.1\n"
                command.startsWith("npm install -g cline") -> 100 to "npm ERR! 404 Not Found - cline\n"
                else -> baseline(command)
            }
        }

        // Reaching the assertions at all is half the claim: setup() neither threw nor left the
        // install unfinished over a package the user can add themselves.
        val report = harness.distribution.setup(extras = listOf(OptionalPackage.CLINE))

        val refusals = report.warnings.filter { it.contains("npm package 'cline'") }
        assertThat(refusals).hasSize(1)
        assertThat(refusals.first()).contains("404 Not Found")
    }

    /**
     * The whole path for the one entry that is neither an apt package nor an npm global: a script
     * fetched over https, run, and then *measured* — the read-back is the half that matters, because a
     * script's exit status is its own word for what it did.
     */
    @Test
    fun `a ticked claude entry runs its maker's installer and reaches no registry`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command.startsWith("command -v claude")) 0 to "eclipse-vendor-on-path\n"
            else baseline(command)
        }
        val steps = mutableListOf<SetupStep>()

        val report = harness.distribution.setup(
            onStep = { steps += it },
            extras = listOf(OptionalPackage.CLAUDE_CODE),
        )

        // The command verbatim: the whole script is fetched first, and run only once every byte of it
        // has arrived. A `curl … | bash` here would report success on a 404.
        assertThat(harness.scripted.commands).contains(
            "curl -fsSL https://claude.ai/install.sh -o /tmp/eclipse-vendor-install.sh && " +
                "bash /tmp/eclipse-vendor-install.sh",
        )
        // Nothing from the archive, and nothing from npm. `node --version` is the tell for the
        // NodeSource route — it is only ever run to decide whether that route is needed — so its
        // absence is this entry needing no Node.js at all, not merely not upgrading one.
        assertThat(harness.scripted.commands.none { "nodesource" in it }).isTrue()
        assertThat(harness.scripted.commands.none { it.startsWith("npm ") }).isTrue()
        assertThat(harness.scripted.commands.none { it == "node --version" }).isTrue()
        assertThat(steps).contains(SetupStep.INSTALL_EXTRA_PACKAGES)
        assertThat(report.warnings).isEmpty()

        // The read-back asks the *session* shell rather than the pipeline's, which is the only
        // question worth asking — whether the user will find the command they were just told they
        // have. `setupEnv`'s two apt knobs and its PATH prefix are the tell for which env this ran in.
        val probe = harness.scripted.spawns.single { it.argv.last().startsWith("command -v claude") }
        assertThat(probe.envp).doesNotContain("LC_ALL=C")
        assertThat(probe.envp).doesNotContain("DEBIAN_FRONTEND=noninteractive")
        assertThat(probe.envp.any { it.startsWith("PATH=/home/ubuntu/.local/bin:") }).isFalse()
    }

    @Test
    fun `a vendor installer that refuses is a warning, never a failed install`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            if (command.startsWith("curl -fsSL https://claude.ai/install.sh")) {
                22 to "curl: (22) The requested URL returned error: 404\n"
            } else {
                baseline(command)
            }
        }

        // Reaching the assertions at all is half the claim, as it is for the npm global above.
        val report = harness.distribution.setup(extras = listOf(OptionalPackage.CLAUDE_CODE))

        val refusals = report.warnings.filter { it.contains("Claude Code CLI") }
        assertThat(refusals).hasSize(1)
        assertThat(refusals.first()).contains("404")
        assertThat(harness.distribution.isConfigured()).isTrue()
        assertThat(harness.distribution.diagnostics.export()).contains("vendor installer refused")
        assertThat(harness.distribution.diagnostics.export()).contains("exit=22")
        // One failure, one fact: an installer that never ran is not also asked whether it left a
        // command behind.
        assertThat(harness.scripted.commands.none { it.startsWith("command -v claude") }).isTrue()
    }

    /**
     * The entry is offered on every device and the pipeline refuses it where the vendor ships no
     * build — before anything is fetched, and in this app's own words rather than the vendor's. The
     * dialog greys the same row out for the same reason; this is the half that is authoritative.
     */
    @Test
    fun `the claude entry is skipped where its installer has no build, naming the architecture`() =
        runTest {
            val harness = Harness(distro(arch = "armhf"))

            val report = harness.distribution.setup(extras = listOf(OptionalPackage.CLAUDE_CODE))

            val skips = report.warnings.filter { it.contains("Claude Code CLI") }
            assertThat(skips).hasSize(1)
            assertThat(skips.first()).contains("armhf")
            assertThat(skips.first()).contains("arm64")
            // Nothing was fetched, which is the whole reason the architectures are declared on the
            // entry rather than discovered from the script's own refusal.
            assertThat(harness.scripted.commands.none { "claude.ai" in it }).isTrue()
            assertThat(harness.distribution.isConfigured()).isTrue()
            assertThat(harness.distribution.diagnostics.export()).contains("vendor installer skipped")
        }

    /**
     * The measured-not-assumed half. The baseline answers everything with an empty success, so the
     * installer "succeeds" and the read-back finds nothing — the shape of a script that installed
     * somewhere a shell cannot see. A user told "installed" who then finds no `claude` in their
     * terminal has been misled by this app rather than by the vendor, so the disagreement is a
     * warning rather than silence.
     */
    @Test
    fun `an installer that reports success but leaves no command is a warning`() = runTest {
        val harness = Harness(distro(arch = "arm64"))

        val report = harness.distribution.setup(extras = listOf(OptionalPackage.CLAUDE_CODE))

        val warnings = report.warnings.filter { it.contains("Claude Code CLI") }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.first()).contains("reported a successful install")
        assertThat(warnings.first()).contains("no claude command")
        assertThat(harness.distribution.isConfigured()).isTrue()
        assertThat(harness.distribution.diagnostics.export()).contains("vendor installer not on path")
    }

    /**
     * A disk that is full refuses twenty packages with one sentence, and twenty copies of that
     * sentence in the "installed with warnings" row is one fact written twenty times — so the
     * warnings are grouped by what apt said, exactly as the base step's diagnostics are.
     */
    @Test
    fun `extras that will not install are one warning per reason, naming every package`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            when {
                command.startsWith("apt-get install -y --no-install-recommends ") &&
                    !command.startsWith(BASE_PACKAGES_COMMAND) ->
                    100 to "E: You don't have enough free space in /var/cache/apt/archives/.\n"
                else -> baseline(command)
            }
        }

        val report = harness.distribution.setup(extras = listOf(OptionalPackage.BUILD_TOOLS))

        val grouped = report.warnings.filter { it.contains("these extra packages were not installed") }
        assertThat(grouped).hasSize(1)
        assertThat(grouped.first()).contains("build-essential")
        assertThat(grouped.first()).contains("libtool")
        assertThat(grouped.first()).contains("free space")
        // The install itself stands: only the extras were lost.
        assertThat(harness.distribution.isConfigured()).isTrue()
    }

    /**
     * The one network phase in the pipeline with no offline gate of its own, and the reason it has
     * none: the base system is already on disk when it starts. A hard gate here would answer a
     * connection that dropped at this instant by failing an install whose every other byte landed —
     * a 30 MB redownload to re-fetch a package the user can add in one apt command. The refusal is
     * instead apt's own, written into the report as a warning.
     */
    @Test
    fun `a connection that drops before the extras costs the tools, not the install`() = runTest {
        var online = true
        val harness = Harness(distro(arch = "arm64"), networkOnline = { online })
        harness.scripted.respond = { command ->
            when {
                // The connection drops once the base system is installed — the latest point at
                // which the userspace is complete and the extras have not begun.
                command.startsWith(BASE_PACKAGES_COMMAND) -> {
                    online = false
                    baseline(command)
                }
                // apt and npm as they answer with no route out.
                !online && (command.contains("apt-get install") || command.startsWith("npm ")) ->
                    100 to "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/... " +
                        "Temporary failure resolving 'ports.ubuntu.com'\n"
                else -> baseline(command)
            }
        }
        val steps = mutableListOf<SetupStep>()

        val report = harness.distribution.setup(
            onStep = { steps += it },
            extras = listOf(OptionalPackage.CLINE),
        )

        // Reaching this line is the claim: `setup()` returned a report rather than throwing the
        // offline failure the other network phases would have thrown here, and it got to the end of
        // the pipeline — the base system it had already installed is what the install reports on.
        assertThat(steps.last()).isEqualTo(SetupStep.VERIFY)
        assertThat(report.warnings.any { it.contains("nodejs") }).isTrue()
        assertThat(harness.scripted.commands.any { it.startsWith("npm install") }).isFalse()
        assertThat(harness.distribution.isConfigured()).isTrue()
    }

    /**
     * The NodeSource source line is the one apt list in `sources.list.d` the app wrote itself, and
     * every setup — every Repair included — retires what it finds there. Retiring this one would
     * leave the user with the archive's Node.js and no source to upgrade from, so a later
     * `apt-get upgrade` would walk them backwards past the version this step installed.
     */
    @Test
    fun `a later setup keeps the nodesource line and retires the shipped ones`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        val dir = File(harness.runtime.rootfsDir, "etc/apt/sources.list.d")
        dir.mkdirs()
        File(dir, "ubuntu.sources").writeText("Types: deb\n")
        File(dir, "nodesource.list").writeText(
            "deb [signed-by=/usr/share/keyrings/nodesource.gpg] " +
                "https://deb.nodesource.com/node_24.x nodistro main\n",
        )

        harness.distribution.setup()

        assertThat(File(dir, "nodesource.list").isFile).isTrue()
        assertThat(File(dir, "ubuntu.sources").isFile).isFalse()
        assertThat(File(dir, "ubuntu.sources.disabled").isFile).isTrue()
    }

    @Test
    fun `the failed install names the package dpkg is stuck on`() = runTest {
        // The other half of the diagnosis, and the half apt's own output does not carry: which
        // package every later command is dying on. Run 35058820878 recorded the database's *shape*
        // (status=169537B, updates=0) and nothing else, so the one thing a reader needs — the name,
        // and through it the maintainer script that refused — had to be inferred.
        val harness = Harness(distro(arch = "arm64"))
        val dpkgDir = File(harness.runtime.rootfsDir, "var/lib/dpkg")
        dpkgDir.mkdirs()
        File(dpkgDir, "status").writeText(
            "Package: bash\n" +
                "Status: install ok installed\n" +
                "\n" +
                "Package: openssh-client\n" +
                "Status: install ok half-configured\n" +
                "\n" +
                "Package: openssh-sftp-server\n" +
                "Status: install ok half-installed\n",
        )
        harness.scripted.respond = { command ->
            when {
                command.contains("openssh-client sudo") -> 100 to "dpkg: error processing package openssh-client (--configure):\n"
                command.startsWith("apt-get install -y --no-install-recommends ") -> 100 to "E: dpkg was interrupted\n"
                else -> baseline(command)
            }
        }

        runCatching { harness.distribution.setup() }
        val export = harness.distribution.diagnostics.export()

        assertThat(export).contains("half-configured=openssh-client,openssh-sftp-server (half-installed)")
        // The installed package is not named: this line is about what is stuck, not what is fine.
        assertThat(export.lines().first { it.contains("half-configured=") }).doesNotContain("bash")
    }

    @Test
    fun `a failing repair pass records dpkg's database and its own error line`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        // The state the device was actually left in: a status the pass could not back up, a
        // stale status-old and one unconsumed update record — what makes every later apt run
        // print "dpkg was interrupted" and exit 100 without touching a mirror.
        val dpkgDir = File(harness.runtime.rootfsDir, "var/lib/dpkg")
        File(dpkgDir, "updates").mkdirs()
        File(dpkgDir, "status").writeText("Package: bash\n")
        File(dpkgDir, "status-old").writeText("Package: bash\n")
        File(File(dpkgDir, "updates"), "0001").writeText("record")
        harness.scripted.respond = { command ->
            if (command.startsWith("dpkg --configure -a")) {
                // dpkg colours its errors whenever stderr is a terminal, and under proot it is.
                2 to "\u001B[1mdpkg:\u001B[0m \u001B[1;31merror:\u001B[0m error creating new" +
                    " backup file '/var/lib/dpkg/status-old': Permission denied\n"
            } else {
                baseline(command)
            }
        }

        val report = harness.distribution.setup()
        val export = harness.distribution.diagnostics.export()

        assertThat(export).contains("dpkg repair pass")
        assertThat(export).contains("exit=2")
        // The evidence that the pass left the database interrupted, read from the host side.
        assertThat(export).contains("dpkg database")
        assertThat(export).contains("status-old=14B")
        assertThat(export).contains("updates=1 pending record(s)")
        // Terminal escapes are presentation, and a diagnostic that quotes dpkg's own line must
        // not push `ESC[1;31m` into the export the user reads.
        assertThat(export).doesNotContain("\u001B")
        assertThat(export).contains("error creating new backup file")
        // The user-facing warning carries the same sentence, without the escapes.
        val warning = report.warnings.first { it.contains("did not fully succeed") }
        assertThat(warning).contains("Permission denied")
        assertThat(warning).doesNotContain("\u001B")
    }

    @Test
    fun `the repair pass surfaces what the fork logged about hard links`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        // A stale line from an earlier run, which the run's own reset must drop: evidence for a
        // failure at minute twelve is not evidence if it is buried under last week's install.
        File(harness.runtime.rootDir, "sigsys-log.txt").writeText(
            "SIGSYS: time=09:00:00 pid=1 comm=apt-get kernel_num=82 pr=82\n",
        )
        harness.scripted.respond = { command ->
            if (command.startsWith("dpkg --configure -a")) {
                // What the fork writes while the pass runs: patch 0003's decision line, then the
                // failure dpkg reported when the emulation was refused anyway.
                File(harness.runtime.rootDir, "sigsys-log.txt").appendText(
                    "LINK: linkat(/var/lib/dpkg/status, /var/lib/dpkg/status-old, flags=0)\n" +
                        "LINK: native linkat refused (13), emulating: /var/lib/dpkg/status ->" +
                        " /var/lib/dpkg/status-old\n",
                )
                2 to "dpkg: error: error creating new backup file: Permission denied\n"
            } else {
                baseline(command)
            }
        }

        harness.distribution.setup()

        val export = harness.distribution.diagnostics.export()
        assertThat(export).contains("blocked-syscall log")
        assertThat(export).contains("native linkat refused (13)")
        // And the stale line is gone: the log describes this run.
        assertThat(export).doesNotContain("kernel_num=82")
    }

    @Test
    fun `a clean run leaves no blocked-syscall event behind`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        File(harness.runtime.rootDir, "sigsys-log.txt").writeText("SIGSYS: stale\n")

        harness.distribution.setup()

        // Nothing was trapped, so nothing is reported — silence is not evidence.
        assertThat(harness.distribution.diagnostics.export()).doesNotContain("blocked-syscall log")
        assertThat(File(harness.runtime.rootDir, "sigsys-log.txt").exists()).isFalse()
    }

    @Test
    fun `setup names the Android group ids the session is in`() = runTest {
        // The group set of the device this was reported on: 3003 inet, 9997 everybody, and the
        // cache and shared groups the platform derives from the app id (here 504, from the app
        // uid 10504 in the report). A shell in the rootfs is in all four — proot passes them
        // through — and a stock group file names none of them, which is what `groups` complains
        // about, once per ID.
        val harness = Harness(
            distro(arch = "arm64"),
            supplementaryGids = { intArrayOf(3003, 9997, 20504, 50504) },
        )

        val report = harness.distribution.setup()

        // A naming gap, not a failure: setup is clean, and the account file says what those
        // numbers are called.
        assertThat(report.warnings).isEmpty()
        val group = File(harness.runtime.rootfsDir, "etc/group")
        assertThat(group.readLines()).containsAtLeast(
            "android_inet:x:3003:",
            "android_everybody:x:9997:",
            "android_cache_504:x:20504:",
            "android_shared_504:x:50504:",
        )
        // The lines the rootfs shipped, and the account line, are still exactly what they were.
        assertThat(group.readLines().first()).isEqualTo("root:x:0:")
        assertThat(group.readLines().count { it.startsWith("ubuntu:") }).isEqualTo(1)
    }

    @Test
    fun `naming the same groups again writes nothing`() = runTest {
        val harness = Harness(distro(arch = "arm64"), supplementaryGids = { intArrayOf(3003, 9997) })
        harness.distribution.setup()
        val group = File(harness.runtime.rootfsDir, "etc/group")
        val named = group.readText()

        // Setup has already named them, so the call the start path makes — on every start, for an
        // install that was written before the names existed — finds nothing to do. That is what
        // keeps opening a terminal from rewriting a rootfs file.
        assertThat(harness.distribution.nameSupplementaryGroups()).isFalse()
        assertThat(group.readText()).isEqualTo(named)
    }

    @Test
    fun `a rootfs with no group file to name is not an error`() = runTest {
        val harness = Harness(distro(arch = "arm64"), supplementaryGids = { intArrayOf(3003) })
        // The state a start can be asked for from NeedsRepair: a rootfs whose account files the
        // extractor never wrote. Nothing to name, and nothing to fail over — the install that
        // writes the file is the thing that has to complain, not this.
        File(harness.runtime.rootfsDir, "etc/group").delete()

        assertThat(harness.distribution.nameSupplementaryGroups()).isFalse()
    }

    @Test
    fun `a group list that cannot be read is a warning, not a failed install`() = runTest {
        val harness = Harness(
            distro(arch = "arm64"),
            supplementaryGids = { throw IllegalStateException("no groups for you") },
        )

        val report = harness.distribution.setup()

        // The userspace is complete and usable; what it loses is the names beside four numbers,
        // so the install says so and carries on rather than sending the user to Repair.
        assertThat(report.warnings.any { it.contains("/etc/group") }).isTrue()
        assertThat(File(harness.runtime.rootfsDir, "etc/passwd").readText())
            .contains("ubuntu:x:10150:10150:Ubuntu:/home/ubuntu:/bin/bash")
    }

    // ------------------------------------------------------------------ the guest's PATH

    @Test
    fun `setup writes the app's PATH into every file a login shell reads it from`() = runTest {
        val harness = Harness(distro(arch = "arm64"))

        harness.distribution.setup()

        val rootfs = harness.runtime.rootfsDir
        val path = UbuntuDistributionManager.LINUX_PATH
        // Four files, because a session's PATH is re-derived four times and the app was writing
        // none of them: fixing fewer leaves a way in that still produces the reported failure.
        //
        // PAM sessions: read by pam_env, and nothing has to run for it to apply.
        assertThat(File(rootfs, "etc/environment").readText()).isEqualTo("PATH=\"$path\"\n")
        // Login shells: sourced by /etc/profile, and it appends only what is missing.
        val profile = File(rootfs, "etc/profile.d/00-eclipse-path.sh").readText()
        assertThat(profile).contains("for dir in ${path.split(":").joinToString(" ")}; do")
        assertThat(profile).contains("export PATH")
        // `su -`, which reads login.defs and not /etc/environment, because a login shell resets the
        // environment it was handed.
        val loginDefs = File(rootfs, "etc/login.defs").readText()
        assertThat(loginDefs).contains("ENV_PATH PATH=$path")
        assertThat(loginDefs).contains("ENV_SUPATH PATH=$path")
        // And sudo, which builds its own PATH from secure_path rather than passing the caller's.
        val sudoers = File(rootfs, "etc/sudoers.d/90-eclipse-ubuntu").readText()
        assertThat(sudoers).contains("secure_path=\"$path\"")
        assertThat(sudoers).contains("ubuntu ALL=(ALL) NOPASSWD: ALL")
    }

    @Test
    fun `rewriting login defs keeps the settings it does not own`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        val loginDefs = File(harness.runtime.rootfsDir, "etc/login.defs")
        // A rootfs whose login.defs is the distribution's, with one of the two keys already set to
        // something short — the state the reported failure comes from.
        loginDefs.writeText("# distro defaults\nUMASK\t\t022\nENV_PATH PATH=/usr/bin:/bin\n")

        harness.distribution.setup()

        val lines = loginDefs.readLines()
        // Everything the file said that this does not own survives, comment included: login.defs is
        // a settings file the user may have edited, and the write is not a rewrite of the file.
        assertThat(lines).contains("# distro defaults")
        assertThat(lines).contains("UMASK\t\t022")
        // And the key it does own is set once, to the app's value — not appended to the old one.
        assertThat(lines.count { it.startsWith("ENV_PATH") }).isEqualTo(1)
        assertThat(lines).contains("ENV_PATH PATH=${UbuntuDistributionManager.LINUX_PATH}")
    }

    // ------------------------------------------------------------------ the programs dpkg needs

    @Test
    fun `a rootfs missing one of dpkg's programs names it, and says it cannot restore it`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        File(harness.runtime.rootfsDir, "usr/bin/rm").delete()

        val report = harness.distribution.setup()

        // Named, not swallowed: "'rm' not found in PATH or not executable" has two causes — a short
        // PATH and an absent file — and this is the second, which no PATH fix reaches.
        assertThat(report.warnings.any { it.contains("usr/bin/rm") }).isTrue()
        // And the check took no repair action it cannot take: no installer is wired here, and the
        // report says so rather than looking like a check that passed.
        assertThat(harness.distribution.diagnostics.export()).contains("essential programs missing")
    }

    @Test
    fun `a missing program comes back out of the pinned archive`() = runTest {
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        val harness = Harness(distro(arch = "arm64"), pinnedTarball = fixture)
        val aptGet = File(harness.runtime.rootfsDir, "usr/bin/apt-get")
        aptGet.delete()

        val report = harness.distribution.setup()

        // The bytes came from where the install got them: the same tarball at the same pin. Nothing
        // in the guest could have done this — asking apt to reinstall a package needs the package
        // manager, and the package manager is the thing that cannot run.
        assertThat(aptGet.readText()).isEqualTo("fake apt\n")
        assertThat(report.warnings.any { it.contains("restored 1 missing program") }).isTrue()
        val log = harness.distribution.diagnostics.export()
        assertThat(log).contains("essential programs restored")
        // Both ends of the evidence: what was missing, and what came back — in the guest's own
        // spelling, which is the one the user's dpkg error names the program in.
        assertThat(log).contains("missing=/usr/bin/apt-get")
        assertThat(log).contains("restored=/usr/bin/apt-get")
    }

    @Test
    fun `a rootfs with every program present downloads nothing`() = runTest {
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        val harness = Harness(distro(arch = "arm64"), pinnedTarball = fixture)

        val report = harness.distribution.setup()

        // The check is one stat per program when nothing is missing — which is the case it is built
        // for, since it runs on every setup and every repair pass. A check that fetched 30 MB to
        // answer "everything is here" would be a worse fault than the one it repairs.
        assertThat(File(harness.rootDir, "downloads/rootfs-arm64.tar.gz").exists()).isFalse()
        assertThat(report.warnings.none { it.contains("restored") }).isTrue()
        assertThat(harness.distribution.diagnostics.export()).doesNotContain("essential programs")
    }

    // ------------------------------------------------------------------ the login probe

    @Test
    fun `the probe reports the login shell's PATH and the programs it cannot find`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            when {
                command.contains("eclipse-uid=") ->
                    0 to "eclipse-uid=0\neclipse-path=/usr/bin:/bin\neclipse-missing=rm\neclipse-missing=tar\n"
                command == "echo eclipse-probe-ok" -> 0 to "eclipse-probe-ok\n"
                command == "whoami" -> 0 to "root\n"
                command.startsWith("getent hosts ") -> 0 to "1.2.3.4 archive.ubuntu.com\n"
                else -> baseline(command)
            }
        }

        val report = harness.distribution.healthProbe()

        assertThat(report.loginUid).isEqualTo(0)
        assertThat(report.loginPath).isEqualTo("/usr/bin:/bin")
        assertThat(report.missingPrograms).containsExactly("rm", "tar")
        // Everything else passed and the install is still not healthy: a userspace whose dpkg
        // cannot find `rm` is one that cannot install, remove or repair a package at all.
        assertThat(report.aptUsable).isTrue()
        assertThat(report.healthy).isFalse()
        assertThat(report.describe()).contains("the package manager cannot find rm, tar")
    }

    @Test
    fun `a login shell that finds everything leaves the install healthy`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            when {
                command.contains("eclipse-uid=") ->
                    0 to "eclipse-uid=0\neclipse-path=${UbuntuDistributionManager.LINUX_PATH}\n"
                command == "echo eclipse-probe-ok" -> 0 to "eclipse-probe-ok\n"
                command == "whoami" -> 0 to "root\n"
                command.startsWith("getent hosts ") -> 0 to "1.2.3.4 archive.ubuntu.com\n"
                else -> baseline(command)
            }
        }

        val report = harness.distribution.healthProbe()

        assertThat(report.missingPrograms).isEmpty()
        assertThat(report.healthy).isTrue()
        assertThat(report.describe()).isEqualTo("healthy")
    }

    @Test
    fun `a whoami capture carrying proot's own teardown lines still reports root`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.scripted.respond = { command ->
            when {
                command.contains("eclipse-uid=") ->
                    0 to "eclipse-uid=0\neclipse-path=${UbuntuDistributionManager.LINUX_PATH}\n"
                command == "echo eclipse-probe-ok" -> 0 to "eclipse-probe-ok\n"
                // A device's own capture, verbatim: the answer first, then what proot wrote to that
                // same pty while it tore itself down. Read whole, all six lines are "the account".
                command == "whoami" -> 0 to
                    "root\n" +
                    "proot warning: cant chmod 'bash': Permission denied\n" +
                    "proot warning: cant chmod 'run-parts': Permission denied\n" +
                    "proot warning: cant chmod 'locale-check': Permission denied\n" +
                    "proot warning: cant chmod 'whoami': Permission denied\n" +
                    "proot error: cant remove " +
                    "'/data/data/dev.eclipse.ssh/files/linux/tmp/exec-8254-bervFx': " +
                    "Directory not empty\n"
                else -> baseline(command)
            }
        }

        val report = harness.distribution.healthProbe()

        // The regression this test exists for, in the user's own words: a userspace that answered
        // `root` and had nothing else wrong was reported as not root, with proot's four warnings
        // quoted back inside the sentence, and the host card withheld over it.
        assertThat(report.account).isEqualTo("root")
        assertThat(report.accountCorrect).isTrue()
        assertThat(report.healthy).isTrue()
        assertThat(report.describe()).isEqualTo("healthy")
    }

    @Test
    fun `a temporary directory nothing can write to and a stale lock are reported, and both gate`() = runTest {
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        // The installer is what can look at the guest's own filesystem, so the probe reads these two
        // through it — a manager with no installer wired reports neither, which is the honest answer
        // for a caller that cannot ask.
        val harness = Harness(distro(arch = "arm64"), pinnedTarball = fixture)
        harness.scripted.respond = { command ->
            when {
                command.contains("eclipse-uid=") ->
                    0 to "eclipse-uid=0\neclipse-path=${UbuntuDistributionManager.LINUX_PATH}\n"
                command == "echo eclipse-probe-ok" -> 0 to "eclipse-probe-ok\n"
                command == "whoami" -> 0 to "root\n"
                else -> baseline(command)
            }
        }
        // What a real install leaves behind. This fixture has neither, and a probe that called every
        // fixture broken would be testing the fixture rather than the probe.
        val rootfs = harness.runtime.rootfsDir
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "run").mkdirs()
        assertThat(harness.distribution.healthProbe().healthy).isTrue()

        // A `touch` where the directory was, and the marker a killed apt leaves behind: nothing
        // holds it — the kernel dropped the lock with the process — but the file is still there, and
        // every dpkg and apt command refuses in about a second because of it.
        File(rootfs, "tmp").deleteRecursively()
        File(rootfs, "tmp").writeText("not a directory\n")
        File(rootfs, "var/lib/dpkg").mkdirs()
        File(rootfs, "var/lib/dpkg/lock-frontend").writeText("")

        val report = harness.distribution.healthProbe()

        // Everything a shell can see still passes: this is the pair of failures nothing else in the
        // userspace notices. A shell starts, `apt-get check` is happy, and only the tool that needs a
        // temporary file or the next package command says anything — in words that name neither /tmp
        // nor the lock.
        assertThat(report.shellWorks).isTrue()
        assertThat(report.aptUsable).isTrue()
        assertThat(report.unusableTempDirs).containsExactly("tmp")
        assertThat(report.staleLocks).containsExactly("var/lib/dpkg/lock-frontend")
        assertThat(report.healthy).isFalse()
        // The sentence names the guest's own path — `/tmp`, not the archive's relative `tmp` — and the
        // lock's sentence says what to do about it, because it is the one Repair clears by itself.
        assertThat(report.describe()).contains("/tmp")
        assertThat(report.describe()).contains("Repair clears it")
    }

    // ------------------------------------------------------------------ what only the host can see

    @Test
    fun `a tmp that is a file is reported unhealthy, and the sentence names the directory`() = runTest {
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        // An installer is what makes these two fields answerable at all: they are asked of the guest's
        // own filesystem, on the host, so a manager built without one reports neither — the honest
        // answer for a caller that cannot look, and a different test.
        val harness = Harness(distro(arch = "arm64"), pinnedTarball = fixture)
        harness.scripted.respond = { command ->
            when {
                // Every field a shell can answer passes, so the one fault planted below is the whole
                // of the report and the sentence is its own words.
                command.contains("eclipse-uid=") ->
                    0 to "eclipse-uid=0\neclipse-path=${UbuntuDistributionManager.LINUX_PATH}\n"
                command == "echo eclipse-probe-ok" -> 0 to "eclipse-probe-ok\n"
                command == "whoami" -> 0 to "root\n"
                else -> baseline(command)
            }
        }
        // Both directories are made here, because a missing one is itself a fault this probe reports:
        // a fixture without them would have two faults, and the sentence below would be about the
        // fixture rather than about the damage.
        val rootfs = harness.runtime.rootfsDir
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "run").mkdirs()

        // The premise, on the very userspace this test then damages: real temporary directories are
        // healthy, and both fields behind that verdict are empty rather than merely ungating.
        val healthy = harness.distribution.healthProbe()
        assertThat(healthy.healthy).isTrue()
        assertThat(healthy.unusableTempDirs).isEmpty()
        assertThat(healthy.staleLocks).isEmpty()
        assertThat(healthy.describe()).isEqualTo("healthy")

        // A tool that wrote a `touch` where the directory was. To the probe that is the same answer
        // as a missing one: not the directory the guest named, so nothing can be written through it.
        File(rootfs, "tmp").deleteRecursively()
        File(rootfs, "tmp").writeText("not a directory\n")

        val report = harness.distribution.healthProbe()

        assertThat(report.unusableTempDirs).containsExactly("tmp")
        // The other field is untouched, so the sentence is this fault's alone — and it names the
        // guest's `/tmp`, not the archive's relative `tmp`, because the reader is looking at their own
        // terminal where `/tmp` is the path.
        assertThat(report.staleLocks).isEmpty()
        assertThat(report.healthy).isFalse()
        assertThat(report.describe())
            .isEqualTo("Ubuntu's temporary directories cannot be written to (/tmp)")
    }

    @Test
    fun `a stale package lock is reported unhealthy, and the probe leaves the file on disk`() = runTest {
        val fixture = TestTarballs.writeRootfsFixture(
            Files.createTempDirectory("linux-fixture").toFile().resolve("rootfs.tar.gz"),
        )
        val harness = Harness(distro(arch = "arm64"), pinnedTarball = fixture)
        harness.scripted.respond = { command ->
            when {
                command.contains("eclipse-uid=") ->
                    0 to "eclipse-uid=0\neclipse-path=${UbuntuDistributionManager.LINUX_PATH}\n"
                command == "echo eclipse-probe-ok" -> 0 to "eclipse-probe-ok\n"
                command == "whoami" -> 0 to "root\n"
                else -> baseline(command)
            }
        }
        // Both temporary directories are real, so that the lock is the only fault the report has to
        // name and the sentence below is not a list of two.
        val rootfs = harness.runtime.rootfsDir
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "run").mkdirs()
        // The file a killed apt leaves behind: nothing holds it — the kernel dropped the lock with the
        // process — and the bytes written here are what proves the probe did not rewrite or clear it.
        val lock = File(rootfs, "var/lib/dpkg/lock-frontend")
        lock.parentFile?.mkdirs()
        lock.writeText("pid 4151 held this\n")

        val report = harness.distribution.healthProbe()

        assertThat(report.staleLocks).containsExactly("var/lib/dpkg/lock-frontend")
        assertThat(report.unusableTempDirs).isEmpty()
        assertThat(report.healthy).isFalse()
        // The sentence names the lock in the guest's own relative spelling, as apt prints it, and says
        // what the way out is — this is the one of the two faults Repair clears by itself.
        assertThat(report.describe())
            .isEqualTo(
                "an interrupted package operation left its lock behind (var/lib/dpkg/lock-frontend)" +
                    " - Repair clears it",
            )
        // Reported, never repaired: the probe is the question and the local rung is the answer, which
        // is the whole reason the two are separate code paths. A file that had been swept would be one
        // fewer thing for Repair to do, and a health check that did the repairing could not be run at
        // any moment without changing what it was run on.
        assertThat(lock.isFile).isTrue()
        assertThat(lock.readText()).isEqualTo("pid 4151 held this\n")
    }

    @Test
    fun `a ladder that times out on every archive is named a timeout, not a mirror failure`() = runTest {
        // Every rung wedges: a pty read that produces nothing and ends nothing, which is what an
        // apt-get on a connection too slow to answer looks like. The ladder then exhausts with no
        // exit code and no output anywhere in its evidence, and the generic sentence it used to
        // produce — "failed on every archive tried" — reads as the mirrors' doing while the mirrors
        // were never heard from. It also let the ladder climb to a reinstall that would time out the
        // same way, which is the failure this type exists to prevent.
        //
        // Each wedged rung parks for the runtime's reader-drain budget (see the wedge test above),
        // so this test is seconds rather than milliseconds: the budget is real, bounded time.
        val harness = Harness(
            distro(arch = "arm64"),
            wedgeOn = { command -> command.startsWith("apt-get update") },
            aptUpdateAttemptTimeoutMs = 250,
        )

        val failure = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(UserspaceFailure.StepTimedOut::class.java)
        val timedOut = failure as UserspaceFailure.StepTimedOut
        // The step, worded as the install screen words it, and every archive the ladder tried.
        assertThat(timedOut.step).isEqualTo("Updating package lists")
        assertThat(timedOut.message).contains("did not finish in time")
        assertThat(timedOut.message).contains("ports.ubuntu.com/ubuntu-ports")
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A minimal extracted rootfs and a real manager over the scripted proot — enough to run
     * [UbuntuDistributionManager.setup] without the installer, which is [RootfsInstallerTest]'s
     * territory. The mirror feed URL points at a localhost port nothing listens on, so the ladder's
     * fetch rung fails instantly instead of touching the network from a unit test.
     */
    // Inner because its scripted proot's default responses come from the outer class's baseline().
    private inner class Harness(
        distro: LinuxDistro,
        scripted: ScriptedPtySpawner = ScriptedPtySpawner(),
        wedgeOn: ((String) -> Boolean)? = null,
        dnsServers: () -> List<String> = { UbuntuDistributionManager.DEFAULT_DNS_SERVERS },
        networkOnline: () -> Boolean = { true },
        mirrorListUrl: String = "http://127.0.0.1:1/mirrors.txt",
        aptUpdateAttemptTimeoutMs: Long = 10 * 60_000L,
        freeBytes: () -> Long = { 0L },
        supplementaryGids: () -> IntArray = { IntArray(0) },
        /**
         * A tarball to hand the manager's installer, for the one repair that goes around apt: a
         * rootfs whose `dpkg` cannot run because a program it needs has gone comes back through the
         * pinned archive, and a test of that needs a pinned archive to come back through.
         *
         * Null everywhere else, and null is not a gap: it is the state the manager has to handle
         * honestly — no installer wired, so a missing program is reported rather than ignored — and
         * the tests below hold it to that.
         */
        pinnedTarball: File? = null,
    ) {
        /** The scripted fake every non-wedged spawn lands in, even when [wedgeOn] wraps it. */
        val scripted: ScriptedPtySpawner = scripted.apply { respond = { baseline(it) } }
        val rootDir = Files.createTempDirectory("ubuntu-distribution").toFile().apply { deleteOnExit() }
        val runtime =
            ProotRuntime(
                rootDir,
                fakeNativeLibraryDir(),
                if (wedgeOn != null) WedgingPtySpawner(scripted, wedgeOn) else scripted,
                storage = RuntimeStorageManager(rootDir, freeBytesProbe = { freeBytes() }),
            )
        val distribution =
            UbuntuDistributionManager(
                distro,
                runtime,
                appUid = 10150,
                appGid = 10150,
                supplementaryGids = supplementaryGids,
                dnsServers = dnsServers,
                mirrorListUrl = mirrorListUrl,
                networkOnline = networkOnline,
                aptUpdateAttemptTimeoutMs = aptUpdateAttemptTimeoutMs,
                // Its own distro record, because the installer verifies what it downloads against
                // the pin — and the pin has to be the fixture's, not the catalog's.
                installer = pinnedTarball?.let { tarball ->
                    RootfsInstaller(
                        rootDir,
                        TestTarballs.fixtureDistro(
                            url = "https://fixtures.invalid/rootfs.tar.gz",
                            sha256 = TestTarballs.sha256(tarball),
                            ubuntuArch = distro.ubuntuArch,
                        ),
                        TestTarballs.serving(tarball),
                    )
                },
            )

        init {
            val rootfs = File(rootDir, "rootfs")
            // What setup rewrites and what it writes into: the account files, and the apt
            // directory sources.list is written to (File.writeText creates no parents).
            File(rootfs, "etc/apt").mkdirs()
            File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            File(rootfs, "etc/group").writeText("root:x:0:\n")
            File(rootfs, "etc/shadow").writeText("root:*:19850:0:99999:7:::\n")
            // The programs the repair prologue checks for before it asks dpkg anything. A real
            // rootfs has them because a real install unpacked them; this one has to say so, or the
            // check would raise its "missing" warning on every test in this file — which is the
            // warning working, not a fixture quirk.
            UbuntuDistributionManager.ESSENTIAL_PROGRAMS.forEach { guest ->
                File(rootfs, guest.removePrefix("/")).apply { parentFile?.mkdirs() }.writeText("")
            }
        }
    }

    /**
     * How the scripted proot answers the commands every setup now runs before its apt steps: the
     * runtime smoke echo must answer its own marker (a silent echo *is* the failure under test
     * elsewhere), and the in-proot resolution check must name the host it was asked for.
     */
    private fun baseline(command: String): Pair<Int, String> = when {
        command == "echo eclipse-runtime-ok" -> 0 to "eclipse-runtime-ok\n"
        command.startsWith("getent hosts ") -> 0 to "1.2.3.4 ${command.removePrefix("getent hosts ")}\n"
        else -> 0 to ""
    }

    /**
     * Whether a command is one of the ladder's rungs: scoped to the sources.list under test. Every
     * other apt command in the setup — the base-package install included — runs unscoped, so it
     * never matches: `sourcelist=/etc/apt/sources.list ` carries a trailing space that only the
     * scoped form has.
     */
    private fun isScopedRung(command: String): Boolean =
        command.contains("Dir::Etc::sourcelist=/etc/apt/sources.list ")

    /**
     * Wraps the scripted spawner with commands that never answer: [wedgeOn] decides per command
     * whether this spawn's pty read parks instead of producing output — the shape of an apt-get
     * wedged on a dead network, which the historical bug waited on forever.
     */
    private class WedgingPtySpawner(
        private val delegate: ScriptedPtySpawner,
        private val wedgeOn: (String) -> Boolean,
    ) : PtySpawner {
        override fun spawn(
            argv: List<String>,
            envp: List<String>,
            cwd: String,
            rows: Int,
            columns: Int,
        ): PtyProcess {
            val command = argv.lastOrNull() ?: ""
            if (wedgeOn(command)) {
                delegate.commands += command
                return WedgedPtyProcess()
            }
            return delegate.spawn(argv, envp, cwd, rows, columns)
        }
    }

    /**
     * A pty whose read parks once, bounded, then ends the stream: wedged, but never forever.
     *
     * The park honors the PtyProcess contract the runtime's cancellation relies on — close() is
     * the one thing that can wake a blocked read — so when the rung's timeout closes the process,
     * the read returns instead of parking out its full budget. A close that left the read parked
     * would model a kernel bug, not a wedged apt-get.
     */
    private class WedgedPtyProcess : PtyProcess {
        private val wake = CountDownLatch(1)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            wake.await(WEDGE_PARK_MS, TimeUnit.MILLISECONDS)
            return -1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length

        override fun resize(rows: Int, columns: Int) = Unit

        override fun awaitExit(): Int = 1

        override fun close() = wake.countDown()
    }

    @Test
    fun `the reason for a failed postinst keeps both lines shadow needed to name the lock`() {
        // The transcript E2E run 35086244789 died on, verbatim: shadow reports a lock it could not
        // take in two lines, and the two are a pair — the second says what failed, the first says
        // which file and whose PID. Keeping two lines kept the second and the verdict, so the
        // evidence named neither the lock file nor the process that held it.
        val reason = dpkgFailureReason(
            """
            Setting up openssh-client (1:8.9p1-3ubuntu0.10) ...
            groupadd: /etc/group.2500379: lock file already used
            groupadd: cannot lock /etc/group; try again later.
            dpkg: error processing package openssh-client (--configure):
             installed openssh-client package post-installation script subprocess returned error exit status 10
            dpkg: dependency problems prevent configuration of openssh-sftp-server:
            Errors were encountered while processing:
             openssh-client
            E: Sub-process /usr/bin/dpkg returned an error code (1)
            """.trimIndent(),
        )

        assertThat(reason).isNotNull()
        assertThat(reason!!).contains("/etc/group.2500379")
        assertThat(reason).contains("cannot lock /etc/group")
        assertThat(reason).contains("dpkg: error processing package openssh-client")
    }

    @Test
    fun `the reason prefers the script that failed over the packages that report the damage`() {
        // The whole cascade, in dpkg's order: the maintainer script's verdict first, then one
        // verdict per package that depended on it. The last verdict describes a consequence; the
        // first names the cause.
        val reason = dpkgFailureReason(
            """
            dpkg: error processing package openssh-client (--configure):
             installed openssh-client package post-installation script subprocess returned error exit status 10
            dpkg: dependency problems prevent configuration of openssh-sftp-server:
             openssh-sftp-server depends on openssh-client (>= 1:8.9p1-3); however:
              Package openssh-client is not configured yet.
            Errors were encountered while processing:
             openssh-client
             openssh-sftp-server
            """.trimIndent(),
        )

        assertThat(reason!!).contains("openssh-client")
        assertThat(reason).doesNotContain("sftp-server")
    }

    @Test
    fun `a failure with no maintainer script falls back to the last verdict`() {
        // An unpack failure, which dpkg reports the same way but with nothing of the package's own
        // to say. There is no script verdict to prefer, so the last one stands — and the noise
        // filter still leaves the report reading as a cause rather than as a progress log.
        val reason = dpkgFailureReason(
            """
            Unpacking libssl3:amd64 (3.0.2-0ubuntu1.10) ...
            dpkg-deb: error: archive './libssl3.deb' is not a debian format archive
            dpkg: error processing archive ./libssl3.deb (--unpack):
             dpkg-deb --control subprocess returned error exit status 2
            """.trimIndent(),
        )

        assertThat(reason!!).contains("dpkg: error processing archive")
        assertThat(reason).doesNotContain("Unpacking libssl3")
    }

    @Test
    fun `a failed command with no output has no reason to report`() {
        assertThat(dpkgFailureReason(null)).isNull()
        assertThat(dpkgFailureReason("")).isNull()
        assertThat(dpkgFailureReason("\n \n")).isNull()
    }

    private fun distro(arch: String, release: String = "jammy") =
        LinuxDistro(
            id = "ubuntu-22.04",
            displayName = "Ubuntu 22.04 LTS",
            release = release,
            ubuntuArch = arch,
            rootfsTarballUrl = "https://fixtures.invalid/rootfs.tar.gz",
            rootfsSha256 = "00",
        )

    private companion object {
        /**
         * The base set's one bulk apt command, spelled out rather than rebuilt from the manager's
         * own list: the extras tests have to let this one through to reach the step under test, and
         * a helper that read the constant out of the class would follow it wherever it went — which
         * is the one thing a test asserting "this command and no other" must not do.
         */
        const val BASE_PACKAGES_COMMAND =
            "apt-get install -y --no-install-recommends " +
                "apt-utils bash-completion ca-certificates cron curl git htop openssh-client " +
                "sudo unzip wget zip"

        /**
         * How long a wedged read parks before releasing. Bounded so the test terminates on this
         * branch, where the runtime's read loop cannot be cancelled; long enough that the rung's
         * real 250ms budget is what a cancellable loop would enforce.
         */
        private const val WEDGE_PARK_MS = 5_000L
    }
}
