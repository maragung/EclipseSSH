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
 * The NodeSource contract is here as well: the suite is `nodistro` and the repo pinned to one major —
 * the entry this replaced named `jammy` and 404'd on every install — and armhf devices skip the
 * step entirely, because NodeSource publishes no 32-bit ARM packages.
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
            harness.scripted.commandsWith
                .map { it.second }
                .filter { it.startsWith("apt-get update") && isScopedRung(it) }
        assertThat(scoped).hasSize(4)
        assertThat(scoped[0]).doesNotContain("ForceIPv4")
        assertThat(scoped[1]).contains("ForceIPv4=true")
        assertThat(scoped.drop(2).map { it.contains("ForceIPv4") }).containsExactly(false, false).inOrder()

        // Exactly one unscoped update: the confirmation after the win.
        assertThat(
            harness.scripted.commandsWith
                .map { it.second }
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
        File(harness.runtime.rootDir, "sigsys-log.txt").writeText(
            listOf(
                "SIGSYS: time=15:57:49 pid=1234 comm=apt-get kernel_num=82 pr=82",
                "SIGSYS: time=15:57:50 pid=1234 comm=dpkg kernel_num=82 pr=82",
            ).joinToString("\n"),
        )
        harness.scripted.respond = { command ->
            if (command.startsWith("apt-get update") && isScopedRung(command)) {
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
            harness.scripted.commandsWith
                .map { it.second }
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
            harness.scripted.commandsWith
                .map { it.second }
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
            harness.scripted.commandsWith
                .map { it.second }
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
        assertThat(harness.scripted.commandsWith).isEmpty()
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
            harness.scripted.commandsWith
                .map { it.second }
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
            harness.scripted.commandsWith
                .map { it.second }
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
                command.contains("python3-pip sudo") -> {
                    fullListAttempts++
                    100 to "E: Unable to locate package some-package"
                }
                // One package is simply not available on this mirror: the per-package fallback
                // turns it into a warning while the other thirteen install.
                command == "apt-get install -y --no-install-recommends git" ->
                    100 to "E: Unable to locate package git"
                else -> baseline(command)
            }
        }

        val report = harness.distribution.setup()

        // The list ran whole, then once more with --fix-missing, before the per-package fallback.
        assertThat(fullListAttempts).isEqualTo(2)
        assertThat(report.warnings.any { it.contains("package 'git' was not installed") }).isTrue()
    }

    @Test
    fun `the dpkg repair pass runs before the first archive is asked anything`() = runTest {
        val harness = Harness(distro(arch = "arm64"))

        harness.distribution.setup()

        val commands = harness.scripted.commandsWith.map { it.second }
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
            harness.scripted.commandsWith
                .map { it.second }
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
    fun `the NodeSource entry uses the distro-agnostic suite and a pinned major`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.distribution.setup()

        val entry =
            harness.scripted.commandsWith
                .map { it.second }
                .first { "nodesource" in it }
        // nodistro: NodeSource publishes one suite for every distribution, not one per codename —
        // the old jammy entry 404'd. And the major is pinned, so Node.js does not drift.
        assertThat(entry).contains("https://deb.nodesource.com/node_24.x nodistro main")
        assertThat(entry).contains("signed-by=/usr/share/keyrings/nodesource.gpg")
        // The update is scoped to the NodeSource list alone; re-fetching the Ubuntu archive here
        // would re-run the whole ladder's download for one new repository.
        assertThat(entry).contains("-o Dir::Etc::sourcelist=/etc/apt/sources.list.d/nodesource.list")
    }

    @Test
    fun `armhf devices skip NodeSource with a warning, not a failure`() = runTest {
        val harness = Harness(distro(arch = "armhf"))
        val report = harness.distribution.setup()

        // NodeSource publishes amd64 and arm64 only; a 32-bit ARM phone cannot run its packages,
        // so the step must not run at all — and must not fail the install over it.
        assertThat(harness.scripted.commandsWith.map { it.second }.none { "nodesource" in it }).isTrue()
        assertThat(report.warnings.any { "armhf" in it }).isTrue()
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
    ) {
        /** The scripted fake every non-wedged spawn lands in, even when [wedgeOn] wraps it. */
        val scripted: ScriptedPtySpawner = scripted.apply { respond = { baseline(it) } }
        val rootDir = Files.createTempDirectory("ubuntu-distribution").toFile().apply { deleteOnExit() }
        val runtime =
            ProotRuntime(
                rootDir,
                "/fake/native/lib",
                if (wedgeOn != null) WedgingPtySpawner(scripted, wedgeOn) else scripted,
                storage = RuntimeStorageManager(rootDir, freeBytesProbe = { freeBytes() }),
            )
        val distribution =
            UbuntuDistributionManager(
                distro,
                runtime,
                appUid = 10150,
                appGid = 10150,
                dnsServers = dnsServers,
                mirrorListUrl = mirrorListUrl,
                networkOnline = networkOnline,
                aptUpdateAttemptTimeoutMs = aptUpdateAttemptTimeoutMs,
            )

        init {
            val rootfs = File(rootDir, "rootfs")
            // What setup rewrites and what it writes into: the account files, and the apt
            // directory sources.list is written to (File.writeText creates no parents).
            File(rootfs, "etc/apt").mkdirs()
            File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            File(rootfs, "etc/group").writeText("root:x:0:\n")
            File(rootfs, "etc/shadow").writeText("root:*:19850:0:99999:7:::\n")
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
     * Whether a command is one of the ladder's rungs: scoped to the sources.list under test. The
     * NodeSource update is scoped to its own list (`sources.list.d/nodesource.list`) and so, by
     * the space after `sources.list`, never matches.
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
                delegate.commandsWith += (argv.contains("-0")) to command
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
         * How long a wedged read parks before releasing. Bounded so the test terminates on this
         * branch, where the runtime's read loop cannot be cancelled; long enough that the rung's
         * real 250ms budget is what a cancellable loop would enforce.
         */
        private const val WEDGE_PARK_MS = 5_000L
    }
}
