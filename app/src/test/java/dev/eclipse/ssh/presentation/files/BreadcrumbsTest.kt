package dev.eclipse.ssh.presentation.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The path bar's arithmetic, tested without a screen.
 *
 * [segmentPosixPath] is the one place path arithmetic is allowed to exist, and only for a POSIX
 * provider: every crumb it hands back is a path the SFTP provider round-trips unchanged, and a crumb
 * with an invented path would send the browser somewhere the server never listed. So the assertions
 * are on exact paths, not on names — "/home" is easy to produce and useless if tapping it goes to
 * "/home/user".
 *
 * [ellipsizeCrumbs] decides what survives a long trail, and the two invariants that matter are the
 * ones the collapsed middle exists for: the root stays (the most common long-path jump) and the
 * tail stays (the parent and grandparent of where the user is). Everything it returns must also be
 * a real crumb or the ellipsis stand-in — a null that is not where the middle was collapsed would
 * render as an untappable hole in a short path.
 */
class BreadcrumbsTest {

    // ---------------------------------------------------------------- segmentPosixPath

    @Test
    fun anAbsolutePathIsSplitRootFirstWithPrefixPaths() {
        val crumbs = segmentPosixPath("/home/user/project")

        assertThat(crumbs.map { it.name }).containsExactly("/", "home", "user", "project").inOrder()
        // Each step is the path of the directory it names, not merely a label — these are what a
        // tap hands back to the provider.
        assertThat(crumbs.map { it.path }).containsExactly("/", "/home", "/home/user", "/home/user/project").inOrder()
    }

    @Test
    fun theRootAloneIsOneCrumb() {
        assertThat(segmentPosixPath("/").map { it.name }).containsExactly("/")
        assertThat(segmentPosixPath("/").map { it.path }).containsExactly("/")
    }

    @Test
    fun repeatedAndTrailingSlashesDoNotBecomeEmptyCrumbs() {
        val crumbs = segmentPosixPath("/var//www/")

        assertThat(crumbs.map { it.name }).containsExactly("/", "var", "www").inOrder()
        assertThat(crumbs.map { it.path }).containsExactly("/", "/var", "/var/www").inOrder()
    }

    @Test
    fun aRelativePathYieldsNothingRatherThanInventedCrumbs() {
        // The caller falls back to the navigation trail when this is empty. A relative path that
        // produced crumbs anyway would hand the fallback's consumer a mix of the two.
        assertThat(segmentPosixPath("relative/path")).isEmpty()
        assertThat(segmentPosixPath("")).isEmpty()
    }

    // ---------------------------------------------------------------- ellipsizeCrumbs

    @Test
    fun aShortTrailIsReturnedUnchanged() {
        val crumbs = segmentPosixPath("/home/user")

        assertThat(ellipsizeCrumbs(crumbs)).isEqualTo(crumbs)
    }

    @Test
    fun aLongTrailKeepsTheRootAnEllipsisAndTheTail() {
        val crumbs = segmentPosixPath("/a/b/c/d/e/f/g")

        val ellipsized = ellipsizeCrumbs(crumbs)

        // The root survives, and so do the folders nearest where the user is.
        assertThat(ellipsized.first()).isEqualTo(Crumb("/", "/"))
        assertThat(ellipsized.last()).isEqualTo(Crumb("g", "/a/b/c/d/e/f/g"))
        assertThat(ellipsized[ellipsized.size - 2]).isEqualTo(Crumb("f", "/a/b/c/d/e/f"))
        // Exactly one hole, in the middle, and nowhere else.
        assertThat(ellipsized.count { it == null }).isEqualTo(1)
        assertThat(ellipsized.indexOfFirst { it == null }).isLessThan(ellipsized.lastIndex)
        // No crumb is dropped silently: every non-null entry is one of the originals.
        assertThat(ellipsized.filterNotNull().all { it in crumbs }).isTrue()
    }

    @Test
    fun aTrailExactlyAtTheLimitIsNotCollapsed() {
        // Four crumbs — the root plus three segments — because that is what the limit of four is
        // measured in; "/a/b/c/d" would be five. At the limit there is nothing to gain by
        // collapsing, and an ellipsis that replaced a visible crumb would hide a folder that fit.
        val crumbs = segmentPosixPath("/a/b/c")
        assertThat(crumbs).hasSize(4)

        assertThat(ellipsizeCrumbs(crumbs, maxVisible = 4)).isEqualTo(crumbs)
        assertThat(ellipsizeCrumbs(crumbs, maxVisible = 4).count { it == null }).isEqualTo(0)
    }
}
