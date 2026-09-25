**Open, and cheap to observe. The durable layer's notification listener asks a
pooled connection to unwrap to `org.postgresql.PGConnection`, does not get one,
and says so — 1,797 times in a five-minute run of one test. Nothing is lost:
the listener falls back to the poll every queue already performs, so work
arrives late rather than not at all. What it costs is latency nobody chose and
a log that no longer reads.**

# The notification listener cannot unwrap a pooled connection

Found while closing the item that carried a worker's lane on the substrate,
which is where the line was first counted. It was not that item's defect and it
outlives it: every deployment that runs the durable layer over a pool has it.

## What it says

```
WARN d.d.t.d.NotificationListenerSource : Notification listener error:
  Cannot unwrap to org.postgresql.PGConnection
```

once per attempt, on a dedicated listener thread, for as long as the process
runs.

## Why it happens

`LISTEN`/`NOTIFY` needs the driver's own connection, because reading a
notification is not a JDBC operation — it is `PGConnection.getNotifications()`.
The durable layer asks the connection it was handed to unwrap to that type, and
the connection it was handed comes from a pool whose wrapper does not delegate
`unwrap` to the driver's class. `dbo-stream` carries the driver **privately**,
in `lib/` beside DBOS's own, precisely so a container's shared driver bundle is
not in the path — and a private copy is a different `Class` object than the one
the pool's wrapper would match against, which is the shape this failure has.
That is a hypothesis with a cheap test, not a conclusion: the class loaders of
the two `PGConnection`s can be printed side by side in one run.

## What it costs

- **Latency, chosen by nobody.** A queue that would have been woken waits out
  its poll interval instead, default one second. Every wake-up the stream
  carries is affected, which is what makes it worth an item rather than a line.
- **A log that cannot be read.** One event per line is the rule; 1,797 copies
  of one line in five minutes is not a log, and it hides whatever else was
  said. It is also `WARN`, which is a level operators are asked to act on.

## What it does not cost

Correctness. The poll underneath is what the listener sits on top of, by
design, and the fallback is the ordinary path rather than a degraded one.

## What proving a fix looks like

A run of `:samples:spring-boot-worker-app:test` whose output contains the line
zero times, with the wake-up tests that depend on notification —
`AStreamLaneIsToldItHasWorkIT` — still passing. Both halves are needed: the
warning can be silenced by switching notification off, and that is the fix this
item is not asking for.
