# The sample world

The six tenant specs every example in the guide runs against, COPIED here so
that this application serves the same world the distribution does — the same
tenants, the same faces, the same zone — and the only difference between the
two is how the store is reached.

It is a copy and not a share, deliberately: the guide includes `sample/`'s
source, so nothing there may move until the guide has somewhere to move to.
Which of the two is current is [one paragraph in the risks
chapter](../../../docs/arc42-011-risks-and-technical-debt/026-two-samples-tell-one-story/README.md).
When `sample/` retires, this becomes the only copy.

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
