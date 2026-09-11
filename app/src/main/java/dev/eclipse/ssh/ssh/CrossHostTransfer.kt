package dev.eclipse.ssh.ssh

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient

/**
 * Copies a file or a whole directory tree from one server to another, through the app, without
 * landing the bytes on the device.
 *
 * This is the engine behind the cross-host send; the UI, the transfer queue and the notifications
 * around it are wired elsewhere. What it owns is the behaviour `SftpDirectoryService.copyAcross`
 * could not offer, because its callers had no way to see into it:
 *
 *  - *Progress.* The tree is pre-walked with [SftpDirectoryService.listTree] so the copy starts
 *    knowing how many bytes it is moving, and every 64 KiB chunk reports a running total against
 *    that figure. A server-to-server copy is the slowest transfer the app offers - both halves of
 *    every byte cross the phone's network - so a transfer with no progress is indistinguishable
 *    from a hang.
 *  - *Collision policy.* [CollisionPolicy] decides what happens when the destination already holds
 *    the name, per entry, instead of the silent truncating overwrite `copyAcross` performed.
 *  - *Per-entry failure.* One unreadable file used to abort the whole copy with everything after
 *    it untried. Here a failing entry is recorded and the walk continues; only a failing *directory*
 *    takes its subtree with it, because nothing below it can land anywhere.
 *  - *Cancellation between chunks.* `ensureActive` runs per chunk, so a cancelled job stops inside
 *    one buffer of where it was asked to stop rather than at the next directory.
 *
 * No Android imports: everything it needs arrives as two MINA [SftpClient]s, a source path, a
 * destination directory and an options object, which is what keeps it testable against two
 * embedded servers in one JVM.
 */
@Singleton
class CrossHostTransfer @Inject constructor(
    private val directories: SftpDirectoryService,
) {
    /**
     * What to do when the destination already holds the name being copied.
     *
     * [RENAME] is the default because it is the only one that cannot lose data the user did not
     * ask to lose: SKIP quietly drops the incoming copy, and OVERWRITE quietly drops what was
     * already there. The copy arriving as "notes (1)" is a surprise the user can undo; an
     * overwritten file on a server they are not looking at is not.
     */
    enum class CollisionPolicy { SKIP, OVERWRITE, RENAME }

    /** One entry that could not be copied, and the server's own sentence about why. */
    data class EntryFailure(val path: String, val reason: String)

    /**
     * How the transfer ended. [entriesCopied] counts directories created (including the merged and
     * renamed ones) as well as files written; [entriesFailed] counts every entry that did not land,
     * including the descendants of a directory that failed; [failures] lists only the entries that
     * failed directly - one line per real problem, not one per file trapped under it.
     */
    data class Result(
        val entriesCopied: Int,
        val entriesSkipped: Int,
        val entriesFailed: Int,
        val bytesTransferred: Long,
        val failures: List<EntryFailure>,
    ) {
        val succeeded: Boolean get() = failures.isEmpty() && entriesSkipped == 0
    }

    /**
     * Copies [sourcePath] (a file or a directory) from [fromSftp] into [destinationDir] on
     * [toSftp], applying [collision] to every entry that already exists there and reporting
     * progress as [onProgress] receives the running byte count against the pre-walked total.
     *
     * A missing source is reported through the result rather than thrown: the caller shows the
     * failure list, and a result object is the same shape that path takes when the source
     * disappears between the user picking it and the copy running - which is the more common way
     * it happens. The walk-level ceilings (depth, entry count) *are* thrown, by [transferTree]'s
     * pre-walk, because they mean the copy as a whole cannot be attempted honestly.
     *
     * Cancellation propagates: a cancelled job throws [CancellationException] and leaves whatever
     * partial entries it had already written on the destination, listable and (under
     * [CollisionPolicy.OVERWRITE]) safe to re-copy over.
     */
    suspend fun transfer(
        fromSftp: SftpClient,
        toSftp: SftpClient,
        sourcePath: String,
        destinationDir: String,
        collision: CollisionPolicy = CollisionPolicy.RENAME,
        onProgress: suspend (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val attributes = try {
            fromSftp.stat(sourcePath)
        } catch (error: Throwable) {
            // The one failure that happens before anything can be walked or copied, so it is
            // reported the same way per-entry failures are: as the transfer's result, with the
            // server's reason attached, rather than as a thrown exception the caller has to treat
            // as a special case beside the result it handles for everything else.
            return@withContext Result(
                entriesCopied = 0,
                entriesSkipped = 0,
                entriesFailed = 1,
                bytesTransferred = 0,
                failures = listOf(EntryFailure(sourcePath, failureReason(error))),
            )
        }
        if (attributes.isDirectory) {
            transferTree(fromSftp, toSftp, sourcePath, destinationDir, collision, onProgress)
        } else {
            transferFile(fromSftp, toSftp, sourcePath, attributes.size, destinationDir, collision, onProgress)
        }
    }

    /**
     * One file, one buffered pump. `toSftp.write` is MINA's default open mode for writing -
     * Write|Create|Truncate - which is the whole of [CollisionPolicy.OVERWRITE]'s file semantics.
     */
    private suspend fun transferFile(
        fromSftp: SftpClient,
        toSftp: SftpClient,
        sourcePath: String,
        size: Long,
        destinationDir: String,
        collision: CollisionPolicy,
        onProgress: suspend (transferredBytes: Long, totalBytes: Long) -> Unit,
    ): Result {
        val totalBytes = size.coerceAtLeast(0)
        val name = sourcePath.trimEnd('/').substringAfterLast('/')
        val target = try {
            fileTarget(toSftp, joinRemote(destinationDir, name), collision)
        } catch (cancelled: CancellationException) {
            // The existence probe is a suspend call, so a cancellation can arrive through it; that
            // belongs to the caller's job, not to this entry's failure list.
            throw cancelled
        } catch (error: Throwable) {
            // Only the rename hunt can throw here (every candidate taken); the entry failed for a
            // reason the caller should see, not one it should crash on.
            return Result(0, 0, 1, 0, listOf(EntryFailure(sourcePath, failureReason(error))))
        }
        if (target == null) return Result(entriesCopied = 0, entriesSkipped = 1, entriesFailed = 0, bytesTransferred = 0, failures = emptyList())
        var transferred = 0L
        try {
            transferred = copyFile(fromSftp, toSftp, sourcePath, target) { fileBytes ->
                onProgress(fileBytes, totalBytes)
            }
            // Reported even when the file is empty and no chunk was ever pumped, so a caller
            // watching progress learns the entry finished rather than never hearing about it.
            onProgress(transferred, totalBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // The partial destination is left in place rather than removed: the copy may be
            // retried (over Truncate) or inspected, and a remove here would race the very
            // cancellation this catch has to stay fast for.
            return Result(0, 0, 1, transferred, listOf(EntryFailure(sourcePath, failureReason(error))))
        }
        return Result(entriesCopied = 1, entriesSkipped = 0, entriesFailed = 0, bytesTransferred = transferred, failures = emptyList())
    }

    /**
     * A directory tree: pre-walk, then copy.
     *
     * The pre-walk is not only for progress. [SftpDirectoryService.listTree] carries the walk's
     * safety ceilings - a depth bound for a server that reports a directory containing itself, and
     * an entry bound so a `/`-pointed copy cannot walk the filesystem into memory - and both throw
     * there, before anything is copied, which is the honest moment: a copy that ran until the
     * ceiling and then reported success would be worse than one that never started.
     */
    private suspend fun transferTree(
        fromSftp: SftpClient,
        toSftp: SftpClient,
        sourcePath: String,
        destinationDir: String,
        collision: CollisionPolicy,
        onProgress: suspend (transferredBytes: Long, totalBytes: Long) -> Unit,
    ): Result {
        val tree = directories.listTree(fromSftp, sourcePath)
        val totalBytes = tree.values.asSequence()
            .filterNot(RemoteFile::isDirectory)
            .sumOf(RemoteFile::size)
            .coerceAtLeast(0)
        val rootName = sourcePath.trimEnd('/').substringAfterLast('/')
        val topTarget = joinRemote(destinationDir, rootName)

        var copied = 0
        var skipped = 0
        var failed = 0
        var transferred = 0L
        val failures = mutableListOf<EntryFailure>()
        /**
         * Relative paths of directories whose subtree is no longer worth visiting, and whether
         * that subtree failed (`true`) or was skipped. A skipped or failed directory takes its
         * descendants with it, because nothing beneath it can land anywhere; they are counted in
         * [Result.entriesSkipped] / [Result.entriesFailed] rather than listed in [failures], which
         * stays one line per real problem instead of one per file trapped under it.
         */
        val deadSubtrees = mutableMapOf<String, Boolean>()

        // The folder itself first, so a rename applied to it moves the whole copy. A skip or a
        // failure here is the whole answer - there is no directory to walk into - so it returns
        // with every descendant counted, rather than letting the loop below rediscover that once
        // per entry. The landing counts: [listTree] starts at the root's own children, so nothing
        // else in the loop below will ever count this directory, and a caller totalling "what
        // arrived" must not come up one short of the folder the user watched appear.
        val baseDir = when (val outcome = createDirectory(toSftp, topTarget, collision)) {
            is DirectoryOutcome.Landed -> {
                copied++
                outcome.path
            }
            DirectoryOutcome.Skipped -> {
                onProgress(0, totalBytes)
                return Result(0, 1 + tree.size, 0, 0, emptyList())
            }
            is DirectoryOutcome.Failed -> {
                onProgress(0, totalBytes)
                return Result(0, 0, 1 + tree.size, 0, listOf(EntryFailure(sourcePath, outcome.reason)))
            }
        }

        // [listTree] walks breadth-first, so a parent is always ahead of its children in the
        // insertion order relied on here: every directory is created before anything is written
        // into it without a second traversal.
        for ((rel, file) in tree) {
            // Checked per entry rather than per chunk only: a tree of empty directories has no
            // chunks to check, and a walk the user cancelled should not keep stat-ing the
            // destination for entries it will never copy.
            coroutineContext.ensureActive()
            val dead = deadSubtrees.entries.firstOrNull { isUnder(rel, it.key) }
            if (dead != null) {
                if (dead.value) failed++ else skipped++
                continue
            }
            val target = joinRemote(baseDir, rel)
            if (file.isDirectory) {
                when (val outcome = createDirectory(toSftp, target, collision)) {
                    is DirectoryOutcome.Landed -> copied++
                    DirectoryOutcome.Skipped -> {
                        skipped++
                        deadSubtrees[rel] = false
                    }
                    is DirectoryOutcome.Failed -> {
                        failed++
                        failures += EntryFailure(file.path, outcome.reason)
                        deadSubtrees[rel] = true
                    }
                }
            } else {
                try {
                    val writeTarget = fileTarget(toSftp, target, collision)
                    if (writeTarget == null) {
                        skipped++
                        continue
                    }
                    val start = transferred
                    val bytes = copyFile(fromSftp, toSftp, file.path, writeTarget) { fileBytes ->
                        onProgress(start + fileBytes, totalBytes)
                    }
                    transferred += bytes
                    copied++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failed++
                    failures += EntryFailure(file.path, failureReason(error))
                }
            }
        }
        // The closing report: a tree of empty directories, or one whose files were all skipped,
        // otherwise never emits anything, and a caller watching progress could not tell a finished
        // zero-byte copy from a dead one.
        onProgress(transferred, totalBytes)
        return Result(copied, skipped, failed, transferred, failures)
    }

    /** Whether [rel] names [prefix] itself or anything beneath it. */
    private fun isUnder(rel: String, prefix: String): Boolean =
        rel == prefix || rel.startsWith("$prefix/")

    /**
     * One buffered pump between the two servers, 64 KiB at a time.
     *
     * The buffer size, the `use` pairing and the per-chunk `ensureActive` all follow the copy loops
     * in [SftpTransferManager] - this is the same shape of work with a second network on the
     * destination side, and the reasons for each are written there.
     *
     * [onChunk] receives this file's running byte count, not the transfer's, so the caller decides
     * how it accumulates across entries.
     */
    private suspend fun copyFile(
        fromSftp: SftpClient,
        toSftp: SftpClient,
        source: String,
        target: String,
        onChunk: suspend (fileBytes: Long) -> Unit,
    ): Long {
        var copied = 0L
        fromSftp.read(source).use { input ->
            toSftp.write(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onChunk(copied)
                }
                output.flush()
            }
        }
        return copied
    }

    /**
     * The path a file should be written to, or `null` when [collision] says leave the destination
     * as it is.
     *
     * OVERWRITE skips the existence check entirely: MINA's `write` opens with Truncate, so asking
     * first would spend a round trip to learn something the write does again anyway.
     */
    private suspend fun fileTarget(toSftp: SftpClient, target: String, collision: CollisionPolicy): String? =
        when (collision) {
            CollisionPolicy.OVERWRITE -> target
            CollisionPolicy.SKIP -> if (directories.exists(toSftp, target)) null else target
            CollisionPolicy.RENAME -> if (directories.exists(toSftp, target)) firstFreeVariant(toSftp, target) else target
        }

    /**
     * Creates the directory [target] under [collision].
     *
     * [DirectoryOutcome.Landed] covers both a directory that was created and one that already
     * existed and was merged into: to a caller walking children, the two are the same thing - a
     * place exists and entries can be written into it. Under OVERWRITE an existing *file* with the
     * directory's name is a failure with a plain sentence rather than the swallowed mkdir error
     * `copyAcross` produced, because the children that follow would each fail with a less
     * understandable one.
     */
    private suspend fun createDirectory(
        toSftp: SftpClient,
        target: String,
        collision: CollisionPolicy,
    ): DirectoryOutcome {
        val existing = runCatching { toSftp.stat(target) }.getOrNull()
        if (existing == null) {
            return try {
                toSftp.mkdir(target)
                DirectoryOutcome.Landed(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                DirectoryOutcome.Failed(failureReason(error))
            }
        }
        return when (collision) {
            CollisionPolicy.SKIP -> DirectoryOutcome.Skipped
            CollisionPolicy.OVERWRITE ->
                if (existing.isDirectory) {
                    DirectoryOutcome.Landed(target)
                } else {
                    DirectoryOutcome.Failed(
                        "a file named \"${target.substringAfterLast('/')}\" is already in the way",
                    )
                }
            CollisionPolicy.RENAME -> {
                val variant = try {
                    firstFreeVariant(toSftp, target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return DirectoryOutcome.Failed(failureReason(error))
                }
                try {
                    toSftp.mkdir(variant)
                    DirectoryOutcome.Landed(variant)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    DirectoryOutcome.Failed(failureReason(error))
                }
            }
        }
    }

    /**
     * The first of "name (1)", "name (2)", ... that the destination does not already hold.
     *
     * Bounded, because each attempt is a stat round trip and a destination whose user (or another
     * copy of this transfer) has taken every variant is one that will not stop producing them. The
     * bound failing is an ordinary entry failure; the caller records it and moves on.
     */
    private suspend fun firstFreeVariant(toSftp: SftpClient, target: String): String {
        val parent = target.substringBeforeLast('/', "")
        val name = target.substringAfterLast('/')
        repeat(MAX_RENAME_ATTEMPTS) { attempt ->
            val candidate = joinRemote(parent.ifEmpty { "/" }, "$name (${attempt + 1})")
            if (!directories.exists(toSftp, candidate)) return candidate
        }
        throw IOException("No free name for \"$name\" after $MAX_RENAME_ATTEMPTS attempts.")
    }

    /**
     * The server's sentence where it has one. Some SFTP failures carry a null or empty message and
     * the class name is still more than nothing - the same fallback the transfer coordinator uses.
     */
    private fun failureReason(error: Throwable): String =
        error.message?.trim()?.takeIf(String::isNotEmpty) ?: error.javaClass.simpleName

    /** What happened to a directory the walk tried to create. */
    private sealed interface DirectoryOutcome {
        /** The directory exists on the destination and children can be written into it. */
        data class Landed(val path: String) : DirectoryOutcome

        /** The collision policy left the destination as it was; the subtree goes uncopied. */
        data object Skipped : DirectoryOutcome

        /** The directory could not be created; the subtree is unwritable, with the server's reason. */
        data class Failed(val reason: String) : DirectoryOutcome
    }

    private companion object {
        /** The same chunk size [SftpDirectoryService] and [SftpTransferManager] copy with. */
        const val COPY_BUFFER_SIZE = 64 * 1024

        const val MAX_RENAME_ATTEMPTS = 100
    }
}
