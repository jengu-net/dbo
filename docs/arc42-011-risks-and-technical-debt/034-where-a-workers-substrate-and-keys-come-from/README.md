**Open, and small: two configuration questions a working carrier left behind.
A worker inside the deployment is carried by the substrate and proven to be,
but what it is given to do that with is still stated by the application in both
cases — a substrate URL and a pair of private keys — and neither is obviously
the application's to state.**

# Where a worker's substrate and keys come from

Left over from the item that built the carrier. Nothing here is a defect: the
configuration works, is refused when incomplete, and is proven end to end by
`TheWorkArrivesOverTheSubstrateIT`. These are shape questions, and shape is
cheapest to change before deployments depend on it.

## Whether a co-located worker opens its own pool

A worker beside the serving half is in a process that already holds a substrate
connection, and today it opens a second one — `dbo-lane-substrate`, from
`dbo.worker.substrate.url`, beside the serving side's own from
`dbo.substrate.url`. A separated worker has nothing to reuse and must be given
one, so the property is not going away; the question is only whether a worker
in the same process should find the pool that is already there.

Against reusing it: the store manages its own connections per tenant on
purpose, and a worker that borrowed one would be the first thing to reach
across that line. For it: two pools against one database, sized separately,
in a process that could have had one.

Deciding it needs a number rather than an argument — what the second pool
costs in a deployment of the size the fleet actually runs.

## Whether the enrolment keys are the application's to hold

`dbo.worker.substrate.sealing-key` and `.signing-key` are private halves, read
from an application's configuration as base64 PKCS#8. That is the participant's
own material and it is right that the store never sees it — but an application
is not obviously where it lives either, and the serving half reads its own key
material from somewhere else entirely.

The question is whether a worker should read them from where the serving half
reads `dbo.auth.kek`, or whether a worker's enrolment is genuinely the
application's because a worker is the participant and nobody else can hold its
private halves for it. The second reading is probably right, which is why this
is a question rather than a plan.

## What settles it

For the pool: a measurement in a co-located deployment. For the keys: a written
answer either way, in the constraint that says where key material lives, so
that the next person configuring a worker reads it rather than infers it.
