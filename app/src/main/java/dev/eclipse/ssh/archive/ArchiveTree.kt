package dev.eclipse.ssh.archive

/**
 * A lazily built folder tree over a flat archive listing.
 *
 * A 850,000-entry ZIP's listing is a flat list of paths; what the user browses is folders. The
 * naive structure - one tree node per folder, all children eagerly attached - is exactly the
 * "build the whole tree at once" the large-archive requirement forbids: the nodes themselves
 * would double the listing's memory for no benefit, since the user opens perhaps a dozen folders.
 *
 * So this class holds the flat list (which the scan already paid for and which search wants
 * anyway - searching a tree is strictly worse) and answers *folder queries* against it: children
 * of a folder, entry at a path, whether a folder exists. Laziness here is not "build nodes on
 * demand" but "never build nodes at all"; the index the queries need - which entries sit directly
 * under which folder - is computed once per (re)load as a map from folder path to the slice of
 * the list under it, sharing the entry objects rather than copying them.
 *
 * All lookups are prefix-driven on the normalized '/'-separated shape, so a query is a scan of
 * one folder's slice, never of the whole list.
 */
class ArchiveTree(entries: List<ArchiveEntry>) {

    /** Entries in listing order, keyed by their normalized path for O(1) lookup. */
    private val byPath: Map<String, ArchiveEntry> =
        // LinkedHashMap: listing order preserved for the children() call's stable ordering.
        entries.associateBy { it.path }

    /**
     * Folder path -> the entries directly inside it, in listing order. Built once in the
     * constructor: the scan is over, this is rearrangement, and doing it lazily per query would
     * repeat the same O(n) walk for every folder the user opens.
     *
     * The map's values exclude directories' *own* directory entries when those entries carry no
     * information beyond existence - but real archives do list directories explicitly, so they
     * are kept and merged with implied parents (see [children]).
     */
    private val childrenIndex: Map<String, MutableList<ArchiveEntry>> = buildMap {
        for (entry in entries) {
            // Every real entry lives in its parent folder.
            parentOf(entry.path)?.let { parent ->
                getOrPut(parent) { mutableListOf() }.add(entry)
            }
            // A directory entry is also a *container* its children are indexed under, even when
            // it is empty - an empty folder must still appear in its own parent's listing.
            if (entry.isDirectory) getOrPut(entry.path) { mutableListOf() }
            // Entries nested deeper than one level imply the folders between: "a/b/c.txt"
            // implies "a" and "a/b" even when no directory entry for either exists.
            if (!entry.isDirectory) {
                entry.impliedParents().forEach { implied ->
                    if (byPath[implied]?.isDirectory != true) {
                        // Only synthesize when no real directory entry claimed the path - a real
                        // entry always knows more (size, mtime) than the implied placeholder.
                        getOrPut(implied) { mutableListOf() }
                    }
                }
            }
        }
        // Second pass: the implied folders need to appear as children of THEIR parents. Doing
        // this in a second pass keeps the first pass's iteration over `entries` clean while the
        // map is still being built.
        val impliedFolders = keys.filter { it !in byPath }
        for (folder in impliedFolders) {
            parentOf(folder)?.let { parent ->
                getOrPut(parent) { mutableListOf() }.add(impliedFolderEntry(folder))
            }
        }
    }

    /** The archive's root-level entries (both real and implied folders), in listing order. */
    fun rootChildren(): List<ArchiveEntry> = children("").orEmpty()

    /**
     * The entries directly inside [folderPath] ("" for the root), in listing order: directories
     * first then files, each group in listing order, because that is the shape every file
     * manager shows and the shape the sort control then re-orders.
     */
    fun children(folderPath: String): List<ArchiveEntry>? {
        val own = childrenIndex[normalizeFolder(folderPath)] ?: return null
        val (dirs, files) = own.partition { it.isDirectory }
        return dirs + files
    }

    /** The entry at an exact path, or null - the detail sheet and the preview's key. */
    fun entryAt(path: String): ArchiveEntry? = byPath[path]

    /** All entries whose path contains [query] (case-insensitive), capped at [limit]. */
    fun search(query: String, limit: Int = 500): List<ArchiveEntry> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        // byPath is a LinkedHashMap: iteration order is listing order, which makes the results
        // stable across repeat searches of the same query.
        return byPath.values.asSequence()
            .filter { it.path.lowercase().contains(needle) }
            .take(limit)
            .toList()
    }

    /** Whether the entry at [path] has any children - the folder-expansion affordance. */
    fun hasChildren(path: String): Boolean =
        !(childrenIndex[normalizeFolder(path)]?.isEmpty() ?: true)

    /**
     * The folder paths on the way from the root to [path], excluding [path] itself - what the
     * breadcrumb renders and what "extract this folder" collects ancestors for.
     */
    fun ancestorsOf(path: String): List<String> =
        path.split('/').dropLast(1).runningFold("") { acc, segment ->
            if (acc.isEmpty()) segment else "$acc/$segment"
        }

    /**
     * Every entry at or below [folderPath] - the selective-extract set for a folder. Includes the
     * folder's own directory entry when the archive listed one; the extract layer creates the
     * directory either way.
     */
    fun subtreeOf(folderPath: String): List<ArchiveEntry> {
        val folder = normalizeFolder(folderPath)
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return byPath.values.filter { it.path == folder || it.path.startsWith(prefix) }
    }

    /** Total entries the scan produced, including directories. */
    val entryCount: Int get() = byPath.size

    private fun normalizeFolder(path: String): String =
        path.trim('/').ifEmpty { "" }

    private fun parentOf(path: String): String? {
        val index = path.lastIndexOf('/')
        return if (index <= 0) {
            // A first-segment path's parent is the root (""); a path that IS the root has none.
            if (path.isEmpty()) null else ""
        } else {
            path.substring(0, index)
        }
    }

    /**
     * The placeholder shown for a folder the archive never listed explicitly. Sizes and times
     * are unknown - null rather than invented numbers - and the path is the identity every other
     * query uses, so the placeholder behaves like a real entry wherever it flows.
     */
    private fun impliedFolderEntry(path: String) = ArchiveEntry(
        path = path,
        isDirectory = true,
        size = 0L,
        compressedSize = null,
        modifiedEpochMillis = null,
        method = null,
        dataOffset = null,
        encrypted = false,
    )
}
