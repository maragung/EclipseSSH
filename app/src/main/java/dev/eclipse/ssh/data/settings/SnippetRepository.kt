package dev.eclipse.ssh.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.security.SecretCipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Same reasoning as the settings store: an unparseable file resets rather than bricking the screen. */
private val Context.snippetsDataStore by preferencesDataStore(
    name = "eclipse_snippets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The saved commands the terminal's snippet sheet runs.
 *
 * Stored through [SecretCipher] - the same AndroidKeyStore-backed AES-256-GCM key that protects host
 * passwords and private keys. A snippet is a command the user wrote, and the useful ones are exactly
 * the ones that carry a secret: a `mysql -p`, an `export TOKEN=`, a `curl` with an Authorization
 * header. They were written to the app's own preferences file in the clear, which is a credential in
 * unsafe storage anywhere that file can be read - a rooted phone, or a forensic image of one. The
 * label is encrypted along with the command for the same reason: people name a snippet after what it
 * does.
 */
@Singleton
class SnippetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
) {
    private val snippetsKey = stringPreferencesKey("snippets")

    val snippets: Flow<List<Snippet>> = context.snippetsDataStore.data.map { prefs ->
        read(prefs[snippetsKey])
    }

    suspend fun save(snippet: Snippet) {
        val clean = snippet.copy(label = sanitize(snippet.label), command = sanitize(snippet.command))
        editPrefs { prefs ->
            val current = read(prefs[snippetsKey]).filterNot { it.id == clean.id }
            prefs[snippetsKey] = write(current + clean)
        }
    }

    suspend fun delete(id: String) {
        editPrefs { prefs ->
            prefs[snippetsKey] = write(read(prefs[snippetsKey]).filterNot { it.id == id })
        }
    }

    /**
     * Every write goes through here so none of them runs on the caller's dispatcher.
     *
     * DataStore invokes an `edit` transform with `withContext(callerContext)`, so a transform started
     * from `viewModelScope` runs on the main thread -- AES encryption plus a file write on the UI thread. Worse, writes are
     * serialised through a single actor, so one transform parked on a stalled dispatcher blocks every
     * later write to this store for the life of the process.
     */
    private suspend fun editPrefs(block: suspend (MutablePreferences) -> Unit): Preferences =
        withContext(Dispatchers.IO) { context.snippetsDataStore.edit(block) }

    /**
     * Encrypts the whole list as one payload rather than field by field.
     *
     * Fewer, larger payloads mean fewer IVs, and nothing in the file reveals how many snippets there
     * are or how long any one of them is. The list is small and rewritten in full on every change
     * anyway, so there is nothing to be gained from encrypting the rows separately.
     */
    private fun write(snippets: List<Snippet>): String = encode(snippets).let(cipher::encrypt)

    /**
     * Reads either an encrypted payload or a plaintext one written by an older build.
     *
     * The discriminator is [FIELD_DELIMITER]: a plaintext row always contains it, and a vault payload
     * is two Base64 strings joined by a dot, so it never can. That is deliberate in preference to
     * "try to decrypt, and treat the failure as legacy plaintext" - a vault key that has gone missing
     * (app data restored onto another device, or the key invalidated) would make every payload look
     * like legacy plaintext and fill the snippet sheet with rows of Base64. This way it yields no
     * snippets, which is what an unreadable store honestly is, and the next [save] rewrites whatever
     * was still readable in encrypted form.
     */
    private fun read(raw: String?): List<Snippet> {
        if (raw.isNullOrBlank()) return emptyList()
        val plain = if (raw.contains(FIELD_DELIMITER)) raw else runCatching { cipher.decrypt(raw) }.getOrNull()
        return decode(plain.orEmpty())
    }

    /**
     * Removes the two control characters this format reserves.
     *
     * A label containing [FIELD_DELIMITER] shifted every field after it, so the snippet came back
     * with a truncated name and somebody else's command; one containing [ROW_DELIMITER] split into
     * two unparseable halves and silently deleted itself. Neither character can be typed on a
     * keyboard, but a snippet assembled from pasted terminal output can carry one, and losing the
     * command a user just saved is the worst outcome available here.
     */
    private fun sanitize(value: String): String =
        value.filterNot { it == ROW_DELIMITER.single() || it == FIELD_DELIMITER.single() }

    private fun encode(snippets: List<Snippet>): String =
        snippets.joinToString(ROW_DELIMITER) { "${it.id}$FIELD_DELIMITER${it.label}$FIELD_DELIMITER${it.command}" }

    private fun decode(raw: String): List<Snippet> {
        if (raw.isBlank()) return emptyList()
        return raw.split(ROW_DELIMITER).mapNotNull { row ->
            // limit = 3 keeps any further separators inside the command instead of producing a
            // fourth field, which would have made the whole snippet vanish on the next read.
            val parts = row.split(FIELD_DELIMITER, limit = 3)
            if (parts.size == 3) Snippet(id = parts[0], label = parts[1], command = parts[2]) else null
        }
    }

    private companion object {
        const val ROW_DELIMITER = "\u001e"
        const val FIELD_DELIMITER = "\u001f"
    }
}
