package dev.eclipse.ssh.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.data.fs.FsModificationConflictException
import dev.eclipse.ssh.ui.editor.encoding.FileEncoding
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * One tab's whole relationship with its file, against a scripted filesystem.
 *
 * [EditorTabState] is the state holder the multi-tab editor keeps alive even for files that are
 * not on screen, so what it must not do is as important as what it must: never open a binary file
 * as mojibake and save it back, never lose the user's text to a failed upload, never silently
 * overwrite a file that changed on the server. Each of those prose rules becomes an assertion
 * here, the same split [EditorTabModelTest] makes for the model.
 *
 * Plain JVM on purpose: [androidx.compose.foundation.ScrollState] and [TextFieldValue] are
 * *constructed* here but never composed, and snapshot state carries no main-thread requirement at
 * construction time — the launch coroutines all run on the scope `runTest` hands them. Should
 * construction ever grow a real Android dependency, VaultBackupTest is the precedent for moving
 * one suite onto Robolectric for exactly that reason.
 */
class EditorTabStateTest {

    private val entry = FsEntry(
        name = "notes.txt",
        path = "/tmp/notes.txt",
        isDirectory = false,
        size = 5L,
        modifiedEpochMillis = 1_000L,
        permissions = null,
        mimeType = "text/plain",
    )

    /**
     * One file, scripted: `stat` answers from the configured size and a clock the test moves,
     * `read` returns the configured bytes, `write` records what it was handed. Failures are
     * installed, not simulated twice — the tab state must react to the same exceptions the real
     * providers throw.
     */
    private class ScriptedFileProvider(
        private val entry: FsEntry,
        override val providerId: String = "test",
        var bytes: ByteArray = "hello".toByteArray(),
        var statSize: Long? = 5L,
        var readFailure: RuntimeException? = null,
        var writeFailure: Exception? = null,
    ) : FileSystemProvider {
        var modified = 1_000L
        val writes = mutableListOf<ByteArray>()
        val guards = mutableListOf<Long?>()
        var reads = 0

        override suspend fun homePath(): String? = null
        override suspend fun parentPath(path: String): String? = null
        override suspend fun list(path: String): List<FsEntry> = emptyList()
        override suspend fun stat(path: String): FsEntry? =
            entry.copy(size = statSize, modifiedEpochMillis = modified)

        override suspend fun read(path: String): ByteArray {
            reads++
            readFailure?.let { throw it }
            return bytes
        }

        override suspend fun write(path: String, data: ByteArray, onlyIfUnmodifiedSince: Long?) {
            writeFailure?.let { throw it }
            guards += onlyIfUnmodifiedSince
            bytes = data
            modified += 1_000
            writes += data
        }

        override suspend fun createFile(parentPath: String, name: String): FsEntry =
            error("unused by the tab state")
        override suspend fun createDirectory(parentPath: String, name: String) =
            error("unused by the tab state")
        override suspend fun rename(path: String, newName: String) = error("unused by the tab state")
        override suspend fun copy(sourcePath: String, targetDirectoryPath: String) =
            error("unused by the tab state")
        override suspend fun move(sourcePath: String, targetDirectoryPath: String) =
            error("unused by the tab state")
        override suspend fun delete(path: String) = error("unused by the tab state")
        override suspend fun setPermissions(path: String, mode: Int) =
            error("unused by the tab state")
        override suspend fun search(root: String, query: String, maxEntries: Int): List<FsEntry> =
            emptyList()
    }

    private fun tab(
        provider: ScriptedFileProvider = ScriptedFileProvider(entry),
        isNewFile: Boolean = false,
        encoding: FileEncoding = FileEncoding.UTF_8,
    ) = EditorTabState(id = 1L, request = EditorRequest(entry, provider, isNewFile), encoding = encoding)

    @Test
    fun `startLoad adopts a readable file's text as both the working copy and the baseline`() = runTest {
        val provider = ScriptedFileProvider(entry)
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.loadState).isEqualTo(EditorLoad.Ready)
        assertThat(tab.textValue.text).isEqualTo("hello")
        assertThat(tab.savedText).isEqualTo("hello")
        assertThat(tab.dirty).isFalse()
        // The save guard is armed with the mtime the stat reported at load — the first save must
        // be comparing against the server as it was when the text was read.
        assertThat(tab.loadedModified).isEqualTo(1_000L)
    }

    @Test
    fun `startLoad refuses what the stat says is over the size cap, without reading it`() = runTest {
        val provider = ScriptedFileProvider(entry, statSize = 512 * 1024L + 1)
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        val state = tab.loadState
        assertThat(state).isInstanceOf(EditorLoad.TooLarge::class.java)
        assertThat((state as EditorLoad.TooLarge).bytes).isEqualTo(512 * 1024L + 1)
        // Refused before the read: a file too big to open is too big to fetch first.
        assertThat(provider.reads).isEqualTo(0)
    }

    @Test
    fun `startLoad refuses bytes that are not UTF-8 rather than opening mojibake`() = runTest {
        // 0xC3 0x28 is '(' behind a dangling lead byte: a strict decoder rejects it, and the
        // tab must reject it too — a permissive decode would save the mojibake back over the file.
        val provider = ScriptedFileProvider(entry, bytes = byteArrayOf(0xC3.toByte(), 0x28.toByte()), statSize = 2L)
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        val state = tab.loadState
        assertThat(state).isInstanceOf(EditorLoad.Failed::class.java)
        assertThat((state as EditorLoad.Failed).message).contains("UTF-8")
    }

    @Test
    fun `a brand-new file opens ready and empty without touching the disk`() = runTest {
        // The "new file" path must not read: there is nothing to read, and a provider that
        // errors on it is the honest one.
        val provider = ScriptedFileProvider(entry, readFailure = IllegalStateException("nothing to read"))
        val tab = tab(provider, isNewFile = true)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.loadState).isEqualTo(EditorLoad.Ready)
        assertThat(tab.textValue.text).isEmpty()
        assertThat(tab.savedText).isEmpty()
        assertThat(tab.dirty).isFalse()
        assertThat(provider.reads).isEqualTo(0)
    }

    @Test
    fun `a successful save writes the bytes, clears the dirty flag, and re-arms the guard`() = runTest {
        val provider = ScriptedFileProvider(entry)
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        tab.applyEdit(TextFieldValue("edited"))

        tab.save(this)
        testScheduler.advanceUntilIdle()

        // The provider saw the edit's bytes, guarded by the mtime the load reported.
        assertThat(provider.writes.single().decodeToString()).isEqualTo("edited")
        assertThat(provider.guards.single()).isEqualTo(1_000L)
        assertThat(tab.savedText).isEqualTo("edited")
        assertThat(tab.dirty).isFalse()
        assertThat(tab.saving).isFalse()
        // Re-armed from the post-write stat, so the *next* save does not conflict with this one.
        assertThat(tab.loadedModified).isEqualTo(provider.modified)
    }

    @Test
    fun `a conflicting save opens the question instead of deciding, and keeps the baseline`() = runTest {
        // The provider says the file changed underneath the editor: that is the user's call to
        // make, not the tab's, and nothing is overwritten and nothing is lost while they decide.
        val provider = ScriptedFileProvider(
            entry,
            writeFailure = FsModificationConflictException(entry.path),
        )
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        tab.applyEdit(TextFieldValue("edited"))

        tab.save(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.conflictOpen).isTrue()
        assertThat(tab.saving).isFalse()
        assertThat(provider.writes).isEmpty()
        assertThat(tab.savedText).isEqualTo("hello")
        assertThat(tab.dirty).isTrue()
    }

    @Test
    fun `a failed save reports the error and keeps the edit`() = runTest {
        val provider = ScriptedFileProvider(entry, writeFailure = RuntimeException("disk full"))
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        tab.applyEdit(TextFieldValue("edited"))

        tab.save(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.saveError).isEqualTo("disk full")
        assertThat(tab.saving).isFalse()
        // A failed upload must not look like a successful one: the text and the dirty flag both
        // survive so the user can retry or copy their work out.
        assertThat(tab.textValue.text).isEqualTo("edited")
        assertThat(tab.savedText).isEqualTo("hello")
        assertThat(tab.dirty).isTrue()
    }

    @Test
    fun `reloadFromDisk adopts the server's text and clock`() = runTest {
        val provider = ScriptedFileProvider(entry)
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        tab.applyEdit(TextFieldValue("thrown away"))

        provider.bytes = "fresh from server".toByteArray()
        provider.modified = 9_999L
        tab.reloadFromDisk(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.textValue.text).isEqualTo("fresh from server")
        assertThat(tab.savedText).isEqualTo("fresh from server")
        assertThat(tab.dirty).isFalse()
        assertThat(tab.loadedModified).isEqualTo(9_999L)
    }

    @Test
    fun `an edit is undone and redone through snapshots`() = runTest {
        val provider = ScriptedFileProvider(entry)
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        tab.applyEdit(TextFieldValue("hello there"))

        // The undo button's exact path: history decides, applySnapshot moves the text without
        // becoming an edit in the history itself.
        tab.applySnapshot(checkNotNull(tab.history.undo(tab.textValue)))
        assertThat(tab.textValue.text).isEqualTo("hello")
        tab.applySnapshot(checkNotNull(tab.history.redo(tab.textValue)))
        assertThat(tab.textValue.text).isEqualTo("hello there")
    }

    @Test
    fun `the tab key is identity - same provider and path, one tab`() {
        // Re-opening a file routes to the tab it already has by comparing this key, so two
        // opens of the same file must produce equal keys and a same-path-different-provider pair
        // must not — that pair is two different files that happen to share a path.
        val one = tab(ScriptedFileProvider(entry))
        val sameFileAgain = tab(ScriptedFileProvider(entry))
        assertThat(sameFileAgain.key).isEqualTo(one.key)

        val elsewhere = tab(ScriptedFileProvider(entry, providerId = "other"))
        assertThat(elsewhere.key).isNotEqualTo(one.key)
    }

    @Test
    fun `a latin 1 file loads and saves as iso 8859 1 without ever becoming utf 8`() = runTest {
        // "café £" as a Latin-1 era tool wrote it: 0xE9 and 0xA3, both invalid as UTF-8. The
        // whole point of the encoding choice is that these bytes cross the editor and come back
        // out the same ones — a UTF-8 save here would rewrite a file the user only opened.
        val latin1 = "café £".toByteArray(Charsets.ISO_8859_1)
        val provider = ScriptedFileProvider(entry, bytes = latin1, statSize = latin1.size.toLong())
        val tab = tab(provider, encoding = FileEncoding.ISO_8859_1)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.loadState).isEqualTo(EditorLoad.Ready)
        assertThat(tab.textValue.text).isEqualTo("café £")
        assertThat(tab.dirty).isFalse()

        tab.save(this)
        testScheduler.advanceUntilIdle()

        assertThat(provider.writes.single()).isEqualTo(latin1)
        assertThat(tab.dirty).isFalse()
    }

    @Test
    fun `a strict decode failure under the chosen encoding fails the load instead of crashing`() = runTest {
        // UTF-8 "café" read as ASCII: 0xC3 is past the seven-bit line. The refusal must land in
        // the same Failed state the UTF-8 path has always used, naming the charset that said no.
        val utf8 = "café".toByteArray(Charsets.UTF_8)
        val provider = ScriptedFileProvider(entry, bytes = utf8, statSize = utf8.size.toLong())
        val tab = tab(provider, encoding = FileEncoding.ASCII)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        val state = tab.loadState
        assertThat(state).isInstanceOf(EditorLoad.Failed::class.java)
        assertThat((state as EditorLoad.Failed).message).contains("ASCII")
    }

    @Test
    fun `redecoding swaps the text through the history and dirties only a real change`() = runTest {
        // 0x92 is a C1 control in ISO-8859-1 but the typographic apostrophe in Windows-1252: the
        // same byte, two readings, and picking between them is exactly what the selector is for.
        val bytes = byteArrayOf(0x69, 0x74, 0x92.toByte(), 0x73)
        val provider = ScriptedFileProvider(entry, bytes = bytes, statSize = bytes.size.toLong())
        val tab = tab(provider, encoding = FileEncoding.ISO_8859_1)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        assertThat(tab.textValue.text).isEqualTo("it\u0092s")
        assertThat(tab.dirty).isFalse()

        tab.redecode(FileEncoding.WINDOWS_1252)

        assertThat(tab.encoding).isEqualTo(FileEncoding.WINDOWS_1252)
        assertThat(tab.textValue.text).isEqualTo("it’s")
        // The bytes on disk did not move — only what the editor makes of them did — and the tab
        // says so the only honest way it can: dirty, so a save is a decision, not a no-op.
        assertThat(tab.dirty).isTrue()
        // The swap went through the history, so one undo takes the old reading back without
        // taking the user's edits (there are none here) with it.
        tab.applySnapshot(checkNotNull(tab.history.undo(tab.textValue)))
        assertThat(tab.textValue.text).isEqualTo("it\u0092s")
    }

    @Test
    fun `redecoding to an encoding that reads the same bytes the same way stays clean`() = runTest {
        // Pure ASCII decodes identically as UTF-8 and ISO-8859-1: the switch is a choice about
        // the next save, not about the text, and a save that would write the very same bytes is
        // not a change worth a dirty dot or an undo step.
        val ascii = "plain".toByteArray()
        val provider = ScriptedFileProvider(entry, bytes = ascii, statSize = ascii.size.toLong())
        val tab = tab(provider, encoding = FileEncoding.UTF_8)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        tab.redecode(FileEncoding.ISO_8859_1)

        assertThat(tab.encoding).isEqualTo(FileEncoding.ISO_8859_1)
        assertThat(tab.textValue.text).isEqualTo("plain")
        assertThat(tab.dirty).isFalse()
        assertThat(tab.history.canUndo).isFalse()
    }

    @Test
    fun `a refused redecode keeps the text, the encoding, and says why`() = runTest {
        // Latin-1 bytes cannot be re-read as UTF-8; the switch must refuse outright rather than
        // half-apply a new encoding over text the old one produced.
        val latin1 = "café".toByteArray(Charsets.ISO_8859_1)
        val provider = ScriptedFileProvider(entry, bytes = latin1, statSize = latin1.size.toLong())
        val tab = tab(provider, encoding = FileEncoding.ISO_8859_1)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()

        tab.redecode(FileEncoding.UTF_8)

        assertThat(tab.encoding).isEqualTo(FileEncoding.ISO_8859_1)
        assertThat(tab.textValue.text).isEqualTo("café")
        assertThat(tab.dirty).isFalse()
        assertThat(tab.saveError).contains("UTF-8")
    }

    @Test
    fun `a failed load gets a second chance through the encoding row`() = runTest {
        // The headline case of the selector: a Latin-1 file meets the UTF-8 default, the load
        // fails, and the options sheet on the failure screen is where the user answers it. The
        // re-decode is a fresh open — clean, guarded by the load's own clock.
        val latin1 = "café £".toByteArray(Charsets.ISO_8859_1)
        val provider = ScriptedFileProvider(entry, bytes = latin1, statSize = latin1.size.toLong())
        val tab = tab(provider)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        assertThat(tab.loadState).isInstanceOf(EditorLoad.Failed::class.java)

        tab.redecode(FileEncoding.ISO_8859_1)

        assertThat(tab.loadState).isEqualTo(EditorLoad.Ready)
        assertThat(tab.textValue.text).isEqualTo("café £")
        assertThat(tab.savedText).isEqualTo("café £")
        assertThat(tab.dirty).isFalse()
        assertThat(tab.loadedModified).isEqualTo(1_000L)

        tab.save(this)
        testScheduler.advanceUntilIdle()
        assertThat(provider.writes.single()).isEqualTo(latin1)
        assertThat(provider.guards.single()).isEqualTo(1_000L)
    }

    @Test
    fun `a save into an encoding that cannot hold the text refuses rather than corrupting`() = runTest {
        // Loaded as ISO-8859-1, then an emoji is pasted in. ISO-8859-1 cannot hold it, and the
        // refusal must be a message on the tab — not a crash of the save, and not a `?` written
        // over a character the user can no longer see the remains of.
        val latin1 = "café".toByteArray(Charsets.ISO_8859_1)
        val provider = ScriptedFileProvider(entry, bytes = latin1, statSize = latin1.size.toLong())
        val tab = tab(provider, encoding = FileEncoding.ISO_8859_1)
        tab.startLoad(this)
        testScheduler.advanceUntilIdle()
        tab.applyEdit(TextFieldValue("café 🚀"))

        tab.save(this)
        testScheduler.advanceUntilIdle()

        assertThat(tab.saveError).contains("ISO-8859-1")
        assertThat(provider.writes).isEmpty()
        assertThat(tab.textValue.text).isEqualTo("café 🚀")
        assertThat(tab.dirty).isTrue()
    }
}
