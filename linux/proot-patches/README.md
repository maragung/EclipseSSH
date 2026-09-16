# proot-patches — fixes the pinned fork has not merged

The proot fork is pinned by commit and SHA256 in `linux/build.gradle.kts`
(`prootForkCommit`/`prootForkSha256`), so the tarball that gets compiled is
always the verifiable upstream tree. Fixes the fork needs but has not merged
ride here as numbered unified diffs; `fetchLinuxSource` applies them, in
filename order, to the freshly extracted fork tree (`patch -p1`) and drops a
`.patches-applied` marker holding a fingerprint of the whole patch set —
every name and every byte.

The marker gates the tree as a whole, not patch by patch: the tree is kept
only when the fingerprint matches, and any change to any patch (or to the
set of them) makes the next build delete the extracted tree, re-extract it
and re-apply every patch in order. That is deliberate. A name-only marker
reads a rewritten patch as already applied, so the patches after it stack
onto a tree the rewrite no longer describes — and because `patch` accepts
fuzzy matches, that can produce a tree that is neither revision and build
without complaint. Re-extracting a 3.4 MB tarball costs a second; a tree
that is not exactly what this directory produces costs a debugging session.

Each patch's preamble documents the failure it fixes, the evidence, and why
it is not upstream. When a patch is superseded by a new fork pin that
includes it upstream, delete the patch and bump the pin in the same change.

The build needs GNU `patch` on PATH (not in the stock ubuntu-24.04 runner
image — every CI workflow installs it alongside the native cache step), and
the `linux-native-*` cache keys hash this directory's `*.patch` files so a
patch change never restores a stale fork tree.

