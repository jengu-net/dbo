**Open. The step is ported, the shell harness is gone from CI — the tree run is the JUnit suite now — and who moves the pin is written down. The guide still comes up three times, and what is left is deciding whether the pinned shell run earns its place. Next: item 002.**

# The guide runs three times in CI

A CI run drives the guide world three times: `quickstart` runs the published
commands as a shell script against the pinned image, `guide-on-tree` runs the
JUnit suite against a server built from the tree, and `guide-as-tests` runs
the same suite against the pinned image. `./verify`'s third phase is the tree
run again, locally.

**The step is ported and the shell harness is out of the tree run.** What
kept it there was one step — a write against a version that has moved —
which read as the store answering wrongly and was the harness: `Snippets.sh`
hands the known values TO a script and never reads back what the script sets,
so a setup capturing the new record's id captured it into a process that then
exited, and the snippet put to the collection instead of the record, which is
an upsert and has no version to precondition on. The id crosses back through
Java now.

**Deleting the tree job outright would have cost coverage**, which the item
did not notice: the `build` job does not run the guide, so `guide-on-tree` is
the ONLY place in CI the guide meets the code under review. Removing it would
leave the guide proven against the pinned image alone — exactly the failure
the job exists to prevent. So it runs the JUnit suite instead of the shell
one, through `DBO_GUIDE_COMPOSE`, which is the door `tree-world.sh` was
written for.

That leaves `quickstart` as the last shell run, against the pinned image. It
proves the published commands work as a reader pastes them, which the suite
approximates by sourcing the same snippets — so whether it still earns a
third bring-up is a real question, and it is the same question item 002 forces
anyway.

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

1. ~~Port the remaining shell step and remove `guide-on-tree` and
   `check-tree.sh`.~~ Done, with one change: `check-tree.sh` is gone and
   `guide-on-tree` stays, running the JUnit suite against the tree. It is the
   only CI job that tests the guide against the code under review.
2. ~~Say who moves the pin and when, in the documentation rule, or move it
   from the build on a release.~~ Done: the documentation rule says it, and
   the skill carries it as a MUST. Revisit the automated half when a release
   exists to hang it on.
3. When item 002 lands, decide whether the pinned runs stay — both of them,
   and `quickstart`'s shell script first, since the suite now covers every
   step it does.
