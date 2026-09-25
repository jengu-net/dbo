**Open, and down to one half of two. The smaller half is fixed and measured:
a lane's pool resolved its driver by NAME, which is resolved globally, so it
was built from the application's copy while the same bundle asked the
container's for the interface. It resolves the driver through its own wiring
now, and the probe that found the crossing reports `unwrap=OK` for the lane
where it reported `FAILED`. The half that
is left is the reason there were two copies to choose between: under an
application built on the Spring Boot assemblies `dbo-stream` is loaded TWICE,
once as a bundle and once from the application's own classpath, and every door
is the classpath's copy asking the classpath's interface and being handed the
bundle's connection. The shipped distribution has none of it. What the
remaining warnings report is a second class space, which the assemblies are
supposed not to have.**

# The notification listener cannot unwrap a pooled connection

Found while closing the item that carried a worker's lane on the substrate.
It is not that item's defect and it outlives it.

## What it says

```
WARN d.d.t.d.NotificationListenerSource : Notification listener error:
  Cannot unwrap to org.postgresql.PGConnection
```

once per attempt, on a dedicated listener thread, for as long as the process
runs — hundreds of times in one run of one test.

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

The counts in this item are from `TheWorkArrivesOverTheSubstrateIT`, and they
are **not** a measure of anything: the listener retries on a timer, so the
total says how long the run took as much as how many listeners are crossed.
What the fix below is measured by is the unwrap itself, which either succeeds
or does not.

## What it actually is, and it is two things

**One: `dbo-stream` exists twice.** The door is constructed by the tenant
runtime in bundle `cloud.jengu.dbo.tenant [30]`, and the class it gets is the
one on the **application's** classpath, not the one in bundle 7. An
application built on the Spring Boot assemblies has the store's jars on its own
classpath — that is how it installs them — and both copies are reachable. One
class space is the property `AHostOfTwoHalvesReachesOneContainerTest` is there
to hold, and for this bundle it does not hold.

**Two — FIXED: the lane's pool did not use the driver its comment said it
did.** The activator set `setDriverClassName("org.postgresql.Driver")` with the
comment that this was the private copy in `lib/`, deliberately not the
container's shared bundle. A driver class NAME is resolved globally, though:
Hikari scans what `DriverManager` has registered and then falls back to the
thread context loader, and under Spring Boot the application's copy had
registered itself. So the pool was built from a third copy — neither the one
the comment named nor the one the same bundle imports the interface from.

The activator resolves the driver itself now, with `Class.forName` on its own
loader, and hands Hikari a small `DataSource` over it. That is the same
resolution that gives it `org.postgresql.PGConnection`, so the two agree
wherever the bundle is wired: to the container's driver bundle where one
exists, and to `lib/` in a participant's container where none does. The
optional import has two homes and both are self-consistent now rather than
only the one that happened to be exercised.

Measured rather than reasoned: the same probe that found the crossing reports
`unwrap=OK` for the lane where it reported `FAILED` before.

**And it took an import with it, which is the part worth keeping.** The moment
this bundle opened a connection with its own driver, the participant-only
container failed to start on `NoClassDefFoundError: javax/net/SocketFactory`.
Every connection the copy in `lib/` opens goes through `javax.net`, TLS or
not, and that package was never imported — it did not have to be, because that
copy was never the one used. A pool built from a driver class name got
whichever copy `DriverManager` or the thread context loader had, and that one
resolved `javax.net` somewhere else entirely. So the manifest had been
describing a bundle that could not open a connection, and a green build said
nothing about it for as long as it never tried. `AHostHoldsALaneByInstallingABundleIT`
is what said it, on the first pool, which is what that test is for.

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

Whether the assemblies should stop putting the store's bundles on the
application classpath, or whether the container should be told those packages
are the system bundle's. It is a packaging decision with a blast radius, and
the three container tests are what would say whether an answer resolves.

Worth deciding beside it: `AHostOfTwoHalvesReachesOneContainerTest` passes
while this is true. Whatever is chosen, that test is not asserting what its
name claims, and a test that would have caught this is part of the answer
rather than an afterthought to it.

## What proving the rest looks like

A run of `:samples:spring-boot-worker-app:test` whose output contains the line
zero times — it still contains it, from the doors — with `AStreamLaneIsToldItHasWorkIT`,
which depends on the wake-up actually arriving, still passing, and the three
container tests still green. All three are needed: the warning can be silenced
by switching notification off, and that is the fix this item is not asking
for.
