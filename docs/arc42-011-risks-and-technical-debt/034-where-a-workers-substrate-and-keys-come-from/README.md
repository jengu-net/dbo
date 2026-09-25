**Open, and one of its two questions is answered. The keys are the
application's and the reasoning is not a preference: a store that held a
participant's private halves could sign as it, which is the one thing the
signing key exists to prevent. The pool question was measured rather than
argued, and the measurement moved it: a co-located process holds TWENTY
connections on the substrate against a default `max_connections` of 100, from
two pools neither of which is sized. Sharing a pool is no longer the
interesting half of that — sizing one is, and the lane's is sized now: a host
holding one lane keeps five connections instead of ten, and one holding a dozen
gets thirty-eight instead of running out at ten. The serving side's is left,
because its demand is known after its pool is built and not before.**

# Where a worker's substrate and keys come from

Left over from the item that built the carrier. Nothing here is a defect: the
configuration works, is refused when incomplete, and is proven end to end by
`TheWorkArrivesOverTheSubstrateIT`. These are shape questions, and shape is
cheapest to change before deployments depend on it.

## The keys are the application's — settled

`dbo.worker.substrate.sealing-key` and `.signing-key` are private halves, read
from an application's configuration as base64 PKCS#8. The question was whether
they should come from where the serving half reads its own key material
instead.

**They must not.** A signature on the stream buys non-forgery and
non-repudiation: a carrier cannot manufacture a participant's ask, and a
participant cannot deny one it signed. Both properties rest entirely on the
private half being held by the participant and by nobody else. A store that
read that key from its own custody could sign as the participant, so the
signature would stop being evidence of anything — and the store is the party
the signature is shown TO. Moving the key to where `dbo.auth.kek` lives would
not be a tidier arrangement of the same guarantee; it would delete the
guarantee.

So a worker is the participant, its enrolment is its own, and the application
is where its private halves live. Where the application gets them from — an
environment variable, a mounted secret, a file — is a deployment's business and
not the store's, exactly as the store never sees them. This is now written in
[participants](../../arc42-008-crosscutting/processes-and-work/participants.md),
where the property it protects is stated, so the next person configuring a
worker reads it rather than infers it.

## What the pool actually costs — measured

A co-located run of `TheWorkArrivesOverTheSubstrateIT`, seven tenants served
and one lane held, queried against the substrate database itself:

```
substrateConnections=20    (plus one for the probe)
maxConnections=100
state=idle n=20            state=active n=0
```

Two pools account for all twenty, and the arithmetic agrees with the
measurement exactly: `dbo-substrate` on the serving side and
`dbo-lane-substrate` in the activator, **neither of which sets a size**, so both
take Hikari's default of ten and fill it.

### What that says, and it is not what the question assumed

**Twenty held for about eight connections' worth of work.** What actually needs
a connection on the substrate is one notification listener per door — seven,
one per served tenant — and one for the lane. The rest are idle capacity
nobody asked for.

**The duplicate pool is half the count, and the substrate is one database per
deployment.** Twenty per co-located process against a ceiling of a hundred is
five such processes before the substrate refuses connections, and a fleet
scales workers by replicas. With one pool it would be ten, and with a pool
sized for its listener it would be far fewer than that.

So the original framing — share the serving half's pool, or open a second —
was the smaller dial. Sharing would halve the count and cross a line the store
keeps on purpose; sizing costs nothing and crosses nothing, and the two are
independent. **Size first.**

### The lane's pool is sized — and it had to be

`dbo-lane-substrate` now takes its size from the lanes it was asked to carry
rather than a default: each lane opens its own durable connection over that one
pool and each of those keeps one to listen on, so the floor is one per lane and
is held for as long as the host runs. Measured on the same run, one lane held
now costs five connections where it cost ten, and the substrate's total went
from twenty to fifteen.

**It is not tidiness.** A fixed ten was survivable only while a lane's listener
could not hold a connection at all — which is what
[item 033](../033-the-notification-listener-cannot-unwrap/README.md) was doing
to it. Fixing the lane's unwrap in that item is what made each lane start
holding one, so a host with more than ten lanes would have waited on a listener
nobody was going to give up. Almost every worker serves many tenants, which
item 031 said while building the carrier. The two changes belong in one set and
are in one.

The serving side's pool is not sized here, and the reason is not symmetry: its
demand is one held connection per tenant whose door is on the stream, and it is
built before any tenant is up. Deriving it needs either a stated expectation or
a pool that grows, and that is a decision rather than an arithmetic.

### And a ceiling to check before it is met

The serving side's pool needs one held connection per tenant whose door is on
the stream, and its size does not grow with the tenants it serves. Ten is the
default; a node carrying about two dozen tenants is the shape this runtime is
built for. That is a ceiling, and it is not currently being met for a reason
that will go away: while
[item 033](../033-the-notification-listener-cannot-unwrap/README.md) crosses
the class space, a door's listener cannot unwrap its connection and degrades to
polling, so it is not holding one. **Fixing 033 is what makes this bite.**

The measurement that settles it is the one above, taken on a node serving more
tenants than the pool is sized for, with 033's remaining half fixed. Until
then it is a coupling worth writing down rather than a number.

## What is left

- Size the SERVING side's substrate pool, which needs a decision the lane's did
  not: its demand is per served tenant and it is built before any tenant is up,
  so either a deployment states what to expect or the pool has to grow.
- Then decide whether a co-located worker shares the serving half's pool. The
  argument against is unchanged — the store manages its connections per tenant
  on purpose — and after sizing there is much less on the other side of it.
- Take the ceiling measurement once 033's class space is answered.
