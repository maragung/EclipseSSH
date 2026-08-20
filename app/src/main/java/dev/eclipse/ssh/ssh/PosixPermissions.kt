package dev.eclipse.ssh.ssh

/**
 * The nine POSIX permission bits: owner, group and other, each read/write/execute.
 *
 * Everything above them in a `st_mode` — the file-type bits, setuid, setgid, sticky — is deliberately
 * outside what this app reads or writes. It shows permissions and it offers a small set of presets;
 * it has no UI for the special bits, and quietly carrying them in either direction would mean
 * displaying or setting something the user was never shown.
 */
internal const val PERMISSION_BITS = 0x1FF

/**
 * Renders the permission bits of [mode] the way `ls` and `chmod` name them: three octal digits.
 *
 * Padded, because a mode is spoken as three digits. Unpadded, `0o044` printed as "44", which reads as
 * a truncated number rather than a permission set, and 0 printed as "0".
 */
internal fun formatPermissions(mode: Int): String =
    (mode and PERMISSION_BITS).toString(8).padStart(3, '0')

/**
 * Renders the permission bits of [mode] the way `ls -l` does, e.g. `rw-r--r--`.
 *
 * Exists so that a preset's label is *derived* from the bits the app will actually send, rather than
 * written out beside them by hand. A hand-written label is how [PERMISSION_PRESETS] came to promise
 * one thing and do another for as long as it did — see the note there.
 */
internal fun symbolicPermissions(mode: Int): String = buildString {
    for (shift in 6 downTo 0 step 3) {
        val triple = (mode shr shift) and 0b111
        append(if (triple and 0b100 != 0) 'r' else '-')
        append(if (triple and 0b010 != 0) 'w' else '-')
        append(if (triple and 0b001 != 0) 'x' else '-')
    }
}

/**
 * Returns [mode] if it is a permission bitfield, and throws if it is one of the three-digit decimal
 * numbers that look like one.
 *
 * The distinction has no other guard anywhere: an SFTP `ATTRS` permissions field takes any 32-bit
 * value, and decimal 644 is the perfectly valid octal 1204 — sticky bit set, owner unable to read
 * their own file. So the mistake could not fail; it could only be applied. What makes it catchable is
 * that the numbers people write as modes (644, 755, 700, 600, 777) all land above 0o777 when read as
 * decimal, and therefore all carry a bit outside [PERMISSION_BITS].
 *
 * Returns the mode so it can be used inline at the call site, where the check is worth seeing.
 */
internal fun requirePermissionBits(mode: Int): Int {
    require(mode and PERMISSION_BITS.inv() == 0) {
        "Permissions must be the nine POSIX bits; got 0x" + Integer.toHexString(mode) +
            ". Octal digits like 644 are not decimal 644."
    }
    return mode
}

/**
 * One entry in the permissions picker: the bits, and the two ways of naming them.
 *
 * Only [mode] is given. Both names are computed from it, so the row a user taps and the value the
 * server receives cannot disagree.
 */
internal class PermissionPreset(val mode: Int) {
    val octal: String = formatPermissions(mode)
    val label: String = "$octal · ${symbolicPermissions(mode)}"
}

/**
 * The permission sets the picker offers, as bit patterns.
 *
 * Written in binary on purpose. These were decimal integer literals — `644`, `755`, `700`, `600`,
 * `777` — paired with hand-written labels that said `rw-r--r--` and so on, and the number went
 * straight into the SFTP `ATTRS` permissions field, which is a `st_mode` bitfield and therefore
 * octal. Every one of the five was silently wrong: decimal 644 is octal 1204, so choosing
 * "644 · rw-r--r--" set the sticky bit and `-w----r--`, taking away the owner's own read access to
 * their file; decimal 777 is octal 1411, i.e. `r----x--x`, which revokes write from everyone
 * including the owner. Because the listing renders what the server reports, the wrong value came
 * straight back into the file row, and the only way out was another chmod from a real shell.
 *
 * Kotlin has no octal literal, which is what made the original mistake so easy to write and so hard
 * to see. Binary groups of three are unambiguous — each group is one octal digit, in the order
 * `ls -l` prints them — and [PermissionPreset] derives the "644" in the label from the bits, so a
 * typo here shows up in the picker and in [PosixPermissionsTest] rather than on someone's server.
 */
internal val PERMISSION_PRESETS: List<PermissionPreset> = listOf(
    PermissionPreset(0b110_100_100), // 644
    PermissionPreset(0b111_101_101), // 755
    PermissionPreset(0b111_000_000), // 700
    PermissionPreset(0b110_000_000), // 600
    PermissionPreset(0b111_111_111), // 777
)
