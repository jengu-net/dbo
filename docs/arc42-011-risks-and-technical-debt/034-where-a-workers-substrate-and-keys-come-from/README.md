**Open, and down to its last decision. The keys are the application's and
the reasoning is not a preference: a store that held a participant's private
halves could sign as it, which is the one thing the signing key exists to
prevent. Both pools are sized now. The lane's is sized from the lanes it
carries: a host holding one lane keeps five connections instead of ten, and
one holding a dozen gets thirty-eight instead of running out at ten. The
serving side's grows: three connections for each tenant's door on the stream,
added as the door opens and given back as it closes, because its demand is
known after its pool is built and not before. What is left is whether a
co-located worker shares the serving half's pool, and after sizing there is
little on the other side of that.**

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
could not hold a connection at all, because its connection came from a copy of
the driver it could not unwrap. Fixing that is what made each lane start
holding one, so a host with more than ten lanes would have waited on a listener
nobody was going to give up. Almost every worker serves many tenants, which
item 031 said while building the carrier.

### The serving side's pool grows with its doors

`dbo-substrate` starts at two connections and grows by
`StreamDoor.CONNECTIONS` — three — as each tenant's door opens, shrinking again
as one closes. The three are what a door holds: its listener, for the door's
life; its serving loop, one statement at a time; and what runs beside the loop
— the keeper waiting on a generation, a wake-up being sent, the durable layer's
own polls. The two beyond the doors are a door being opened and a payload set
aside. Growing rather than a stated expectation, because how many tenants a
node serves changes while it runs, and a number written in configuration is
right on the day it was written.

**It had to be done in the same change as the doors' connections.** The
serving pool was handed the database URL, so the driver was whichever copy had
registered itself first — under the Spring Boot assemblies, the
application's — and no door's listener could unwrap it. Each door retried once
a second and held nothing, which is why Hikari's default of ten survived eight
doors. The pool now opens its connections through `SubstrateConnections`, the
stream bundle's own driver resolution, the same as the lane's; each door's
listener then holds its connection, and a fixed ten falls short from the
fourth door. The fleet steps' substrates take their connections the same way,
for the same reason.

Measured with `samples/check-separated.sh substrate`, eight tenants served and
a worker in its own JVM: no unwrap warning on either side, where the server
logged one per door per second before.

### The per-step fear was unfounded

An earlier reading of item 032 held that a consumer could serve only one step,
which would have made this arithmetic one listener and one pool **per step**
rather than per substrate. It was a defect in the consumer rather than a limit
of the durable layer, and it is fixed: two steps sharing a substrate are served
by one consumer. The dial this item is about — place steps together and pay
once, or apart and pay per — works as designed.

## What is left

- Decide whether a co-located worker shares the serving half's pool. The
  argument against is unchanged — the store manages its connections per tenant
  on purpose — and after sizing there is much less on the other side of it.
