# The sample world

The six tenant specs every example in the guide runs against. What each one is
and why the insurer is a release behind is [the guide's first
chapter](../index.md); this page is for somebody who arrived at the directory
rather than the chapter.

`mom.json` sits beside the others rather than inside `tenants/` on purpose: it
is the managing tenant, and the scan loop that retracts undeclared tenants must
not be able to retract the thing that records retractions.

## What was checked, and what it cost

All six were applied to a server from the pinned image and served their
capability statements, reporting `5.0.0` and `4.0.1`. A patient created at the
hospital was found again by identifier, changed, and read back as he was. The
same person was created at the insurer, whose R4 face refused an R5-shaped
`Coverage` by naming the profile it failed against.

First bring-up is not fast. On one laptop the managing tenant took about two
and a half minutes and each further tenant between twenty-five seconds and a
minute, almost all of it the terminology baseline. The cost is per database, so
a world is brought up once and kept — not once per test.

`../examples/check.sh` is the script that runs all of it.
