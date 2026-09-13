package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
 * stays, and exhausting the ladder names every archive tried.
 *
 * The NodeSource contract is here too: the suite is `nodistro` and the repo pinned to one major —
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
    fun `the update command pins retries, languages and a connect timeout`() {
        // The exact strings the pty receives, asserted so a flag nobody can test end to end is at
        // least pinned as written.
        val plain =
            aptUpdateCommand(AptUpdateAttempt("http://ports.ubuntu.com/ubuntu-ports", forceIpv4 = false))
        assertThat(plain).isEqualTo(
            "apt-get update -o Acquire::Retries=3 -o Acquire::Languages=none -o Acquire::http::Timeout=30",
        )
        assertThat(
            aptUpdateCommand(AptUpdateAttempt("http://ports.ubuntu.com/ubuntu-ports", forceIpv4 = true)),
        ).isEqualTo("$plain -o Acquire::ForceIPv4=true")
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
    fun `an empty or unusable feed body yields no mirrors`() {
        val primary = "http://ports.ubuntu.com/ubuntu-ports"
        assertThat(parseMirrorList("", primary)).isEmpty()
        assertThat(parseMirrorList("https://only.example/ubuntu\nnot a url\n", primary)).isEmpty()
    }

    // ------------------------------------------------------------------ end to end, scripted proot

    @Test
    fun `a failing primary archive walks the ladder to a mirror that works and stays`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        var plainUpdates = 0
        harness.spawner.respond = { command ->
            when {
                // The NodeSource update is scoped to its own list and not part of the ladder.
                command.contains("Dir::Etc::sourcelist") -> 0 to ""
                command.startsWith("apt-get update") -> {
                    plainUpdates++
                    // Primary, primary over IPv4, first built-in: all fail. The second built-in
                    // works, and its sources.list is the one that must remain on disk.
                    if (plainUpdates <= 3) 100 to "Err:1 http://ports.ubuntu.com jammy Release\n" else 0 to ""
                }
                else -> 0 to ""
            }
        }

        harness.distribution.setup()

        val updates =
            harness.spawner.commandsWith
                .map { it.second }
                .filter { it.startsWith("apt-get update") && !it.contains("Dir::Etc::sourcelist") }
        assertThat(updates).hasSize(4)
        assertThat(updates[0]).doesNotContain("ForceIPv4")
        assertThat(updates[1]).contains("ForceIPv4=true")

        // The winning rung's base is what Repair and every later install reuse.
        val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
        assertThat(sources).contains("mirror.nju.edu.cn/ubuntu-ports")
        assertThat(sources).doesNotContain("ports.ubuntu.com")
    }

    @Test
    fun `exhausting the ladder fails the step naming every archive tried`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.spawner.respond = { command ->
            if (command.startsWith("apt-get update") && !command.contains("Dir::Etc::sourcelist")) {
                100 to "Err:1 … Could not connect"
            } else {
                0 to ""
            }
        }

        val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IOException::class.java)
        // "Could not update" alone would hide which mirror said why; every rung is named.
        assertThat(thrown!!.message).contains("ports.ubuntu.com")
        assertThat(thrown.message).contains("mirrors.ustc.edu.cn")
        assertThat(thrown.message).contains("mirror.nju.edu.cn")
        assertThat(thrown.message).contains("Could not connect")
    }

    @Test
    fun `the NodeSource entry uses the distro-agnostic suite and a pinned major`() = runTest {
        val harness = Harness(distro(arch = "arm64"))
        harness.distribution.setup()

        val entry =
            harness.spawner.commandsWith
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
        assertThat(harness.spawner.commandsWith.map { it.second }.none { "nodesource" in it }).isTrue()
        assertThat(report.warnings.any { "armhf" in it }).isTrue()
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A minimal extracted rootfs and a real manager over the scripted proot — enough to run
     * [UbuntuDistributionManager.setup] without the installer, which is [RootfsInstallerTest]'s
     * territory. The mirror feed URL points at a localhost port nothing listens on, so the ladder's
     * fetch rung fails instantly instead of touching the network from a unit test.
     */
    private class Harness(distro: LinuxDistro) {
        val spawner = ScriptedPtySpawner()
        val rootDir = Files.createTempDirectory("ubuntu-distribution").toFile().apply { deleteOnExit() }
        val runtime = ProotRuntime(rootDir, "/fake/native/lib", spawner)
        val distribution =
            UbuntuDistributionManager(
                distro,
                runtime,
                appUid = 10150,
                appGid = 10150,
                mirrorListUrl = "http://127.0.0.1:1/mirrors.txt",
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

    private fun distro(arch: String, release: String = "jammy") =
        LinuxDistro(
            id = "ubuntu-22.04",
            displayName = "Ubuntu 22.04 LTS",
            release = release,
            ubuntuArch = arch,
            rootfsTarballUrl = "https://fixtures.invalid/rootfs.tar.gz",
            rootfsSha256 = "00",
        )
}
