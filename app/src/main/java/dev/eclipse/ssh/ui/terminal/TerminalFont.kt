package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.eclipse.ssh.R

/**
 * The terminal's typeface: JetBrains Mono, bundled rather than borrowed from the platform.
 *
 * `FontFamily.Monospace` is whatever monospace the device happens to ship - Droid Sans Mono on one
 * phone, Roboto Mono on another, a vendor's own on a third - so the grid the user reads changed shape
 * from device to device, and none of those faces was designed for the thing a terminal asks of a font:
 * a fixed advance that stays honest at small sizes, glyphs that do not blur into each other at 12sp, and
 * the near-ambiguous pairs a shell is full of - `l1I`, `O0`, `{}` `()` `[]`, `.,;:` - drawn so they
 * cannot be confused. JetBrains Mono is cut for exactly that, which is why it is worth carrying the two
 * files instead of trusting the platform.
 *
 * Both weights are declared, and the bold one matters more than it looks. SGR bold (`ESC[1m`) is how a
 * prompt, a `grep` match and half of `ls --color` announce themselves, and the renderer asks for
 * [FontWeight.Bold] on those cells (see `drawFrame`). With only the regular file registered, Compose
 * would *synthesise* bold by smearing the regular outline, which at terminal sizes thickens the stem
 * unevenly and drifts the advance - the one thing a monospace grid cannot tolerate, because the cell
 * width was measured from the regular cut. Registering the real bold face means a bold cell is the same
 * width as a plain one and the column alignment holds.
 */
internal val TerminalMonoFontFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)
