package dev.eclipse.ssh.terminal

/**
 * Everything the terminal view measured, in the four numbers the rest of the app needs from it.
 *
 * This used to be two numbers, and the reason it is four is the whole of the tall-terminal setting. A
 * viewport is not one size: the *pty* is told a height, the *buffer* is given a height, and the *window*
 * the user reads is a height, and once the app-wide setting became a floor those three stopped being the
 * same number. Collapsing them meant the app could only ever express "the terminal is exactly the size of
 * the screen", which is the one configuration the setting exists to leave.
 *
 * [columns] is still a single number because width never needed the distinction: a column past the right
 * edge is reachable by wrapping or panning at the same grid the pty was told, so there is nothing to say
 * twice.
 *
 * @param columns the width to give both the pty and the buffer.
 * @param ptyRows the height to tell the pty. Normally [bufferRows]; the on-screen rows while a
 *   full-screen program is drawing, because `vim` and `htop` address every cell positionally and would
 *   put their status line a thousand rows below the last pixel.
 * @param bufferRows the height to give the local grid - the app-wide floor, or a host's own shorter
 *   choice. This is the number that makes `ls` print a thousand entries without paging, and it is
 *   deliberately *not* the pty's height on an alternate screen: entering and leaving `vim` must not
 *   reflow the tall screen underneath it.
 * @param visibleRows how many rows fit on screen, and therefore the window [AnsiTerminalBuffer.frame]
 *   should cut - the height the user actually reads, wherever the cursor has scrolled to.
 */
data class TerminalViewport(
    val columns: Int,
    val ptyRows: Int,
    val bufferRows: Int,
    val visibleRows: Int,
)
