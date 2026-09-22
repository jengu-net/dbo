**Open. The step is ported, the shell harness is gone from the tree run, and
who moves the pin is written down. Whether the pinned shell run earns its place
is decided, and on a count rather than on item 002: the suite runs all
ninety-nine published snippets, `up.sh` included as of this change, so the
guide's shell run covers nothing the suite does not — while the quickstart's
own run covers a compose file and two specs nothing else touches. Next: delete
the guide's shell run from the `quickstart` job, once the bring-up snippet has
run as published in CI.**

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

That leaves the shell runs, and the `quickstart` job is two of them: it runs
`quickstart/check.sh` and then `docs/guide/examples/check.sh`. They are not
the same kind of thing, and counting them together is what made this look like
one question.

**`quickstart/check.sh` is the only thing in the repository that runs
`quickstart/compose.yaml` and its two specs.** That compose file is what the
landing page tells a reader to copy, and the suite never touches it — a
different world, different tenants, reached through a different file. So the
suite does not cover it in any sense, and deleting that run would restore
exactly the failure the job was written for: an instruction nothing executes.
It stays.

**`docs/guide/examples/check.sh` is a different case, and it is nearly
closed.** It and the JUnit suite run the same ninety-nine snippet files
against the same pinned image. Counted rather than assumed: every published
snippet is executed by the suite except one — `up.sh`, the command that brings
the world up, which is the first thing a reader types and was the only one
nothing ran as published.

It ran as arguments instead, because it cannot run as published on the tree:
the published command names the published compose file, and substituting
another is the whole of what `DBO_GUIDE_COMPOSE` is for. So the suite runs it
as published where that is true — the pinned run — and by the same arguments
where it is not. That closes the gap where the overlap actually is.

**And the reason recorded for the overlap is now spent.** `guide-as-tests`
says in its own comment that it overlaps "on purpose and only for now…one step
is deliberately not ported, and the shell harness is what still covers it."
That step is ported, and this was the last published command the suite did not
run. What is left before the guide world comes up twice instead of three times
is the deletion, and nothing is now learned by deferring it to item 002 — that
item changes what the suite is made of, not which of these three runs covers
something the others do not.

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
3. ~~Decide whether the pinned runs stay.~~ **Decided, on a count rather than
   on item 002.** `quickstart/check.sh` stays: it is the only thing that runs
   the quickstart's own compose file and specs, which is a different artefact
   from the guide world and the one a reader copies first. The three guide-world
   bring-ups are the question, and the count says the shell one is the
   redundant one — the suite runs every published snippet, `up.sh` included as
   of this change.
4. Delete `docs/guide/examples/check.sh` from the `quickstart` job, and the
   stale sentence in `guide-as-tests` explaining an overlap that has ended.
   Held back from the same change as step 3 on purpose: the step above is what
   makes the deletion safe, and a deletion that lands with its own
   justification unproven is how coverage goes missing.
