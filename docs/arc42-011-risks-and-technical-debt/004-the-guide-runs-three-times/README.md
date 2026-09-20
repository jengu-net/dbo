**Open. The guide runs three times per CI run. Next: port the one step the shell harness still covers.**

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

Nobody is named to move the pin. The compose file names one image, a person
changes it, and until they do a guide step asserting behaviour newer than
the pin fails for a reason unrelated to the step.

## Steps

1. Port the remaining shell step and remove `guide-on-tree` and
   `check-tree.sh`.
2. Say who moves the pin and when, in the documentation rule, or move it
   from the build on a release.
3. When item 002 lands, decide whether the pinned run stays.
