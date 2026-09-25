**Open, and larger than the log line it was filed as. The durable layer's
notification listener cannot unwrap a pooled connection to
`org.postgresql.PGConnection`, so every wake-up it would have delivered waits
out a poll instead. The cause is not the driver: under an application built on
the Spring Boot assemblies, `dbo-stream` is loaded TWICE — once as a bundle and
once from the application's own classpath — and three copies of the driver are
live at once. The shipped distribution has none of this. What the warning is
reporting is a second class space, which the assemblies are supposed not to
have.**

# The notification listener cannot unwrap a pooled connection

Found while closing the item that carried a worker's lane on the substrate.
It is not that item's defect and it outlives it.

## What it says

```
WARN d.d.t.d.NotificationListenerSource : Notification listener error:
  Cannot unwrap to org.postgresql.PGConnection
```

once per attempt, on a dedicated listener thread, for as long as the process
runs — 621 times in one run of one test.

## What was measured

A probe at the two places DBOS is handed a DataSource, printing the class it
asks for, the class the connection actually is, and the loader of each. In one
run of `TheWorkArrivesOverTheSubstrateIT`:

| | the class `StreamDoor`/`StreamLane` is | the `PGConnection` it asks for | the connection it gets |
|---|---|---|---|
| the lane | bundle `cloud.jengu.dbo.stream [7]` | bundle `org.postgresql.jdbc [32]` | the **application classpath** |
| each door | the **application classpath** | the **application classpath** | bundle `org.postgresql.jdbc [32]` |

Every pairing is crossed, and in opposite directions. Read it twice: the two
rows disagree about which class `StreamDoor` even is.

`dbo-subscriptions` was probed the same way and is **correct** — it asks for
bundle 32's interface and is handed bundle 32's connection, and its unwrap
succeeds. Its build file already carries the mandatory `org.postgresql` import
and the reasoning for it, and that import works. So the fix that was made
there is sound and is not what is failing.

## What it actually is, and it is two things

**One: `dbo-stream` exists twice.** The door is constructed by the tenant
runtime in bundle `cloud.jengu.dbo.tenant [30]`, and the class it gets is the
one on the **application's** classpath, not the one in bundle 7. An
application built on the Spring Boot assemblies has the store's jars on its own
classpath — that is how it installs them — and both copies are reachable. One
class space is the property `AHostOfTwoHalvesReachesOneContainerTest` is there
to hold, and for this bundle it does not hold.

**Two: the lane's pool does not use the driver its comment says it does.** The
activator sets `setDriverClassName("org.postgresql.Driver")` with the comment
that this is the private copy in `lib/`, deliberately not the container's
shared bundle. Hikari resolves that name through the **thread context class
loader**, which under Spring Boot is the application's — so the pool is built
from the application's driver while the same bundle asks bundle 32 for the
interface. The comment describes an intent the code does not achieve, and it
would still be crossed if it did: lib/ and bundle 32 are also two copies.

## Where it does not happen

`ServerDistIT` boots the shipped distribution as a subprocess and the warning
appears **zero** times. There the container is the whole application and there
is no second classpath beside it. So this is a property of the assemblies'
packaging, not of the store — which is what makes it worth an item rather than
a line, because the assemblies are the shape an integrator copies.

## What it costs

- **Latency, chosen by nobody.** A queue that would have been woken waits out
  its poll interval instead, default one second, on every wake-up the stream
  would have carried.
- **A log that cannot be read**, at the level operators are asked to act on.
- **And the part that is not about logs at all:** a bundle with two live
  copies is a deployment where which copy a caller got decides what it can do.
  Here it surfaces as a failed unwrap. Nothing says it would surface that
  gently next time.

## What it does not cost

Correctness, today. The poll underneath is what the listener sits on top of,
by design, and the fallback is the ordinary path rather than a degraded one.

## What has to be decided

The second defect is contained and the first is not. Whether the assemblies
should stop putting the store's bundles on the application classpath, or
whether the container should be told those packages are the system bundle's,
is a packaging decision with a blast radius — and the three container tests
are what would say whether an answer resolves.

## What proving a fix looks like

A run of `:samples:spring-boot-worker-app:test` whose output contains the line
zero times, with `AStreamLaneIsToldItHasWorkIT` — which depends on the wake-up
actually arriving — still passing, and the three container tests still green.
All three are needed: the warning can be silenced by switching notification
off, and that is the fix this item is not asking for.
