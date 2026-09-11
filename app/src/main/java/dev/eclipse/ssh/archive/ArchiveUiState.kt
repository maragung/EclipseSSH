package dev.eclipse.ssh.archive

/**
 * The archive browser's state machine, without a filesystem or a UI.
 *
 * Pure for the same reason every other model in this app is pure: the prose requirements - the
 * archive is never downloaded to be listed, a changed archive never keeps serving stale
 * metadata for an operation, a password is asked for only when the archive actually needs one -
 * each become an assertion here instead of a hope in the wiring.
 *
 * The states the screen renders:
 *  - [Loading] (with progress and a cancellable scan),
 *  - [Ready] (the tree is browsable; [serverChanged] may be raised while ready),
 *  - [Failed] (corrupt/unsupported/connection lost, with the message the user sees),
 *  - [PasswordRequired] (encrypted archive, before any tree exists).
 *
 * The transitions the screen drives:
 *  open -> scan -> ready | failed | password-required;
 *  ready + watcher verdict -> [serverChanged] (a notification - reload is the user's call);
 *  password supplied -> rescan with it;
 *  reload -> rescan (fresh metadata, the flag clears).
 */
sealed interface ArchiveUiState {

    /** The scan is running. [progress] is null until the first callback arrives. */
    data class Loading(val progress: ArchiveScanProgress?) : ArchiveUiState

    /**
     * The archive is open and browsable.
     *
     * @property tree the lazy folder view over the scanned entries
     * @property format the container format, for the properties sheet
     * @property stats the size/mtime the archive was validated against - the watcher's baseline
     */
    data class Ready(
        val tree: ArchiveTree,
        val format: ArchiveReader.Format,
        val stats: ArchiveValidation,
    ) : ArchiveUiState

    /** The scan ended in something the user must be told, with the message to tell them. */
    data class Failed(val message: String) : ArchiveUiState

    /** The archive is encrypted and needs a password before anything can be listed. */
    data class PasswordRequired(val message: String) : ArchiveUiState
}

/**
 * The remote archive's size and mtime as of the scan that produced a tree.
 *
 * This is the change-detection baseline: on every watcher tick the browser re-stats the remote
 * file, and a difference in either field (or the file's vanishing) means the archive the user
 * is browsing is no longer the archive on the server. What happens next is a notification, not
 * an action - reload is always the user's choice, exactly as the requirement states.
 */
data class ArchiveValidation(
    val size: Long,
    val modifiedEpochMillis: Long?,
) {
    /**
     * Whether a fresh stat disagrees with this baseline. The same unknown-not-different rule as
     * everywhere else: a backend that cannot report an mtime must not put every archive into
     * conflict.
     */
    fun differsFrom(size: Long?, modifiedEpochMillis: Long?): Boolean {
        if (size != null && size != this.size) return true
        if (modifiedEpochMillis != null && this.modifiedEpochMillis != null &&
            modifiedEpochMillis != this.modifiedEpochMillis
        ) return true
        // A file that vanished is the strongest change there is.
        if (size == null) return true
        return false
    }
}

/**
 * Maps a scan failure to the state the user sees, choosing the message.
 *
 * The exceptions carry type information the UI switches on (corrupt vs password vs unsupported
 * vs connection); anything else is a connection-shaped failure in practice, because the scan's
 * non-I/O failure modes all have their own types.
 */
fun archiveFailureState(error: Throwable): ArchiveUiState.Failed = ArchiveUiState.Failed(
    when (error) {
        is ArchivePasswordRequiredException ->
            // Password-required is not a failure of the archive; the state exists so the unlock
            // sheet is offered. But if the path reaches here, the message is the honest one.
            error.message ?: "This archive is password protected."
        else -> "Unable to read the archive. It may be corrupted or unsupported."
    },
)

/** Same mapping, but password-required keeps its own state so the unlock sheet is what shows. */
fun archiveStateFor(error: Throwable): ArchiveUiState = when (error) {
    is ArchivePasswordRequiredException -> ArchiveUiState.PasswordRequired(
        error.message ?: "This archive is password protected."
    )
    else -> archiveFailureState(error)
}
