package dev.eclipse.ssh.data.saf

import androidx.documentfile.provider.DocumentFile

/** Iteratively indexes a SAF document tree as (relativePath, document, sizeBytes). */
object LocalSyncIndex {
    /** Guards against StackOverflowError / runaway walks on deep or cyclic trees. */
    const val MAX_DEPTH = 32
    const val MAX_ENTRIES = 50_000

    fun walk(root: DocumentFile): List<Triple<String, DocumentFile, Long>> {
        val out = mutableListOf<Triple<String, DocumentFile, Long>>()
        // Breadth-first with an explicit queue: recursion overflowed the stack on deeply
        // nested trees, and SAF shortcuts can make a tree effectively unbounded.
        val pending = ArrayDeque<Triple<DocumentFile, String, Int>>()
        pending.add(Triple(root, "", 0))
        while (pending.isNotEmpty() && out.size < MAX_ENTRIES) {
            val (dir, rel, depth) = pending.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (out.size >= MAX_ENTRIES) break
                val name = child.name ?: continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (child.isDirectory) {
                    out += Triple(childRel, child, 0L)
                    if (depth + 1 < MAX_DEPTH) pending.add(Triple(child, childRel, depth + 1))
                } else {
                    out += Triple(childRel, child, runCatching { child.length() }.getOrDefault(0L))
                }
            }
        }
        return out
    }
}
