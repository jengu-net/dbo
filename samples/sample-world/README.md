# The sample world

The six tenant specs the sample applications serve, and the distribution
serves the same six — so the only difference between the two samples is how
the store is reached.

It sits beside the applications rather than inside one because more than one
thing is about it: the serving application bootstraps from it, and a test
seeds what it declares from it before declaring its own. A world inside one
application would have made the others reach into its directory.

It is a copy of `sample/world`, and a copy rather than a share because the
guide includes `sample/`'s source and nothing there may move until the guide
has somewhere to move to. Which of the two is current is [one paragraph in the
risks chapter](../../docs/arc42-011-risks-and-technical-debt/026-two-samples-tell-one-story/README.md).

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
