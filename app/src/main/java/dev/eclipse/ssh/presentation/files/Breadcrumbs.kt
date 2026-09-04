package dev.eclipse.ssh.presentation.files

/**
 * One clickable step of the path bar: what to show ([name]) and where tapping it goes ([path]).
 *
 * [path] is an opaque provider path, never assembled by the UI — it is either segmented out of a
 * POSIX string the SFTP provider owns (see [segmentPosixPath]) or captured verbatim as the user
 * descends (the trail the controller keeps for the local SAF provider, whose `content://` URIs cannot
 * be split). The UI hands [path] straight back to the provider when a crumb is tapped.
 */
data class Crumb(val name: String, val path: String)

/**
 * Splits a POSIX absolute [path] into crumbs, root first.
 *
 * This is the one place path arithmetic is allowed, and only for a POSIX provider: an SFTP path is a
 * real slash-separated string the provider round-trips unchanged, so `/home/user/project` can be cut
 * into `/`, `/home`, `/home/user`, `/home/user/project` without asking the backend. Local SAF paths
 * are `content://…/tree/…` document URIs where the slashes are URL structure, not directory
 * boundaries — feeding one here would invent folders that do not exist, so the controller builds the
 * local trail from navigation history instead and never calls this.
 *
 * A relative or empty path yields an empty list; the caller then falls back to the navigation trail.
 */
fun segmentPosixPath(path: String): List<Crumb> {
    if (!path.startsWith('/')) return emptyList()
    val crumbs = ArrayList<Crumb>()
    crumbs.add(Crumb(name = "/", path = "/"))
    val builder = StringBuilder()
    for (part in path.split('/')) {
        if (part.isEmpty()) continue
        builder.append('/').append(part)
        crumbs.add(Crumb(name = part, path = builder.toString()))
    }
    return crumbs
}

/**
 * The ellipsis stand-in used by [ellipsizeCrumbs]: a null in the returned list marks the collapsed
 * middle, which the path bar draws as a non-clickable "…". Every non-null entry is a real, tappable
 * crumb that kept its path.
 */
typealias CrumbOrEllipsis = Crumb?

/**
 * Collapses an over-long crumb trail so the path bar never forces the row to scroll or wrap: keeps the
 * root, a single ellipsis, and the last few crumbs — the ones nearest where the user is, which are the
 * ones worth a tap. A trail already within [maxVisible] is returned unchanged.
 *
 * The root is always kept because "jump back to the top" is the most common long-path move, and the
 * tail is kept because the parent and grandparent of the current folder are the next most common. The
 * collapsed middle is deliberately unreachable in one tap: offering ten faint middle crumbs on a phone
 * is what caused the horizontal overflow this exists to prevent.
 */
fun ellipsizeCrumbs(crumbs: List<Crumb>, maxVisible: Int = 4): List<CrumbOrEllipsis> {
    if (maxVisible < 3 || crumbs.size <= maxVisible) return crumbs
    // Reserve one slot for the root and one for the ellipsis; the rest is tail.
    val keepTail = (maxVisible - 2).coerceAtLeast(1)
    return buildList {
        add(crumbs.first())
        add(null)
        addAll(crumbs.takeLast(keepTail))
    }
}
