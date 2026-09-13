package dev.eclipse.ssh.archive

import dev.eclipse.ssh.ssh.SshConnectionManager
import dev.eclipse.ssh.ssh.SshSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClient.OpenMode

/**
 * An archive's bytes, read in bounded pieces over the host's existing SFTP session.
 *
 * This is the class the whole feature's promise hangs on: a ZIP's listing arrives as one open,
 * a handful of ranged reads at the end of the file, and one close - never a download. The
 * [ArchiveByteSource] contract is suspend; the MINA [SftpClient] calls under it are blocking, so
 * every read runs on [Dispatchers.IO] and the channel pair lives once per source, not per read.
 *
 * One channel per [ArchiveByteSource] lifetime: the browser holds one source for as long as the
 * archive is open, and opening a channel per 64 KB chunk would turn a TAR scan into hundreds of
 * handshakes. The client and handle are opened lazily on first read and closed by [close], which
 * the browser calls when the archive closes - including on cancellation - so a dropped screen
 * never leaks a channel on the host's session.
 *
 * Sequential reads ([read]) and ranged reads ([readAt]) share the channel and one cursor; ZIP
 * uses only [readAt] (its cursor stays at zero), TAR only [read] (it never jumps).
 */
class SftpArchiveByteSource(
    private val connectionManager: SshConnectionManager,
    sessionStore: SshSessionStore,
    hostId: String,
    hostName: String,
    /** The remote archive's POSIX path. */
    private val remotePath: String,
    /** The archive's size as the caller's stat reported it - avoids a stat round-trip here. */
    override val size: Long,
) : ArchiveByteSource {

    private val session = sessionStore.primarySession(hostId)
        ?: throw IllegalStateException("$hostName is not connected")

    /** The lazily opened channel pair. Guarded by [mutex] so concurrent reads serialize on it. */
    private var channel: SftpClient? = null
    private var handle: SftpClient.CloseableHandle? = null
    private val mutex = Mutex()
    private var cursor = 0L

    /**
     * The SFTP read ceiling. The protocol's read packet is bounded (MINA caps it at 32 KB by
     * default), so asking for more returns less; looping until the ask is satisfied is the
     * caller-visible contract ("exactly [length] bytes") and the reason this class exists rather
     * than a naked read call.
     */
    private val sftpReadChunkBytes = 32 * 1024

    private suspend fun readChunk(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = channel
                val client: SftpClient
                val h: SftpClient.CloseableHandle
                if (existing != null && handle != null) {
                    client = existing
                    h = handle!!
                } else {
                    // `openSftp` deliberately hands the lifetime to the caller (its KDoc explains
                    // why withSftp's use{} is wrong for this shape); here the lifetime is this
                    // source's own, ended in [close].
                    client = connectionManager.openSftp(session)
                    h = try {
                        client.open(remotePath, OpenMode.Read)
                    } catch (error: Throwable) {
                        // The archive was deleted, replaced or refused between the stat that
                        // produced [size] and this open - a real race with anything that rewrites
                        // files on the host. [close] only closes what the fields hold, and this
                        // client never reaches them, so it is closed here or not at all: a live
                        // SFTP channel per failed attempt, one more server-side slot gone each
                        // time the user retries the browse. Already on IO, inside this block's
                        // withContext - an SFTP close writes to the socket and belongs here.
                        runCatching(client::close)
                        throw error
                    }
                    channel = client
                    handle = h
                }
                client.read(h, offset, dst, dstOffset, length)
            }
        }

    override suspend fun read(maxBytes: Int): ByteArray {
        val from = cursor
        val result = readAt(from, maxBytes)
        cursor = from + result.size
        return result
    }

    override suspend fun readAt(offset: Long, length: Int): ByteArray {
        if (offset >= size) return ByteArray(0)
        require(length >= 0) { "Negative length $length" }
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            // The ask is capped at the protocol's own chunk size: the loop turns MINA's "here is
            // what fits in one packet" into the source contract's "here is everything asked".
            val ask = minOf(length - filled, sftpReadChunkBytes)
            val n = readChunk(offset + filled, out, filled, ask)
            if (n <= 0) {
                // A read that should have succeeded returned EOF: the file is shorter than the
                // stat said - it changed underneath us. Corrupt, not short: the same rule the
                // in-memory backing enforces, so the parsers see one truth from both backings.
                throw ArchiveCorruptException("Archive $remotePath ended before offset ${offset + length}")
            }
            filled += n
        }
        return out
    }

    override suspend fun seek(offset: Long) {
        cursor = offset.coerceIn(0L, size)
    }

    /** Closes the channel. Idempotent; call when the archive browser closes or the scan cancels. */
    override suspend fun close() {
        mutex.withLock {
            val h = handle
            val client = channel
            handle = null
            channel = null
            withContext(Dispatchers.IO) {
                runCatching { h?.close() }
                runCatching { client?.close() }
            }
        }
    }
}
