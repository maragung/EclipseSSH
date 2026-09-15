# proot-patches — fixes the pinned fork has not merged

The proot fork is pinned by commit and SHA256 in `linux/build.gradle.kts`
(`prootForkCommit`/`prootForkSha256`), so the tarball that gets compiled is
always the verifiable upstream tree. Fixes the fork needs but has not merged
ride here as numbered unified diffs; `fetchLinuxSource` applies them, in
filename order, to the freshly extracted fork tree (`patch -p1`) and drops a
`.patches-applied` marker (naming the applied files) so they are never
applied twice.

Each patch's preamble documents the failure it fixes, the evidence, and why
it is not upstream. When a patch is superseded by a new fork pin that
includes it upstream, delete the patch and bump the pin in the same change.

The build needs GNU `patch` on PATH (not in the stock ubuntu-24.04 runner
image — every CI workflow installs it alongside the native cache step), and
the `linux-native-*` cache keys hash this directory's `*.patch` files so a
patch change never restores a stale fork tree.

