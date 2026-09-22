package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import kotlin.coroutines.coroutineContext

/**
 * The installed userspace as one tarball, both directions: [export] writes one out of a tree the
 * app owns, [extract] unpacks one into a tree that has replaced nothing yet.
 *
 * It is deliberately a file-level class with no Android type anywhere in it — a [File] in, an
 * [OutputStream] out — because everything that makes an import safe (the traversal guard, the link
 * guard, the expansion budget, the "is this actually a userspace" check) is a property of the bytes
 * rather than of the screen that chose them, and this is the shape those properties can be tested
 * in. [RootfsTransfer] is the half that knows about locks and the userspace state machine; the
 * caller's stream is the boundary between them.
 *
 * ## What an export carries
 * The guest filesystem as the guest sees it. That is every entry under the rootfs *except* the
 * three names proot binds the device's own over — [RootfsPaths.HIDDEN], which is the same set the
 * Files tab refuses to browse and for the same reason: what a rootfs holds under dev, proc and sys
 * is a handful of mount-point stubs that a running session never shows anyone, so archiving them
 * would put one device's stubs inside a file that claims to be somebody's userspace. They are
 * written as the empty directories they are, so a restored tree still has the three names proot's
 * binds land on. proot's scratch directory needs no rule here: `PROOT_TMP_DIR` is outside the
 * rootfs by construction, so it cannot be inside an archive of it.
 *
 * The user's workspace *is* in the archive, because it is inside the rootfs:
 * `/home/ubuntu/workspace` is the user's own data — the most valuable thing in the tree by a
 * distance — and an export that left it out would be a backup of the operating system rather than
 * of the userspace they built. The screen that offers the export says so in as many words.
 *
 * ## What a round trip is worth
 * Modes and symlinks survive. A hard link does not: it is written as a full copy of its content,
 * because a copy is what this app's own archives mean by a file ([LinuxWorkspaceManager]'s
 * snapshot makes the same choice for the same reason — the reader gets a file either way, and the
 * writer needs no second pass to resolve a link whose target has not been written yet). The cost is
 * honest and bounded: a tree that used hard links to avoid duplicating a large file — a
 * `node_modules`-shaped tree is the usual one — exports larger than it measures on disk.
 *
 * A round trip is not a *device* round trip, and the difference is the useful part: an export
 * carries the exporting device's app uid in `/etc/passwd`, its resolvers and its apt mirror. All
 * three are rewritten by the setup pipeline the import hands off to, which is why importing an
 * archive from another phone produces a userspace that works on this one rather than one that
 * merely unpacks.
 *
 * @param root the tree the archive is rooted at: the installed rootfs on the way out, the staging
 *   directory on the way in
 */
internal class RootfsArchive(private val root: File) {

    /**
     * One progress reading: [bytes] of [total] moved, after [entries] archive members.
     *
     * [total] is 0 when the total is unknown — a document provider that will not report the
     * archive's length — and the reader is then expected to draw an indeterminate bar rather than
     * one at zero, the same rule the transfer rows follow everywhere else.
     */
    data class Progress(val entries: Int, val bytes: Long, val total: Long)

    /**
     * What one pass did: how many members it wrote or read, how many bytes of real file content
     * they were, and the entries it could not carry over.
     */
    data class Report(val entries: Int, val bytes: Long, val warnings: List<String>)

    /**
     * Writes the tree out as a gzipped tar. Streaming throughout: a userspace is hundreds of
     * megabytes, and the whole point of exporting one is that the device can hold it.
     *
     * Cancellable at every buffer and every entry, which is what makes a cancelled export leave a
     * truncated document rather than a process pinned in a read for however long 250 MB takes.
     */
    suspend fun export(
        output: OutputStream,
        onProgress: (Progress) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        if (!root.isDirectory) {
            throw IOException("there is no root filesystem at $root to export")
        }
        val plan = planExport()
        val total = plan.items.sumOf { it.bytes }
        var entries = 0
        var written = 0L
        var reported = 0L
        GZIPOutputStream(output, COPY_BUFFER).use { gzip ->
            TarArchiveOutputStream(gzip, COPY_BUFFER).use { tar ->
                // POSIX long names, like the workspace snapshot: a rootfs is deeper than 100
                // characters in places, and GNU's `./PaxHeaders` noise is what the alternative
                // writes into an archive a user may open on a desktop.
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                for (item in plan.items) {
                    coroutineContext.ensureActive()
                    // The one thing an archive cannot represent here. A fifo read would block until
                    // a writer appeared — inside an export, on a device where no writer ever will —
                    // and a device node needs privileges Android does not grant. Skipped and named:
                    // a gap nobody hears about is a userspace that is subtly not the one that was
                    // backed up.
                    if (item.kind == Kind.SKIPPED) continue
                    tar.putArchiveEntry(item.entry())
                    if (item.kind == Kind.FILE) {
                        item.file.inputStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                tar.write(buffer, 0, read)
                                written += read
                                if (written - reported >= REPORT_EVERY_BYTES) {
                                    reported = written
                                    onProgress(Progress(entries, written, total))
                                }
                            }
                        }
                    } else {
                        written += item.bytes
                    }
                    tar.closeArchiveEntry()
                    entries++
                }
            }
        }
        // The last reading is the end by definition rather than by the counter, which no longer
        // moves once the last entry is written.
        onProgress(Progress(entries, total, total))
        Report(entries, total, plan.skipped.map { "$it cannot be stored in an archive" })
    }

    /**
     * Unpacks [source] into [root], which must be a directory the caller has already emptied and
     * which nothing else has touched: this writes the tree, it does not install it. Replacing what
     * is installed is [RootfsTransfer]'s swap, and keeping the two apart is what makes a failed
     * import a staging directory to delete rather than a userspace to repair.
     *
     * [source] is untrusted input by definition — a file the user picked, from wherever — so every
     * entry goes through the same two guards the pinned installer uses ([resolveInsideRoot] for the
     * name, [resolveLinkInsideRoot] for a link's target), the same expansion budget is enforced
     * against [budgetBytes], and the tree must still turn out to carry a userspace: a tarball of
     * somebody's photographs is refused *here*, before anything is swapped, rather than surfacing
     * later as apt failing in a rootfs with no shell.
     *
     * @param budgetBytes how much unpacked content is allowed, from
     *   [RootfsTransfer.importBudgetBytes] — the archive's own size times the installer's multiple
     * @param sourceBytes the archive's size as the picker reported it, the denominator of the
     *   progress readings; 0 when the provider would not say
     */
    suspend fun extract(
        source: InputStream,
        budgetBytes: Long,
        sourceBytes: Long = 0L,
        onProgress: (Progress) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        deleteTreeNoFollow(root)
        root.mkdirs()
        val warnings = mutableListOf<String>()
        var entries = 0
        var written = 0L
        var consumed = 0L
        var sawUserspace = false
        // Through the shared gzip-aware opener: an export is a `.tar.gz`, and a raw
        // TarArchiveInputStream would read one as an empty archive — after which every check below
        // would pass on a tree of nothing.
        openTarStream(counting(source) { consumed = it }, COPY_BUFFER).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./")
                val target = resolveInsideRoot(root, entry.name)
                // 0 is LF_OLDNORM - NUL is the pre-POSIX encoding of "regular file" and real
                // tarballs (Ubuntu Base included) still emit it for early entries.
                when (entry.linkFlag) {
                    TarArchiveEntry.LF_DIR -> target.mkdirs()
                    TarArchiveEntry.LF_SYMLINK -> {
                        target.parentFile?.mkdirs()
                        // Where a link points must stay inside the staging root too: a symlink
                        // pointing out is an escape hatch no entry-name check would catch, because
                        // nothing here writes through it.
                        resolveLinkInsideRoot(root, entry.name, entry.linkName)
                        target.delete()
                        deleteTreeNoFollow(target)
                        Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                    }
                    TarArchiveEntry.LF_LINK -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = resolveInsideRoot(root, entry.linkName)
                        // The portable equivalent of a hard link, and the same one the installer
                        // makes. A forward link — one whose target arrives later in the archive —
                        // is skipped and named rather than silently dropped: it is a file that is
                        // absent from the imported userspace, and the warning is what turns a later
                        // "command not found" into a sentence about the archive.
                        if (linkTarget.isFile) {
                            linkTarget.copyTo(target, overwrite = true)
                            written += target.length()
                        } else {
                            warnings += "hardlink $name skipped: its target ${entry.linkName} was not in the archive yet"
                        }
                    }
                    TarArchiveEntry.LF_NORMAL, 0.toByte() -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> tar.copyTo(out, COPY_BUFFER) }
                        applyMode(target, entry.mode)
                        written += target.length()
                    }
                    else -> {
                        // Device nodes, fifos and sockets: proot binds the device's own /dev, so a
                        // userspace does not need them, and creating one would need privileges the
                        // app does not have anyway.
                    }
                }
                if (name.removeSuffix("/") in ROOTFS_MARKERS) sawUserspace = true
                // The expansion budget, the same shape the pinned install enforces: the archive's
                // own size times a multiple is what a real userspace unpacks to, and a gzip bomb
                // that would fill the device dies here with a staging tree the caller can reclaim.
                if (written > budgetBytes) {
                    throw IOException(
                        "the archive expands beyond the expected size (${written / MIB} MB unpacked " +
                            "from a ${sourceBytes / MIB} MB file) - refusing to continue",
                    )
                }
                entries++
                if (entries % PROGRESS_EVERY_ENTRIES == 0) {
                    coroutineContext.ensureActive()
                    onProgress(Progress(entries, consumed, sourceBytes))
                }
            }
        }
        if (!sawUserspace) {
            throw IOException(
                "this archive is not a Linux userspace: it carries none of " +
                    ROOTFS_MARKERS.joinToString(", "),
            )
        }
        // 1 by definition, not by the counter: the byte counter reads through a gzip stream that
        // stops at the end-of-archive padding, so the last honest reading sits a little short of the
        // total it has, in fact, reached.
        onProgress(Progress(entries, sourceBytes, sourceBytes))
        Report(entries, written, warnings)
    }

    /**
     * Why the extracted tree is not a userspace this app may adopt, as sentences naming the path —
     * an empty list when it is one.
     *
     * Two questions, and the difference between them is why this is not folded into [extract]: the
     * manifest check ([RootfsValidator]) is about the tree being *usable* — a shell, the dynamic
     * linker, the files setup writes into — and the version check is about it being the *same*
     * userspace this device is set up for. A mixture is the failure both exist to prevent: the
     * setup pipeline would rewrite apt sources for one Ubuntu release into a tree of another, and
     * the result is a rootfs that installs nothing and cannot say why.
     *
     * Refused only on a positive mismatch. A tree with no `/etc/os-release`, or one whose fields
     * are missing, is "cannot tell" rather than "different", and a hand-trimmed or hand-built
     * userspace is not a mistake this check is entitled to refuse.
     */
    fun verify(distro: LinuxDistro): List<String> {
        val findings = RootfsValidator(expectedArch = distro.ubuntuArch)
            .validate(root)
            .mapTo(mutableListOf()) { finding -> "${finding.path} ${finding.problem}" }
        val release = readOsRelease(root)
        val id = release["ID"]
        if (id != null && id.isNotBlank() && !id.equals(UBUNTU_ID, ignoreCase = true)) {
            findings += "the archive holds a \"$id\" system, and this app installs Ubuntu"
        }
        val codename = release["UBUNTU_CODENAME"] ?: release["VERSION_CODENAME"]
        if (codename != null && codename.isNotBlank() && distro.release.isNotBlank() &&
            !codename.equals(distro.release, ignoreCase = true)
        ) {
            findings +=
                "the archive holds Ubuntu $codename and this device's Ubuntu is ${distro.release}: " +
                "uninstall first, choose that version on the install screen, then import"
        }
        return findings
    }

    /** One archive member: where it is, what it is, and what it weighs. */
    private class Item(
        val file: File,
        val name: String,
        val kind: Kind,
        val bytes: Long,
        val linkTarget: String?,
    ) {
        /**
         * The tar entry for this member. Symlinks are built through the link-type constructor
         * rather than by setting linkName on a regular entry: the type is what makes an extractor
         * write a link, and setting only the name leaves a regular file with a target nothing reads.
         */
        fun entry(): TarArchiveEntry =
            if (kind == Kind.SYMLINK) {
                TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { linkName = linkTarget }
            } else {
                // The exec bit is re-applied by hand on top of what the constructor reads, the same
                // way the workspace snapshot does: the mode that matters inside a userspace is
                // "is this runnable", and nothing else about the host's mode survives a copy that
                // is owned by one uid.
                TarArchiveEntry(file, name).apply { if (file.canExecute()) mode = mode or EXEC_BITS }
            }
    }

    /** What a member is, as far as an archive is concerned. */
    private enum class Kind { DIRECTORY, SYMLINK, FILE, SKIPPED }

    /** The members to write, in the order they go out, plus the ones that cannot be written. */
    private class Plan(val items: List<Item>, val skipped: List<String>)

    /**
     * The export plan: one entry per member, the three borrow directories as empty mount points,
     * and nothing underneath them.
     *
     * The walk is [walkTreeNoFollow], which is what makes a link an entry rather than a detour —
     * a `lib -> usr/lib` is written as a link and its target's files are reached through the real
     * `usr/lib`, counted once, in both the archive and the total.
     */
    private fun planExport(): Plan {
        val items = mutableListOf<Item>()
        val skipped = mutableListOf<String>()
        for (name in RootfsPaths.HIDDEN) {
            val dir = File(root, name)
            if (dir.isDirectoryNoFollow()) items += Item(dir, name, Kind.DIRECTORY, 0L, null)
        }
        walkTreeNoFollow(root).forEach { file ->
            val name = file.relativeTo(root).path
            if (name.split('/').first() in RootfsPaths.HIDDEN) return@forEach
            val linkTarget = runCatching {
                if (Files.isSymbolicLink(file.toPath())) {
                    // The target as the link stores it, never a resolved path: a relative link must
                    // stay relative, so a restore lands in the same shape it was archived in.
                    Files.readSymbolicLink(file.toPath()).toString()
                } else {
                    null
                }
            }.getOrNull()
            val kind = when {
                linkTarget != null -> Kind.SYMLINK
                file.isDirectoryNoFollow() -> Kind.DIRECTORY
                file.isRegularFileNoFollow() -> Kind.FILE
                else -> Kind.SKIPPED
            }
            if (kind == Kind.SKIPPED) skipped += name
            items += Item(file, name, kind, if (kind == Kind.FILE) file.length() else 0L, linkTarget)
        }
        return Plan(items, skipped)
    }

    /**
     * The mode bits that matter under Android, exactly as the installer applies them: the executable
     * bit from the entry, read and write for the owner. Group and other bits are dropped — every
     * file in the tree has the same owner, and `setExecutable(false, false)` on a directory would
     * break traversal into it.
     */
    private fun applyMode(target: File, mode: Int) {
        if (target.isDirectory) return
        target.setExecutable((mode and EXEC_BITS) != 0, true)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val PROGRESS_EVERY_ENTRIES = 200
        const val REPORT_EVERY_BYTES = 4L * 1024 * 1024
        const val MIB = 1024L * 1024

        /** Owner, group and other execute, which is how "runnable" is spelled in a tar mode. */
        const val EXEC_BITS = 0b001_001_001

        /** What the os-release of the system this app installs says its own ID is. */
        const val UBUNTU_ID = "ubuntu"

        /**
         * The members that make a tarball a userspace rather than a directory of files: the shell
         * every maintainer script runs under, and the two programs that make a Debian-shaped system
         * installable at all. Any one of them is enough — a trimmed image may legitimately drop the
         * other two — and the manifest check in [verify] is what has the final word on the rest.
         */
        val ROOTFS_MARKERS = setOf("bin/sh", "usr/bin/dpkg", "usr/bin/apt-get")
    }
}

/**
 * The `KEY=value` pairs of the tree's `/etc/os-release`, or an empty map when it carries none.
 *
 * Empty rather than a failure, deliberately: a hand-built or trimmed userspace may have no
 * os-release at all, and [RootfsArchive.verify] refuses only on a positive mismatch — "cannot tell"
 * must not read as "wrong system".
 */
private fun readOsRelease(root: File): Map<String, String> {
    val file = File(root, "etc/os-release")
    if (!file.isFile) return emptyMap()
    val values = mutableMapOf<String, String>()
    for (line in runCatching { file.readLines() }.getOrDefault(emptyList())) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val split = trimmed.indexOf('=')
        if (split <= 0) continue
        values[trimmed.substring(0, split)] = trimmed.substring(split + 1).trim().trim('"', '\'')
    }
    return values
}

/**
 * The stream the import's progress reads: the bytes handed over by the document's own stream, which
 * is the only quantity a fraction can honestly be shown of — the tar layer above counts entries,
 * and nothing here knows in advance what they unpack to.
 *
 * Only the ranged form needs overriding for an arbitrary [InputStream] (its single-byte and
 * array-wide reads both delegate to it), but the byte-at-a-time form is overridden too: a caller
 * probing one byte at a time would otherwise be invisible to the counter, and the reading would sit
 * at zero while the archive drained.
 */
private class CountingStream(
    private val delegate: InputStream,
    private val onBytesRead: (Long) -> Unit,
) : InputStream() {
    private var total = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) report(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = delegate.read(buffer, offset, length)
        if (count > 0) report(count.toLong())
        return count
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    private fun report(count: Long) {
        total += count
        onBytesRead(total)
    }
}

/** [source] with its reads counted; see [CountingStream]. */
private fun counting(source: InputStream, onBytesRead: (Long) -> Unit): InputStream = CountingStream(source, onBytesRead)
