package dev.eclipse.ssh.ssh

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient

@Singleton
class SftpDirectoryService @Inject constructor() {
    /**
     * Resolves the account's real starting directory by canonicalising ".".
     *
     * Guessing "/home/$username" is wrong for root (whose home is /root), for BSD/macOS
     * (/Users/...), for chrooted SFTP accounts, and for any distribution that does not use
     * /home. Falling back to a guess only happens when the server refuses to canonicalise.
     */
    suspend fun homeDirectory(sftp: SftpClient, username: String): String = withContext(Dispatchers.IO) {
        val canonical = runCatching { sftp.canonicalPath(".") }.getOrNull()?.trim()
        if (!canonical.isNullOrEmpty() && canonical != ".") normalize(canonical) else fallbackHome(username)
    }

    suspend fun list(sftp: SftpClient, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        sftp.readDir(path).filter { isPlainEntryName(it.filename) }.map { entry ->
            val attributes = entry.attributes
            RemoteFile(
                name = entry.filename,
                path = if (path.endsWith('/')) path + entry.filename else "$path/${entry.filename}",
                isDirectory = attributes.isDirectory,
                size = attributes.size,
                modifiedEpochSeconds = attributes.modifyTime?.toMillis()?.div(1_000L) ?: 0L,
                permissions = formatPermissions(attributes.permissions),
            )
        }.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun createDirectory(sftp: SftpClient, path: String) = withContext(Dispatchers.IO) { sftp.mkdir(path) }
    suspend fun delete(sftp: SftpClient, path: String) = withContext(Dispatchers.IO) {
        if (sftp.stat(path).isDirectory) sftp.rmdir(path) else sftp.remove(path)
    }
    suspend fun rename(sftp: SftpClient, from: String, to: String) = withContext(Dispatchers.IO) { sftp.rename(from, to) }
    suspend fun copy(sftp: SftpClient, from: String, to: String) = withContext(Dispatchers.IO) {
        copyEntry(sftp, from, to)
    }

    /**
     * Recursively lists [rootPath] and returns relative path → [RemoteFile].
     *
     * Bounded, because the whole listing is held in memory at once and every value in it comes from
     * the server. Two ways that ended badly without a limit. The ordinary one: pointing Sync at `/`
     * (or at any tree with a `node_modules` in it) walks the entire filesystem into a map of
     * [RemoteFile] objects and the app is killed for running out of memory, having reported nothing.
     * The hostile one: a server is free to answer `readdir` with a directory that contains itself,
     * and the walk below then never terminates — [attributes] are the server's word, not something
     * the client can check.
     */
    suspend fun listTree(sftp: SftpClient, rootPath: String): Map<String, RemoteFile> = withContext(Dispatchers.IO) {
        val result = linkedMapOf<String, RemoteFile>()
        val root = if (rootPath == "/") "/" else rootPath.trimEnd('/')
        walkTree(sftp, root, "", result)
        result
    }

    private fun walkTree(sftp: SftpClient, path: String, rel: String, result: MutableMap<String, RemoteFile>) {
        val pending = ArrayDeque<Descent>()
        pending.add(Descent(path, rel, depth = 0))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            current.checkDepth()
            sftp.readDir(current.source).forEach { entry ->
                if (!isPlainEntryName(entry.filename)) return@forEach
                val childPath = joinRemote(current.source, entry.filename)
                val childRel = if (current.target.isEmpty()) entry.filename else "${current.target}/${entry.filename}"
                val attributes = entry.attributes
                result[childRel] = RemoteFile(
                    name = entry.filename,
                    path = childPath,
                    isDirectory = attributes.isDirectory,
                    size = attributes.size,
                    modifiedEpochSeconds = attributes.modifyTime?.toMillis()?.div(1_000L) ?: 0L,
                    permissions = formatPermissions(attributes.permissions),
                )
                if (result.size > MAX_TREE_ENTRIES) {
                    throw IOException(
                        "This folder holds more than $MAX_TREE_ENTRIES files. Pick a narrower folder " +
                            "to sync.",
                    )
                }
                if (attributes.isDirectory) pending.add(current.child(childPath, childRel))
            }
        }
    }

    suspend fun exists(sftp: SftpClient, path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { sftp.stat(path); true }.getOrDefault(false)
    }

    /**
     * Streams a file or directory tree from one server to another.
     *
     * Iterative, like [copyEntry] and for the reason [copyEntry] already gave: this one was written
     * recursively, so a deep tree overflowed the stack — and it recursed *through*
     * `withContext(Dispatchers.IO)`, which puts a dispatch and a fresh continuation on the stack at
     * every level, so it gave out well before a plain recursion would.
     */
    suspend fun copyAcross(fromSftp: SftpClient, toSftp: SftpClient, from: String, to: String) {
        withContext(Dispatchers.IO) {
            val pending = ArrayDeque<Descent>()
            pending.add(Descent(from, to, depth = 0))
            var copied = 0
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val current = pending.removeFirst()
                current.checkDepth()
                if (fromSftp.stat(current.source).isDirectory) {
                    runCatching { toSftp.mkdir(current.target) }
                    fromSftp.readDir(current.source).forEach { entry ->
                        if (!isPlainEntryName(entry.filename)) return@forEach
                        if (++copied > MAX_TREE_ENTRIES) throw tooManyEntries()
                        pending.add(
                            current.child(
                                joinRemote(current.source, entry.filename),
                                joinRemote(current.target, entry.filename),
                            ),
                        )
                    }
                } else {
                    fromSftp.read(current.source).use { input ->
                        toSftp.write(current.target).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                    }
                }
            }
        }
    }

    private fun copyEntry(sftp: SftpClient, from: String, to: String) {
        // Iterative so a deeply nested tree cannot overflow the stack.
        val pending = ArrayDeque<Descent>()
        pending.add(Descent(from, to, depth = 0))
        var copied = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            current.checkDepth()
            if (sftp.stat(current.source).isDirectory) {
                runCatching { sftp.mkdir(current.target) }
                sftp.readDir(current.source).forEach { entry ->
                    if (!isPlainEntryName(entry.filename)) return@forEach
                    if (++copied > MAX_TREE_ENTRIES) throw tooManyEntries()
                    pending.add(
                        current.child(
                            joinRemote(current.source, entry.filename),
                            joinRemote(current.target, entry.filename),
                        ),
                    )
                }
            } else {
                sftp.read(current.source).use { input ->
                    sftp.write(current.target).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
            }
        }
    }

    /**
     * Sets [path]'s permission bits.
     *
     * [mode] is a `st_mode` bitfield, not the three digits a person says out loud — build it with
     * [PERMISSION_PRESETS] or a binary literal, never `644`. [requirePermissionBits] is what makes
     * that difference loud, and why it can be caught at all is written there.
     *
     * Consequently the app cannot set setuid, setgid or sticky. It has no UI for them, and an SFTP
     * `chmod` that silently added one would be worse than one that refuses. Widening this needs a
     * deliberate change here and a way to show the bits in the picker.
     */
    suspend fun chmod(sftp: SftpClient, path: String, mode: Int) = withContext(Dispatchers.IO) {
        sftp.setStat(path, SftpClient.Attributes().perms(requirePermissionBits(mode)))
    }

    /**
     * One directory still to be walked, and how deep it already is.
     *
     * The depth is carried rather than inferred because a server's `readdir` can name a directory
     * that is its own ancestor — a symlink loop is invisible to the client, since `isDirectory` is
     * whatever the server says it is — and a queue-based walk has no call stack to run out of. Left
     * unbounded, that is a loop the app cannot be interrupted out of except by being killed.
     */
    private data class Descent(val source: String, val target: String, val depth: Int) {
        fun child(source: String, target: String) = Descent(source, target, depth + 1)

        fun checkDepth() {
            if (depth > MAX_TREE_DEPTH) {
                throw IOException(
                    "Stopped after $MAX_TREE_DEPTH levels under \"$source\". The server may be " +
                        "reporting a directory that contains itself.",
                )
            }
        }
    }

    private companion object {
        /**
         * Ceilings for a single tree walk. Generous next to any real directory a person would sync —
         * and small enough that a runaway one fails with a message instead of an OOM kill.
         */
        const val MAX_TREE_ENTRIES = 50_000
        const val MAX_TREE_DEPTH = 64

        fun tooManyEntries() = IOException(
            "This tree holds more than $MAX_TREE_ENTRIES entries. Copy a narrower folder.",
        )

        // formatPermissions moved to PosixPermissions.kt, beside the inverse direction it has to
        // agree with. The two were written years apart in effect: this one rendered the mode as
        // octal and the picker sent it back as decimal.

        fun normalize(path: String): String =
            if (path.length > 1) path.trimEnd('/').ifEmpty { "/" } else path
    }
}

/**
 * Best-effort home directory for [username] when the server cannot canonicalise ".".
 * Notably `/home/root` does not exist on any conventional Unix system.
 */
fun fallbackHome(username: String): String = when {
    username.isBlank() -> "/"
    username == "root" -> "/root"
    else -> "/home/$username"
}

        /**
 * Whether [name] is a directory entry this app will act on.
 *
 * Every name here is the server's word, and each one is pasted into a path — a remote path
 * for the copy and sync paths, and a *local* one on the way back: `syncFromRemote` takes the
 * relative path this produces and walks it segment by segment into the SAF tree the user
 * picked. A name of ".." or one carrying its own "/" is therefore a request to step outside
 * the folder that was chosen, and a name carrying a NUL is a request that means one thing to
 * Kotlin and another to any native layer that reads it as a C string.
 *
 * A real POSIX filename cannot contain "/" or NUL — the kernel forbids both — so nothing
 * legitimate is skipped by refusing them; only a server that is broken or hostile produces
 * one. "." and ".." are skipped for the ordinary reason as well: they are every directory's
 * own entries and walking them is what turns a tree into a loop.
 *
 * Skipped rather than thrown on, deliberately. One malformed entry must not make a directory
 * unlistable or abandon a sync halfway through the files that were fine.
 */
fun isPlainEntryName(name: String): Boolean = name.isNotEmpty() &&
    name != "." &&
    name != ".." &&
    !name.contains('/') &&
    !name.contains('\u0000')

/**
 * Appends [child] to remote directory [base] with exactly one separator, so a base that
 * already ends in "/" (notably the root "/") does not produce a doubled "//" path that
 * some SFTP servers reject.
 */
fun joinRemote(base: String, child: String): String =
    if (base.endsWith('/')) base + child else "$base/$child"

private const val COPY_BUFFER_SIZE = 64 * 1024

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedEpochSeconds: Long,
    val permissions: String,
)
