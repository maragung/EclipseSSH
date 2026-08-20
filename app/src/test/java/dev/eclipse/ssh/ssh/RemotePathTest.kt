package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Remote path arithmetic. Both helpers feed straight into SFTP calls, where a doubled "//"
 * or a wrong home directory is the difference between a working transfer and an
 * "Unable to open" failure the user cannot diagnose.
 */
class RemotePathTest {

    @Test
    fun `root user gets slash root, not slash home slash root`() {
        // /home/root does not exist on a conventional Unix system.
        assertThat(fallbackHome("root")).isEqualTo("/root")
    }

    @Test
    fun `ordinary users get their home under slash home`() {
        assertThat(fallbackHome("deploy")).isEqualTo("/home/deploy")
    }

    @Test
    fun `a blank username falls back to the filesystem root`() {
        assertThat(fallbackHome("")).isEqualTo("/")
        assertThat(fallbackHome("   ")).isEqualTo("/")
    }

    @Test
    fun `joining onto the root directory does not double the separator`() {
        assertThat(joinRemote("/", "etc")).isEqualTo("/etc")
    }

    @Test
    fun `joining onto a normal directory inserts one separator`() {
        assertThat(joinRemote("/srv/releases", "build.tar.gz")).isEqualTo("/srv/releases/build.tar.gz")
    }

    @Test
    fun `a trailing separator on the base is not duplicated`() {
        assertThat(joinRemote("/srv/releases/", "build.tar.gz")).isEqualTo("/srv/releases/build.tar.gz")
    }

    @Test
    fun `nested relative children keep their own separators`() {
        assertThat(joinRemote("/var/www", "assets/img/logo.png")).isEqualTo("/var/www/assets/img/logo.png")
    }

    // --- directory entry names the server supplies ---

    @Test
    fun `ordinary filenames are acted on`() {
        listOf("hello.txt", "..hidden", "...", "a b", "naïve", "-", "file.tar.gz")
            .forEach { name ->
                assertThat(isPlainEntryName(name)).isTrue()
            }
    }

    /**
     * The entries a walk must skip.
     *
     * "." and ".." are every directory's own, and following them turns a tree into a loop. A name
     * carrying "/" or NUL cannot exist on a POSIX filesystem at all, so one arriving over the wire is
     * a broken or hostile server — and it matters because `syncFromRemote` walks the relative path
     * built from these names into the SAF tree the user picked, one segment at a time.
     */
    @Test
    fun `dot dotdot and names carrying a separator are skipped`() {
        listOf("", ".", "..", "/", "a/b", "../../etc/passwd", "..\u0000", "a\u0000b")
            .forEach { name ->
                assertThat(isPlainEntryName(name)).isFalse()
            }
    }
}
