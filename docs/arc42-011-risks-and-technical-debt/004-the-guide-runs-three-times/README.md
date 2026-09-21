**Open. The guide runs three times per CI run. Who moves the pin is now written down. Next: port the one step the shell harness still covers, which is what lets the third run go.**

# The guide runs three times in CI

A CI run drives the guide world three times: `guide-as-tests` against the
pinned image, `guide-on-tree` as the shell harness against a server built
from the tree, and the third phase of `./verify`, which runs the JUnit
suite against the tree.

The shell run exists because one step is deliberately not ported to JUnit.
Once it is, the shell run has nothing the verify phase lacks. The
pinned-image run proves the published commands against the image a reader
pulls; when the guide becomes the sample application's story
([item 002](../002-sample-application/README.md)), what it proves has to be
decided again.

Who moves the pin is now written down. The
[documentation rule](../../arc42-002-constraints/working-rules/documentation.md)
says it is whoever writes the step that needs it, in the same change, and
that the tag is checked against the registry rather than read off the commit
log — because an image exists only if the publish that makes it succeeded,
and pinning one that was never published fails as `manifest unknown`.

Automating it was the alternative and was not taken. There is no release to
hang it on: the registry deliberately carries no floating tag until one
exists, so "move the pin on a release" is a rule about an event that does not
happen yet. A rule a person follows is what there is until then, and the run
that proves the step against the pinned image is the one that catches them
forgetting.

## Steps

1. Port the remaining shell step and remove `guide-on-tree` and
   `check-tree.sh`.
2. ~~Say who moves the pin and when, in the documentation rule, or move it
   from the build on a release.~~ Done: the documentation rule says it, and
   the skill carries it as a MUST. Revisit the automated half when a release
   exists to hang it on.
3. When item 002 lands, decide whether the pinned run stays.
