package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a tick on the install dialog means once the pipeline reads it.
 *
 * The checkboxes and the install path meet at exactly three questions — which apt packages, which
 * npm globals, and does this selection need a Node.js newer than the archive's — so those three are
 * pure functions over a selection, and this is where they are pinned. The promise the install path
 * leans on hardest is the empty one: a selection of nothing resolves to nothing, which is what makes
 * "a plain install is byte-for-byte the install it always was" a fact rather than a hope.
 */
class OptionalPackagesTest {

    @Test
    fun `nothing ticked resolves to nothing at all`() {
        val none = emptySet<OptionalPackage>()

        assertThat(OptionalPackages.resolve(none)).isEmpty()
        assertThat(OptionalPackages.aptPackages(none)).isEmpty()
        assertThat(OptionalPackages.npmGlobals(none)).isEmpty()
        assertThat(OptionalPackages.implied(none)).isEmpty()
        assertThat(OptionalPackages.needsModernNode(none)).isFalse()
    }

    @Test
    fun `a coding agent pulls in the node it runs on`() {
        val resolved = OptionalPackages.resolve(setOf(OptionalPackage.OPENCODE))

        assertThat(resolved).containsExactly(OptionalPackage.NODE, OptionalPackage.OPENCODE).inOrder()
        // The dependency is named for the dialog, which is the only place it can be said before the
        // install rather than after it.
        assertThat(OptionalPackages.implied(setOf(OptionalPackage.OPENCODE)))
            .containsExactly(OptionalPackage.NODE)
    }

    @Test
    fun `the node a coding agent pulls in is the modern one`() {
        // The whole reason `requires` exists: ticking cline and not Node.js must still produce a
        // Node.js this app is willing to upgrade, because cline documents 20 as its floor.
        assertThat(OptionalPackages.needsModernNode(setOf(OptionalPackage.CLINE))).isTrue()
        assertThat(OptionalPackages.needsModernNode(setOf(OptionalPackage.KILO))).isTrue()
        assertThat(OptionalPackages.needsModernNode(setOf(OptionalPackage.OPENCODE))).isTrue()
        // And the entries that do not reach a registry do not ask for one.
        assertThat(OptionalPackages.needsModernNode(setOf(OptionalPackage.PYTHON))).isFalse()
        assertThat(OptionalPackages.needsModernNode(setOf(OptionalPackage.BUILD_TOOLS))).isFalse()
        assertThat(OptionalPackages.needsModernNode(setOf(OptionalPackage.TERMINAL_TOOLS))).isFalse()
    }

    @Test
    fun `a selection that was only an agent still installs node and npm`() {
        val apt = OptionalPackages.aptPackages(setOf(OptionalPackage.CLINE))

        assertThat(apt).containsAtLeast("nodejs", "npm")
        // No other entry may leak into a selection that did not ask for it: `python-is-python3` is
        // the marker for one that would.
        assertThat(apt).doesNotContain("python-is-python3")
        assertThat(OptionalPackages.npmGlobals(setOf(OptionalPackage.CLINE))).containsExactly("cline")
    }

    @Test
    fun `every entry installs something, and each names itself once`() {
        for (entry in OptionalPackages.all) {
            assertThat(entry.aptPackages.isNotEmpty() || entry.npmGlobals.isNotEmpty()).isTrue()
            assertThat(entry.label).isNotEmpty()
            assertThat(entry.summary).isNotEmpty()
        }
        // Labels are what the dialog draws and what a screen reader reads out; two entries sharing
        // one would be two checkboxes a user cannot tell apart.
        assertThat(OptionalPackages.all.map { it.label }).containsNoDuplicates()
        assertThat(OptionalPackages.all.map { it.name }).containsNoDuplicates()
    }

    @Test
    fun `everything ticked is every package, once each`() {
        val all = OptionalPackages.all.toSet()
        val apt = OptionalPackages.aptPackages(all)
        val npm = OptionalPackages.npmGlobals(all)

        assertThat(OptionalPackages.resolve(all)).containsExactlyElementsIn(OptionalPackages.all).inOrder()
        // Deduplicated across entries: both a hypothetical second agent and the Node entry could
        // name `nodejs`, and apt would be asked for it twice in one command.
        assertThat(apt).containsNoDuplicates()
        assertThat(npm).containsNoDuplicates()
        assertThat(OptionalPackages.implied(all)).isEmpty()
    }

    @Test
    fun `an entry's dependencies are declared above it, which is what makes resolution an order`() {
        // `resolve` walks the enum, so "dependencies install first" is a property of the file rather
        // than of the walk: an entry that named one below itself would install it after.
        val positions = OptionalPackages.all.withIndex().associate { (index, entry) -> entry to index }
        for (entry in OptionalPackages.all) {
            for (required in entry.requires) {
                assertThat(positions.getValue(required)).isLessThan(positions.getValue(entry))
            }
        }
    }
}
