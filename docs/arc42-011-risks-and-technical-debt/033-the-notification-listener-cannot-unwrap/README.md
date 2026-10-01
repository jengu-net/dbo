**Open, and down to one crossing. Two of the three are fixed and each was
smaller than it looked: a lane's pool named its driver by string, which is
resolved globally, and `dbo-stream` exported its package without importing it,
so its export was not substitutable and its own code kept a copy while
everything else wired to the application's. One class space now, proven. What
remains is the driver package itself — `org.postgresql` is NOT in any
assembly's shared set, so the application's copy and the container's driver
bundle's copy are two classes, and a door whose code is now the application's
asks the application's interface of a connection the tenant's pool made from
the bundle's. The warning is still there.**

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

## What it turned out to be, and the question was wrongly put

The question asked whether the assemblies should stop putting the store's
bundles on the application classpath, or whether the container should be told
those packages are the system bundle's. **It already is**, deliberately, and
that mechanism is the whole of how an application and a container hold one
class space: an assembly names the packages it shares, the system bundle
exports them, and a bundle whose export is SUBSTITUTABLE — exported and
imported both — has its own import wired to the system bundle, so the bundle's
code, every other bundle and the application outside the framework hold one
class.

**`dbo-stream` exported `cloud.jengu.dbo.stream` and did not import it.** Its
hand-written closed `Import-Package` suppressed the substitutable import bnd
would otherwise emit, so it opted out of the mechanism for its own code alone
— while `dbo-tenant`, which re-exports the same package substitutably, wired to
the application's copy. That is the two classes: a door the tenant runtime
constructs and a lane this bundle's activator constructs, measured as two
loaders in one JVM, with nothing failing at boot.

One clause fixes it. Measured after: the door and the lane report the same
class on the same loader.

**And it did not silence the warning**, which is the finding rather than a
disappointment. Merging the class space for `cloud.jengu.dbo.stream` moved the
door's code onto the application's classloader — so the door now asks for
`org.postgresql.PGConnection` as the APPLICATION resolves it, while the
connection it is handed comes from `dbo-tenant`'s pool, built with the
container's driver bundle. `org.postgresql` is in no assembly's shared set, so
those are two classes, and the unwrap still cannot succeed.

So the crossing is now in one place and has one shape: **the driver package is
not shared while the code that unwraps it is**. The candidates are to share
`org.postgresql` the way the store's own packages are shared, or to have the
door resolve its interface the same way its connection was made — the second
being what fixed the lane. Which is right is not obvious: the driver bundle
carries a large surface, and sharing a third party's package is a different
act from sharing one's own.

**And the refusal is at boot, where the set is computed.** `SharedPackages`
already refuses a shared set that is not closed over what its own API refers
to; it now also refuses a shared package whose exporting bundle does not import
it, naming the package and the bundle. Proven both ways — removing the clause
makes the container refuse to start rather than start wrongly.

Checked at boot rather than by a test walking every bundle, and the difference
matters: whether a package is shared is an **assembly's** decision, so a bundle
cannot know whether it is in that set. A test over every bundle would demand
substitutable exports from seven that have never been shared and have never
been a problem.

**One correction to this item's own note.** It said
`AHostOfTwoHalvesReachesOneContainerTest` was not asserting what its name
claims. That was unfair: its javadoc says plainly that no container is started
and what it asserts is the wiring of two assemblies' configuration. The
class-space claim lives in `EmbeddedContainerIT`, and what was missing was not
an honest test but the boot check above.

## What is proven, and what is not

**Proven:** the door and the lane report one class on one loader where they
reported two; the container refuses to start when the clause is removed; the
three container tests are green with it in place.

**Not proven, because it is not true yet:** the warning is gone. A run of
`:samples:spring-boot-server-app:test` still carries it, from the doors. That
is what the remaining crossing costs, and it is the measure this item closes
on — with the wake-up leg of `AParticipantHoldsItsLaneOnTheStreamIT` still
passing, so that notification was made to work rather than switched off.
