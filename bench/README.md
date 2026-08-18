# The bench

Performance measurement on the hardware dbo is meant to run on at the edge: a
Raspberry Pi 5 with NVMe, booted from a USB stick into Alpine, running plain
Postgres and a plain JVM. No container runtime, because the edge does not have
one and a container layer is one more thing between the measurement and the
truth.

The Pi is chosen for two reasons and they are both about honesty. It is
**constant** — the same silicon every run, unlike a laptop with a thermal
budget it shares with a browser — and it is **the actual target**, so a number
measured here is a number a deployment will see.

## Two profiles, and why both exist

| Profile | PGDATA | What it measures | Use it for |
|---|---|---|---|
| `ram` | tmpfs | The CPU and concurrency ceiling | Comparing commits. Lowest variance of anything available. |
| `nvme` | the NVMe | What a deployment actually feels | Quoting a number to somebody |

**The `ram` profile does not measure storage.** With PGDATA on tmpfs, `fsync`
returns immediately and the WAL never reaches media, so write throughput is
flattered — substantially. That is not a flaw as long as nobody quotes a `ram`
write number as a production figure. It is the right profile for "did this
commit make the engine slower", because the storage variance that would drown
that signal is gone.

`ram` also has a hard ceiling: Alpine in RAM, plus the JVM heap, plus ten
tenant databases in tmpfs. When tmpfs fills, Postgres does not degrade
gracefully — it stops. The runner sizes the dataset against available memory
and refuses to start a run it cannot finish.

## Thermal throttling is the reproducibility threat

A Pi under sustained load throttles within minutes without real cooling, and
it does not announce it: results drift *downward inside a single run*, so the
first repetition beats the last and the median means nothing.

Every sample therefore carries the SoC temperature and the `vcgencmd
get_throttled` flags, and **a run in which the throttle flag was ever set is
marked invalid**. A benchmark that skips this measures its own cooling.

## What is measured

Each figure exists because dbo makes a claim that needs a number behind it.

| Measurement | The claim it tests |
|---|---|
| Cold start to first `200` | The README says about five seconds on a server. The edge number is the one that matters. |
| Create and update, p50/p99 | The write path, per tenant and aggregate. |
| The same, with PDI on | The per-write cost of encrypting identifying elements — the first question anyone asks about it. |
| Token `identifier=` lookup | Weighted the way reality is: 88 of 206 measured production call sites are this one shape. |
| Feed lag across 10 tenants under write load | The differentiated one. Ten tenant databases on one instance is exactly what the commit-fence fast path exists for. |
| Subscription delivery latency | Durable delivery, end to end. |
| Archive and restore duration | Maintenance is only credible if its timings are known. |
| Tenants per JVM before degradation | A product limit an edge deployment has to be told. |

## Running one

The runner executes **on the Pi**, against the production REST surface over
loopback. Putting the network in the measurement loop would make this a test
of the LAN.

```bash
bench/provision-alpine.sh          # on the Pi, once per boot
bench/run.sh --profile ram --tenants 10 --duration 120
scp pi:/tmp/dbo-bench/result.json bench/results/
```

Results are committed. A benchmark whose history lives only in somebody's
terminal cannot show drift, and drift is the entire point.

## Reading a result

Every result carries the machine, not just the number: Pi model, RAM, kernel,
Postgres version, JDK build, dbo version, profile, and the thermal record. A
figure without those is unattributable — a Pi 4 and a Pi 5 differ by two to
three times, and comparing across them silently is how a benchmark starts
lying.
