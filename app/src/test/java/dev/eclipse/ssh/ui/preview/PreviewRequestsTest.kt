package dev.eclipse.ssh.ui.preview

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The preview handoff, without a window in sight.
 *
 * A preview cannot be parcelled into an intent, so the request waits in [PreviewRequests] and the
 * intent carries a token for it. What that buys and what it costs are both asserted here: the token
 * is read exactly once, so a re-delivery of the intent finds nothing to reopen, and a token nobody
 * stored is indistinguishable from a spent one, so a hand-written intent cannot name a file.
 *
 * The same contract as `ForwardRequestsTest`, and the same reason for being pure JVM: the one thing
 * that must not depend on a composition, a lifecycle or a main looper is that a request is consumed
 * once.
 */
class PreviewRequestsTest {

    private val entry = FsEntry(
        name = "notes.txt",
        path = "/tmp/notes.txt",
        isDirectory = false,
        size = 5L,
        modifiedEpochMillis = 0L,
        permissions = null,
        mimeType = "text/plain",
    )

    private val provider: FileSystemProvider = NoProvider()

    @Test
    fun `a stored file request is handed over once and then gone`() {
        val token = PreviewRequests.putFile(FilePreviewRequest(entry, provider))

        val taken = PreviewRequests.takeFile(token)
        // The pair travels together: an entry without its provider names a file the window could
        // never read, which is why the two are one request rather than two.
        assertThat(taken?.entry).isEqualTo(entry)
        assertThat(taken?.provider).isSameInstanceAs(provider)

        // One-shot by design: a rotation, the recents screen or a crash-and-relaunch delivers the
        // same intent again, and a preview of a file the user already closed must not reopen itself
        // - for a remote file, that would also open a channel to do it.
        assertThat(PreviewRequests.takeFile(token)).isNull()
    }

    @Test
    fun `an archive request carries a reader that still reads`() {
        val archiveEntry = ArchiveEntry(
            path = "logs/app.log",
            isDirectory = false,
            size = 3L,
            compressedSize = null,
            modifiedEpochMillis = null,
            method = 0,
            dataOffset = 128L,
            encrypted = false,
        )
        val token = PreviewRequests.putArchiveEntry(
            ArchivePreviewRequest(archiveEntry) { "abc".toByteArray() },
        )

        val taken = PreviewRequests.takeArchiveEntry(token)
        assertThat(taken?.entry).isEqualTo(archiveEntry)
        // The reader is a closure over the open archive rather than a path, and it is the window's
        // only way to the entry's bytes - so it has to arrive callable, not merely non-null. A
        // request whose closure arrived dead would draw an empty body over a file that is not empty.
        val read = taken?.readEntry
        assertThat(read).isNotNull()
        assertThat(String(runBlocking { read!!.invoke() }!!)).isEqualTo("abc")
        assertThat(PreviewRequests.takeArchiveEntry(token)).isNull()
    }

    @Test
    fun `a null or unknown token hands nothing over`() {
        // A null token is an intent that never carried one - a hand-written intent, or a window the
        // system restored - and an unknown one is a token from a process that has since died. Both
        // answer the same way, because the window's only alternative is to show a file nobody asked
        // for: a preview that guessed would be reading a file the user never chose.
        assertThat(PreviewRequests.takeFile(null)).isNull()
        assertThat(PreviewRequests.takeArchiveEntry(null)).isNull()
        assertThat(PreviewRequests.takeFile("not-a-token")).isNull()
        assertThat(PreviewRequests.takeArchiveEntry("not-a-token")).isNull()
    }

    @Test
    fun `each request gets its own token`() {
        val first = PreviewRequests.putFile(FilePreviewRequest(entry, provider))
        val second = PreviewRequests.putFile(FilePreviewRequest(entry, provider))

        // A shared token would make "which file did this window open on" undecidable, which is the
        // one question the token exists to answer.
        assertThat(first).isNotEqualTo(second)
        assertThat(PreviewRequests.takeFile(second)).isNotNull()
        assertThat(PreviewRequests.takeFile(first)).isNotNull()
    }

    /** Nothing here reads through the provider; only its identity matters. */
    private class NoProvider : FileSystemProvider {
        override val providerId: String = "test"
        override suspend fun homePath(): String? = null
        override suspend fun parentPath(path: String): String? = null
        override suspend fun list(path: String): List<FsEntry> = emptyList()
        override suspend fun stat(path: String): FsEntry? = null
        override suspend fun read(path: String): ByteArray = error("unused")
        override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) = error("unused")
        override suspend fun createFile(parentPath: String, name: String): FsEntry = error("unused")
        override suspend fun createDirectory(parentPath: String, name: String) = error("unused")
        override suspend fun rename(path: String, newName: String) = error("unused")
        override suspend fun copy(sourcePath: String, targetDirectoryPath: String) = error("unused")
        override suspend fun move(sourcePath: String, targetDirectoryPath: String) = error("unused")
        override suspend fun delete(path: String) = error("unused")
        override suspend fun setPermissions(path: String, mode: Int) = error("unused")
        override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> = emptyList()
    }
}
