package dev.eclipse.ssh.data.fs

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.linux.RootfsPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Ubuntu userspace browsed as files, against a rootfs built by hand in a temp directory.
 *
 * A rootfs is a 30 MB download, so none of this installs one: [UbuntuRootfsLocator] is the seam the
 * provider reads its tree through, and these tests hand it a tree the test wrote. What that buys is
 * the ability to build the cases that matter and cannot be found in a real image on demand — a link
 * that climbs out of the root, a relative link through three directories, a directory that reports
 * itself as its own parent.
 *
 * The assertions are about two promises, and the second is the one with teeth. *Guest paths out*:
 * every path the provider hands the UI is a path inside Ubuntu, so a row can never show the app's own
 * private storage layout. *Nothing escapes*: a rootfs' absolute links mean the guest's `/run`, not the
 * device's, and a listing built with host semantics would browse the phone while claiming to browse
 * Ubuntu — read *and* write, since the same mapping decides where a save lands.
 */
class UbuntuFileSystemProviderTest {

    // `@get:Rule` alone, and not the `@get:Rule @JvmField` pair the Robolectric suites here use: a
    // `@JvmField` property has no getter to carry the annotation, so Kotlin drops it and JUnit never
    // creates the folder — every test in the class then dies on `newFolder` with "the temporary folder
    // has not yet been created", which reads as fifteen broken tests rather than one broken rule.
    @get:Rule
    val temp = TemporaryFolder()

    private fun providerFor(root: File): UbuntuFileSystemProvider =
        UbuntuFileSystemProvider { root }

    /**
     * A rootfs shaped the way Ubuntu Base is: a usrmerged `/bin` that is a link to `usr/bin`, a home
     * for the `ubuntu` account, and the three directories proot binds the device's own over.
     */
    private fun newRootfs(): File {
        val root = temp.newFolder("rootfs")
        File(root, "usr/bin").mkdirs()
        File(root, "home/ubuntu").mkdirs()
        // /bin and /lib are absolute links in every usrmerged image, which is the case a naive
        // File(root, path) walk gets wrong.
        Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Paths.get("/usr/bin"))
        File(root, "proc").mkdirs()
        File(root, "dev").mkdirs()
        File(root, "sys").mkdirs()
        return root
    }

    private fun write(root: File, guestPath: String, text: String): File {
        val file = File(root, guestPath.removePrefix("/"))
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    @Test
    fun `homePath is the ubuntu account's home, and the root before setup has made one`() = runBlocking {
        val root = newRootfs()
        assertThat(providerFor(root).homePath()).isEqualTo("/home/ubuntu")

        // Between extraction and setup() the account does not exist yet, and a home that is not there
        // would open the session on an error instead of on the tree.
        File(root, "home/ubuntu").delete()
        assertThat(providerFor(root).homePath()).isEqualTo("/")
    }

    @Test
    fun `a listing names guest paths, never the app's own storage`() = runBlocking {
        val root = newRootfs()
        write(root, "home/ubuntu/notes.md", "hello")

        val entries = providerFor(root).list("/home/ubuntu")

        assertThat(entries.map { it.name }).containsExactly("notes.md")
        val notes = entries.single()
        // The whole point: the row says /home/ubuntu/notes.md, not the sandbox path it really is.
        assertThat(notes.path).isEqualTo("/home/ubuntu/notes.md")
        assertThat(notes.path).doesNotContain(root.absolutePath)
        assertThat(notes.isDirectory).isFalse()
        assertThat(notes.size).isEqualTo(5L)
    }

    @Test
    fun `an absolute link resolves inside the root, so bin is usr bin to both paths`() = runBlocking<Unit> {
        val root = newRootfs()
        write(root, "usr/bin/hello", "#!/bin/sh\n")

        // /bin -> /usr/bin. Under host semantics this would look for the *device's* /usr/bin.
        assertThat(providerFor(root).read("/bin/hello").decodeToString()).isEqualTo("#!/bin/sh\n")
        assertThat(providerFor(root).read("/usr/bin/hello").decodeToString()).isEqualTo("#!/bin/sh\n")

        // And the directory lists through the link, so browsing /bin is browsing /usr/bin.
        assertThat(providerFor(root).list("/bin").map { it.name }).containsExactly("hello")
    }

    @Test
    fun `a relative link is resolved against its own directory, not the root`() = runBlocking {
        val root = newRootfs()
        write(root, "home/ubuntu/workspace/main.kt", "fun main() {}")
        // A relative link, which is what a user's own `ln -s` makes and what `..` inside it means.
        Files.createSymbolicLink(
            File(root, "home/ubuntu/src").toPath(),
            java.nio.file.Paths.get("workspace"),
        )

        assertThat(providerFor(root).read("/home/ubuntu/src/main.kt").decodeToString())
            .isEqualTo("fun main() {}")
    }

    @Test
    fun `a relative link that climbs above the root is refused, not followed onto the device`() {
        val root = newRootfs()
        // Five levels up from /home/ubuntu is past /, and on a real device it is the app's own
        // sandbox — or /data, or wherever the phone keeps what the guest was never given.
        Files.createSymbolicLink(
            File(root, "home/ubuntu/escape").toPath(),
            java.nio.file.Paths.get("../../../../../etc/hosts"),
        )

        val failure = assertThrows(IOException::class.java) {
            runBlocking { providerFor(root).list("/home/ubuntu/escape") }
        }
        assertThat(failure.message).contains("climbs above the root filesystem")
    }

    @Test
    fun `a parent traversal out of the root is refused`() {
        val root = newRootfs()

        assertThrows(IOException::class.java) {
            runBlocking { providerFor(root).list("/..") }
        }
        assertThrows(IOException::class.java) {
            runBlocking { providerFor(root).write("/../escape", ByteArray(0)) }
        }

        // `..` that stays inside is ordinary POSIX and is served: /home/ubuntu/../etc is /etc, and a
        // path the guest itself would accept is one this must not refuse.
        assertThat(File(root, "escape").exists()).isFalse()
    }

    @Test
    fun `a link that points at itself is a loop, not a hang`() {
        val root = newRootfs()
        Files.createSymbolicLink(File(root, "spin").toPath(), java.nio.file.Paths.get("/spin"))
        val provider = providerFor(root)

        // stat answers null for a path it cannot resolve, which is the contract for "nothing there".
        assertThat(runBlocking { provider.stat("/spin") }).isNull()

        // Drawn in a listing it is still an entry — dropping it would leave a hole — and it is walking
        // it that has to stop, at Linux' own MAXSYMLINKS rather than at a stack overflow.
        assertThat(runBlocking { provider.list("/") }.map { it.name }).contains("spin")
        assertThrows(IOException::class.java) {
            runBlocking { provider.list("/spin") }
        }
        assertThat(RootfsPaths.MAX_SYMLINK_HOPS).isEqualTo(40)
    }

    @Test
    fun `the device's own dev, proc and sys are not offered as the guest's`() = runBlocking {
        val root = newRootfs()
        write(root, "etc/hostname", "ubuntu\n")

        val atRoot = providerFor(root).list("/")

        // They exist on disk — proot bind-mounts over them — but what is under them in the tarball is
        // mount-point stubs, and the Files tab has "This device" for the real ones.
        assertThat(atRoot.map { it.name }).contains("etc")
        assertThat(atRoot.map { it.name }).doesNotContain("proc")
        assertThat(atRoot.map { it.name }).doesNotContain("dev")
        assertThat(atRoot.map { it.name }).doesNotContain("sys")

        val failure = assertThrows(IOException::class.java) {
            runBlocking { providerFor(root).list("/proc") }
        }
        assertThat(failure.message).contains("device's own")
    }

    @Test
    fun `a directory called proc deeper in the tree is the user's own and is listed`() = runBlocking {
        val root = newRootfs()
        write(root, "home/ubuntu/proc/notes.txt", "mine")

        val entries = providerFor(root).list("/home/ubuntu")

        // The hiding rule is about the session's borrowed mounts at the root, not about the name.
        assertThat(entries.map { it.name }).contains("proc")
    }

    @Test
    fun `a write carries the conflict guard the editor relies on`() = runBlocking {
        val root = newRootfs()
        val file = write(root, "home/ubuntu/notes.md", "first")
        val provider = providerFor(root)
        val readAt = Files.getLastModifiedTime(file.toPath()).toMillis()

        // The file changed after the editor read it: a question for the user, never a silent overwrite.
        file.writeText("second")
        file.setLastModified(readAt + 5_000)
        assertThrows(FsModificationConflictException::class.java) {
            runBlocking { provider.write("/home/ubuntu/notes.md", "third".toByteArray(), readAt) }
        }
        assertThat(file.readText()).isEqualTo("second")

        // A file deleted since is not a conflict: writing it back is what the user asked for.
        file.delete()
        runBlocking { provider.write("/home/ubuntu/notes.md", "third".toByteArray(), readAt) }
        assertThat(file.readText()).isEqualTo("third")
    }

    @Test
    fun `a copy into a linked directory lands inside the rootfs`() = runBlocking {
        val root = newRootfs()
        write(root, "etc/notes.md", "hello")
        File(root, "srv").mkdirs()
        Files.createSymbolicLink(File(root, "srv/data").toPath(), java.nio.file.Paths.get("/home/ubuntu"))

        providerFor(root).copy("/etc/notes.md", "/srv/data")

        // Through the link, so the bytes are in home/ubuntu — not in whatever /home/ubuntu means to
        // the host, which is the write-side of the same escape a listing would have leaked.
        assertThat(File(root, "home/ubuntu/notes.md").readText()).isEqualTo("hello")
        assertThat(File(root, "srv/notes.md").exists()).isFalse()
    }

    @Test
    fun `names that are paths, or nothing, are refused`() = runBlocking {
        val root = newRootfs()
        val provider = providerFor(root)

        for (name in listOf("..", ".", "", "a/b", "\u0000")) {
            assertThrows(IOException::class.java) {
                runBlocking { provider.createFile("/home/ubuntu", name) }
            }
            assertThrows(IOException::class.java) {
                runBlocking { provider.rename("/home/ubuntu/notes.md", name) }
            }
        }
    }

    @Test
    fun `permissions are the device's own mode bits, and setting them is a real chmod`() = runBlocking {
        val root = newRootfs()
        val script = write(root, "home/ubuntu/run.sh", "#!/bin/sh\n")
        val provider = providerFor(root)

        runBlocking { provider.setPermissions("/home/ubuntu/run.sh", 0b111_101_101) }

        assertThat(Files.getPosixFilePermissions(script.toPath()))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"))
        // And the listing reports it back, which is what makes the Permissions row meaningful here.
        assertThat(provider.stat("/home/ubuntu/run.sh")?.permissions).isEqualTo("755")
    }

    @Test
    fun `a device with no rootfs says so, and says it in words`() {
        val provider = UbuntuFileSystemProvider { null }
        assertThat(provider.isAvailable()).isFalse()

        val failure = assertThrows(IOException::class.java) {
            runBlocking { provider.list("/") }
        }
        assertThat(failure.message).contains("No Ubuntu userspace is installed")
    }

    @Test
    fun `availability follows the locator, so an install is picked up without a restart`() {
        val root = newRootfs()
        var located: File? = null
        val provider = UbuntuFileSystemProvider { located }

        assertThat(provider.isAvailable()).isFalse()
        located = root
        assertThat(provider.isAvailable()).isTrue()
    }

    @Test
    fun `search walks guest paths and skips what it cannot read`() = runBlocking {
        val root = newRootfs()
        write(root, "home/ubuntu/workspace/main.kt", "fun main() {}")
        write(root, "home/ubuntu/workspace/deep/notes.md", "hi")
        write(root, "etc/notes.conf", "no")

        val found = providerFor(root).search("/home/ubuntu", "notes", 50)

        assertThat(found.map { it.path }).containsExactly("/home/ubuntu/workspace/deep/notes.md")
        assertThat(found.single().path).doesNotContain(root.absolutePath)
    }
}
