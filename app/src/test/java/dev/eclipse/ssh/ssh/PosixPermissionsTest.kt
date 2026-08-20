package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the two directions of a file mode against each other.
 *
 * The listing rendered a mode as octal and the permissions picker sent it back as decimal, so a file
 * shown as 644 became octal 1204 the moment the user chose "644" from the picker. Nothing failed and
 * nothing logged; the row simply came back reading `1204`, with the owner no longer able to read their
 * own file. These tests exist so the round trip is checked rather than assumed.
 */
class PosixPermissionsTest {

    @Test
    fun `a mode renders as three octal digits`() {
        assertThat(formatPermissions(0b110_100_100)).isEqualTo("644")
        assertThat(formatPermissions(0b111_101_101)).isEqualTo("755")
        assertThat(formatPermissions(0b111_111_111)).isEqualTo("777")
    }

    @Test
    fun `a mode with a leading zero keeps its width`() {
        // Unpadded this printed "44", which reads as a two-digit mode rather than "no owner access".
        assertThat(formatPermissions(0b000_100_100)).isEqualTo("044")
        assertThat(formatPermissions(0)).isEqualTo("000")
    }

    @Test
    fun `the file type bits are not part of the permissions`() {
        // What a server actually reports for a regular file with 644: S_IFREG or 0o644.
        assertThat(formatPermissions("100644".toInt(8))).isEqualTo("644")
        // ...and for a directory with 755.
        assertThat(formatPermissions("40755".toInt(8))).isEqualTo("755")
    }

    @Test
    fun `permissions read the way ls prints them`() {
        assertThat(symbolicPermissions(0b110_100_100)).isEqualTo("rw-r--r--")
        assertThat(symbolicPermissions(0b111_101_101)).isEqualTo("rwxr-xr-x")
        assertThat(symbolicPermissions(0b111_000_000)).isEqualTo("rwx------")
        assertThat(symbolicPermissions(0b110_000_000)).isEqualTo("rw-------")
        assertThat(symbolicPermissions(0b111_111_111)).isEqualTo("rwxrwxrwx")
        assertThat(symbolicPermissions(0)).isEqualTo("---------")
    }

    @Test
    fun `the picker offers the modes it always offered`() {
        // Same five choices as before the fix, in the same order. The bug was in what they sent, not
        // in which ones existed, and a fix that quietly dropped one would be a feature removed.
        assertThat(PERMISSION_PRESETS.map { it.octal })
            .containsExactly("644", "755", "700", "600", "777")
            .inOrder()
    }

    @Test
    fun `every preset sends the mode its label promises`() {
        PERMISSION_PRESETS.forEach { preset ->
            assertThat(preset.mode).isEqualTo(preset.octal.toInt(8))
            assertThat(preset.label).isEqualTo("${preset.octal} · ${symbolicPermissions(preset.mode)}")
        }
    }

    @Test
    fun `the preset labels spell out the permissions they set`() {
        assertThat(PERMISSION_PRESETS.map { it.label }).containsExactly(
            "644 · rw-r--r--",
            "755 · rwxr-xr-x",
            "700 · rwx------",
            "600 · rw-------",
            "777 · rwxrwxrwx",
        ).inOrder()
    }

    @Test
    fun `no preset is the decimal reading of its own digits`() {
        // The whole defect in one assertion: 644 the number is not 644 the mode.
        assertThat(PERMISSION_PRESETS.map { it.mode })
            .containsNoneOf(644, 755, 700, 600, 777)
    }

    @Test
    fun `every preset is accepted as a permission bitfield`() {
        PERMISSION_PRESETS.forEach { preset ->
            assertThat(requirePermissionBits(preset.mode)).isEqualTo(preset.mode)
        }
    }

    @Test
    fun `a decimal mode is refused`() {
        // Each of the five as it used to be written. All are valid modes, which is why only an
        // explicit check can catch them: every one sets the sticky bit.
        listOf(644, 755, 700, 600, 777).forEach { decimal ->
            assertThrows(IllegalArgumentException::class.java) { requirePermissionBits(decimal) }
        }
    }

    @Test
    fun `the special bits are refused rather than passed through`() {
        listOf("1777", "2755", "4755").forEach { withSpecialBit ->
            assertThrows(IllegalArgumentException::class.java) {
                requirePermissionBits(withSpecialBit.toInt(8))
            }
        }
    }

    @Test
    fun `a mode a server reported is refused as an argument`() {
        // Guards the plausible mistake of chmod'ing a file back to the mode it was read with,
        // S_IFREG bits and all, which would ask the server to change the file's type.
        assertThrows(IllegalArgumentException::class.java) { requirePermissionBits("100644".toInt(8)) }
    }
}
