package dev.eclipse.ssh

import dev.eclipse.ssh.terminal.TerminalViewport

/**
 * A viewport reported from a screen of exactly [columns] x [rows].
 *
 * All three heights are the same number, which is what "fit screen" means and what every test that
 * drives the terminal directly is standing in for: the pty is told the height of the screen, the local
 * grid is given that height, and the window cut out of it is that height. The three differ only when the
 * app-wide height setting is a floor above the screen, which is what [TerminalViewport] documents and
 * what the geometry tests cover.
 */
internal fun screenViewport(columns: Int, rows: Int): TerminalViewport =
    TerminalViewport(columns = columns, ptyRows = rows, bufferRows = rows, visibleRows = rows)
