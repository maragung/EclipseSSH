package dev.eclipse.ssh.data.fs

import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.ssh.SftpDirectoryService
import dev.eclipse.ssh.ssh.SshConnectionManager
import dev.eclipse.ssh.ssh.SshSessionStore
import dev.eclipse.ssh.ssh.formatPermissions
import dev.eclipse.ssh.ssh.isPlainEntryName
import dev.eclipse.ssh.ssh.joinRemote
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient

/**
 * One host's SFTP tree, reached through the SSH session the app already holds.
 *
 * Every operation opens its own SFTP channel via [SshConnectionManager.withSftp] and closes it on
 * the same IO dispatcher — the bounded, thread-safe shape the app standardized on after a
 * channel-close on the main thread was seen as a transport failure. Nothing here caches a channel,
 * because a [path] handed out by one channel is faithfully honoured by the next one: an SFTP path is
 * a real POSIX string, round-tripped unchanged.
 *
 * That POSIX round-trip is also why this is the one backend allowed to do path arithmetic: splitting
 * and joining a slash-separated absolute path never invents a directory, the way splitting a
 * `content://` URI would. The arithmetic lives in [parentPath] and the `joinRemote` calls, and
 * nowhere else.
 *
 * POSIX is also what this provider can report that the local one cannot: permission bits in
 * [FsEntry.permissions] and real modification times — including for the conflict guard on [write],
 * where a file that changed after the editor opened it is a refused write rather than a lost one.
 */
class SftpFileSystemProvider(
    private val hostId: String,
    private val hostName: String,
    private val username: String,
    private val sessionStore: SshSessionStore,
    private val connectionManager: SshConnectionManager,
    private val directoryService: SftpDirectoryService,
) : FileSystemProvider {

    override val providerId: String = "sftp:$hostId"

    /** SFTP carries the full POSIX mode; the UI offers the permission editor for this provider. */
    override val supportsPermissions: Boolean = true

    /**
     * The live session for this host, or a failure a person can read.
     *
     * Every operation starts here rather than at connect time, because a provider instance outlives
     * the session it was made for: it sits in the explorer's state while the user reconnects, and
     * the next operation should find the new session through the store instead of holding a stale
     * channel to a dead transport. Asked host-wide rather than by key: SFTP is one channel on the
     * host's primary session, not one per terminal, and the provider cannot know which of the host's
     * session keys is holding it.
     */
    private fun session() = sessionStore.primarySession(hostId)
        ?: throw IllegalStateException("$hostName is not connected")

    private suspend fun <T> channel(block: suspend (SftpClient) -> T): T =
        connectionManager.withSftp(session(), block)

    /** The account's real home directory, canonicalised by the server rather than guessed. */
    override suspend fun homePath(): String? = channel { sftp ->
        directoryService.homeDirectory(sftp, username)
    }

    /**
     * The directory above [path]. Pure arithmetic on a POSIX absolute path; relative paths and the
     * root itself have no parent to offer.
     */
    override suspend fun parentPath(path: String): String? {
        if (!path.startsWith('/')) return null
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        return if (cut == 0) "/" else trimmed.substring(0, cut)
    }

    override suspend fun list(path: String): List<FsEntry> = channel { sftp ->
        sftp.readDir(path).filter { isPlainEntryName(it.filename) }.map { entry ->
            val attributes = entry.attributes
            FsEntry(
                name = entry.filename,
                path = joinRemote(path, entry.filename),
                isDirectory = attributes.isDirectory,
                size = attributes.size.takeIf { !attributes.isDirectory },
                modifiedEpochMillis = attributes.modifyTime?.toMillis(),
                permissions = formatPermissions(attributes.permissions),
                mimeType = null,
            )
        }.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): FsEntry? = channel { sftp ->
        runCatching { sftp.stat(path) }.getOrNull()?.let { attributes ->
            val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
            FsEntry(
                name = name,
                path = path,
                isDirectory = attributes.isDirectory,
                size = attributes.size.takeIf { !attributes.isDirectory },
                modifiedEpochMillis = attributes.modifyTime?.toMillis(),
                permissions = formatPermissions(attributes.permissions),
                mimeType = null,
            )
        }
    }

    override suspend fun read(path: String): ByteArray = channel { sftp ->
        sftp.read(path).use { it.readBytes() }
    }

    override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) =
        channel { sftp ->
            if (onlyIfUnmodifiedSince != null) {
                val current = runCatching { sftp.stat(path).modifyTime?.toMillis() }.getOrNull()
                if (current != null && current > onlyIfUnmodifiedSince) {
                    throw FsModificationConflictException(path)
                }
            }
            sftp.write(path).use { output -> output.write(data) }
        }

    override suspend fun createFile(parentPath: String, name: String): FsEntry = channel { sftp ->
        val path = joinRemote(parentPath, name)
        // Opening for write with the default modes is create-or-truncate, which is the semantics the
        // contract names: an existing file of this name becomes empty rather than erroring, matching
        // what a `> file` in the shell would have done.
        sftp.write(path).use { output -> output.write(ByteArray(0)) }
        val attributes = sftp.stat(path)
        FsEntry(
            name = name,
            path = path,
            isDirectory = attributes.isDirectory,
            size = 0L,
            modifiedEpochMillis = attributes.modifyTime?.toMillis(),
            permissions = formatPermissions(attributes.permissions),
            mimeType = null,
        )
    }

    override suspend fun createDirectory(parentPath: String, name: String) = channel { sftp ->
        sftp.mkdir(joinRemote(parentPath, name))
    }

    override suspend fun rename(path: String, newName: String) = channel { sftp ->
        val parent = parentPath(path)
            ?: throw IllegalArgumentException("The root directory cannot be renamed")
        sftp.rename(path, joinRemote(parent, newName))
    }

    override suspend fun copy(sourcePath: String, targetDirectoryPath: String) = channel { sftp ->
        // copyEntry walks iteratively with a depth cap, so a deep tree costs a queue entry per
        // folder instead of a stack frame per level.
        directoryService.copy(sftp, sourcePath, joinRemote(targetDirectoryPath, sourcePath.entryName()))
    }

    override suspend fun move(sourcePath: String, targetDirectoryPath: String) = channel { sftp ->
        val currentParent = parentPath(sourcePath)
        if (currentParent == targetDirectoryPath) return@channel
        val target = joinRemote(targetDirectoryPath, sourcePath.entryName())
        try {
            // rename is atomic and free when both ends are on one filesystem
            sftp.rename(sourcePath, target)
        } catch (error: IOException) {
            // ...and impossible across filesystems, where copy-then-delete is the honest fallback.
            directoryService.copy(sftp, sourcePath, target)
            deleteTree(sftp, sourcePath)
        }
    }

    override suspend fun delete(path: String) = channel { sftp ->
        deleteTree(sftp, path)
    }

    override suspend fun setPermissions(path: String, mode: Int) = channel { sftp ->
        directoryService.chmod(sftp, path, mode)
    }

    /**
     * Names under [root] containing [query], bounded by [maxEntries] and by depth.
     *
     * A folder that cannot be read is skipped, not fatal — the readable rest of the tree is still
     * what the user asked for. The server's word about which entries are directories is taken as-is
     * (it is all the client has), which is exactly what the depth cap is for: a server that reports
     * a directory inside itself cannot turn this walk into a loop.
     */
    override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> =
        channel { sftp ->
            val results = ArrayList<FsEntry>()
            val pending = ArrayDeque<Pair<String, Int>>()
            pending.add((if (root == "/") "/" else root.trimEnd('/')) to 0)
            while (pending.isNotEmpty() && results.size < maxEntries) {
                val (directory, depth) = pending.removeFirst()
                if (depth > MAX_SEARCH_DEPTH) continue
                val entries = runCatching { sftp.readDir(directory) }.getOrNull() ?: continue
                for (entry in entries) {
                    if (results.size >= maxEntries) break
                    if (!isPlainEntryName(entry.filename)) continue
                    if (entry.filename.contains(query, ignoreCase = true)) {
                        val attributes = entry.attributes
                        results.add(
                            FsEntry(
                                name = entry.filename,
                                path = joinRemote(directory, entry.filename),
                                isDirectory = attributes.isDirectory,
                                size = attributes.size.takeIf { !attributes.isDirectory },
                                modifiedEpochMillis = attributes.modifyTime?.toMillis(),
                                permissions = formatPermissions(attributes.permissions),
                                mimeType = null,
                            ),
                        )
                    }
                    if (entry.attributes.isDirectory) {
                        pending.add(joinRemote(directory, entry.filename) to depth + 1)
                    }
                }
            }
            results
        }

    /**
     * Deletes children before parents, iterative post-order on an explicit stack.
     *
     * `rmdir` on a non-empty directory fails server-side, so the depth-first order is not an
     * optimization — it is the only order that works. The depth cap from
     * [dev.eclipse.ssh.ssh.SftpDirectoryService] is honoured in spirit here with the same number,
     * so a hostile tree fails with a message rather than exhausting memory.
     */
    private fun deleteTree(sftp: SftpClient, path: String) {
        if (!sftp.stat(path).isDirectory) {
            sftp.remove(path)
            return
        }
        val stack = ArrayDeque<DeleteNode>()
        stack.add(DeleteNode(path, depth = 0))
        while (stack.isNotEmpty()) {
            val node = stack.last()
            if (node.depth > MAX_SEARCH_DEPTH) {
                throw IOException("Stopped after $MAX_SEARCH_DEPTH levels under \"$path\". The server may be reporting a directory that contains itself.")
            }
            if (!node.expanded) {
                node.expanded = true
                sftp.readDir(node.path).forEach { entry ->
                    if (isPlainEntryName(entry.filename)) {
                        stack.add(DeleteNode(joinRemote(node.path, entry.filename), node.depth + 1))
                    }
                }
            } else {
                stack.removeLast()
                sftp.rmdir(node.path)
            }
        }
    }

    /** One directory still to delete, and whether its children have been queued. */
    private class DeleteNode(val path: String, val depth: Int) {
        var expanded = false
    }

    private companion object {
        const val MAX_SEARCH_DEPTH = 64
    }
}

/** The last segment of a POSIX path — the name a copy or move keeps. */
private fun String.entryName(): String = trimEnd('/').substringAfterLast('/')

/**
 * Makes the SFTP provider for one host.
 *
 * A factory rather than a singleton-in-a-map because a provider is a thin, stateless view over the
 * session store: making a new one per host is cheaper than keeping stale ones after the user closes
 * a tab, and each operation re-resolves the live session anyway, so there is nothing cached to go
 * wrong.
 */
@Singleton
class SftpProviderFactory @Inject constructor(
    private val sessionStore: SshSessionStore,
    private val connectionManager: SshConnectionManager,
    private val directoryService: SftpDirectoryService,
) {
    fun forHost(host: HostProfile): SftpFileSystemProvider = SftpFileSystemProvider(
        hostId = host.id,
        hostName = host.name,
        username = host.username,
        sessionStore = sessionStore,
        connectionManager = connectionManager,
        directoryService = directoryService,
    )
}
