# Comparative load test: dbo vs fhirest vs HAPI FHIR

Ingest a real patient population into three FHIR servers on the same
Raspberry Pi 5, under the same load shapes, and say honestly which does what.

This plan extends `bench/` rather than starting beside it. The runner already
there — production serving path, thermal invalidation, failures-invalidate —
carries the discipline this needs; what it does not yet have is a second and
third target, an ingest workload, and machine-resource sampling.

## 1. What the source data actually is

Measured against the source population on the cluster's database node, not estimated:

| | |
|---|---|
| Patients | 100,218 |
| Resources per patient | avg **2,267**, p50 **1,499**, p95 **5,669**, max **117,444** |
| Stored form | `patient.bundle_gz` — gzipped R4 transaction Bundle in `bytea` |
| Compressed bundle | avg **359 kB** → roughly 3.6 MB of JSON at the ~10:1 this data compresses |

Distribution by resource count:

| Band | Patients | avg gz |
|---|---|---|
| 29–999 | 32,858 | 65 kB |
| 1,000–1,999 | 31,135 | 216 kB |
| 2,000–2,999 | 19,034 | 401 kB |
| 3,000–3,999 | 8,071 | 577 kB |
| 4,000–4,999 | 3,093 | 748 kB |
| 5,000–5,999 | 1,393 | 927 kB |
| 6,000–117,444 | 4,634 | 2,421 kB |

## 2. Why 500 bundles is the wrong first row

Two things, and the second is the more serious.

**It is not 500 records, it is 1.1 million.** 500 × 2,267 ≈ **1.13 M
resources**, roughly 1.8 GB of JSON. Record-by-record at one thread, against a
server on a Pi doing perhaps 50 writes a second, is about six hours — for one
cell of eighteen. The matrix would take weeks and thermal drift would swamp
every number in it.

**A bundle is not a unit.** Bundle size spans 29 to 117,444 resources — a
factor of four thousand. Two runs of "500 bundles" differ in real work by more
than the differences the benchmark is trying to detect, and comparing 1 thread
against 50 threads means comparing different draws. Any number computed per
bundle is uninterpretable.

**So the unit is the resource, and the cohort is banded.** Fix the *work*, not
the patient count:

- Draw whole bundles from a **tight band, 900–1,100 resources** (both source
  buckets hold tens of thousands, so the draw is free). A batch is then a
  roughly constant unit and thread counts compare.
- **First row: 50 patients ≈ 50,000 resources.** Enough to pass JIT warm-up
  and fill a page cache, small enough that the slowest cell is tens of minutes
  rather than hours.
- **Second row, once the harness is proven: 200 patients ≈ 200,000
  resources**, at reduced repetitions.
- The >6,000 tail is **excluded from the parallel matrix and run separately**
  as its own question: does a single transaction of 49,144 entries (Dumbledore)
  complete at all, on each server, and at what memory cost? That is a real
  finding and it is not a throughput finding.

The cohort is drawn once, by seed, and **committed as a manifest of patient
ids** — a benchmark whose input is re-randomised per run cannot show drift.

## 3. Why R5, and what it forces

R5 is not a preference here, it is a constraint: **fhirest is R5-pinned**
(`forR5` hardwired, one version per JVM). R5 is the only version all three can
serve, so it is the only version a three-way comparison can use.

The data is R4. That conversion is real work and it has one rule:

> **Convert offline, once, before any run.** Never in the measurement loop.

A converter in the driver measures the converter. The cohort is converted with
`org.hl7.fhir.convertors` (VersionConvertor_40_50), materialised as files,
checksummed, and every server is fed **byte-identical input**.

Then the cohort must earn its place: **all three servers must accept 100 % of
it** in a pre-flight pass. A bundle any one server rejects is removed from the
cohort for **all** servers — otherwise the three are measured on different
data and the comparison is void. The count removed, and why, gets recorded;
a silently shrinking cohort is how a comparison starts flattering somebody.

## 4. The three systems, and where they are honestly not comparable

All three as **plain JVMs, no container runtime** — consistent with the
existing bench, where a container layer is one more thing between the
measurement and the truth. HAPI's `hapi-fhir-jpaserver-starter` and fhirest
are both bootable Spring Boot jars, so this costs nothing but configuration.

Each gets a **dedicated Postgres** on the same NVMe, same version, same
config, dropped and recreated between cells. Same JDK, same heap, same GC
flags, all recorded in the result.

Configuration that must be stated and matched, because it is where a
comparative benchmark lies:

| Dial | Setting | Why it matters |
|---|---|---|
| Write validation | **off** on all three | HAPI defaults differ from the others; validation cost is a separate measurement, not a hidden one |
| Transaction semantics | Bundle `type=transaction` in batch mode; individual POST in record mode — identical on all three | |
| Search indexing | left at each server's default, and **recorded** | see below |
| Connection pool | same size | otherwise this measures pool tuning |

**The caveat that must ride with every throughput number:** the three servers
do *different amounts of work per write*. HAPI populates a full search-index
schema; fhirest indexes on write; dbo extracts a tier-1 envelope. A
resources-per-second figure that does not say what got indexed is not a
comparison, it is a advertisement. Every result therefore reports throughput
**and** what each server indexed **and** bytes on disk per resource — the last
being the number that makes the trade visible rather than arguable.

## 5. The two machines

Both settled and inspected, not assumed.

| | Bench Pi | Driver |
|---|---|---|
| Address | `192.168.1.128` | `mini`, `192.168.1.12` |
| Machine | Raspberry Pi 5 Model B rev 1.0, 8 GB | macOS 26.4 arm64, 8 cores, 8 GB |
| OS | Alpine 3.22.5, **booted from NVMe** | |
| Storage | `/dev/nvme0n1p7` on `/data`, 190 GB free | |
| Installed | JDK 21.0.11, PostgreSQL **17.10** | **no JDK — phase 0 installs one** |
| Idle SoC | **49.9 °C**, `vcgencmd` present | |
| Link | 0.7 ms RTT, 0 % loss over three pings | |

Two things to reconcile in phase 0. `bench/provision-alpine.sh` installs
PostgreSQL **16** and assumes **diskless Alpine that forgets everything each
boot** — this Pi boots from NVMe and already carries 17.10. The script either
does not apply to this host or needs to say which host it does apply to;
running it as-is would install a second Postgres beside the one in use.

## 6. Where the driver runs — a deliberate departure

The existing bench runs the driver **on the Pi over loopback**, because a
driver on another machine measures the LAN. That rule is right for what it was
written for and **wrong for this test**: at 50 threads the driver is itself a
substantial load, and a Pi 5 has four cores it would be taking from the server
under test.

So: **the driver runs off-box on `mini`**, and the plan pays the cost of that
honestly by measuring the floor — a null-endpoint baseline run
that proves the 1 GbE link and the driver are not the limit at the offered
rate. If the floor is close to the measured rate, the run is invalid for the
same reason a throttled run is.

## 7. What is measured

Per cell, sampled at 2 s into a time series (the existing `Thermal` keeps only
a max and the latched flags — it grows a series):

- **SoC temperature** and `vcgencmd get_throttled` — live and latched bits
- **CPU** from `/proc/stat` deltas, per-core
- **Memory** from `/proc/meminfo`, plus RSS of the JVM and of Postgres
  *separately* — "the server used 2 GB" means nothing when Postgres is half of it
- **Disk**: database size before and after (→ **bytes on disk per resource**),
  and `/proc/diskstats` for the IO the write path actually caused

Per cell, computed:

- ingest throughput, resources/s and bundles/s
- per-request latency p50 / p95 / p99
- peak RSS, JVM and Postgres
- bytes on disk per resource
- failures — any at all

## 8. What invalidates a run

The existing three rules stand, and one is added.

- **It throttled or browned out.** The numbers describe cooling.
- **Any request failed.** A latency computed over the requests that happened
  to work errs in the flattering direction, because the ones that break tend
  to be the slow ones.
- **The machine could not be watched.** No `vcgencmd`, no quotable figure.
- **The driver or the link was near its floor.** New, and it follows from
  moving the driver off-box.
- **It did not start from the agreed temperature.** The gate timed out, or the
  recorded start temperature fell outside the band. A cell that began hot is
  not comparable to one that began cold, and the recorded start temperature is
  what makes that checkable rather than assumed.

Invalid runs are written out in full. Evidence you delete is evidence you were
going to need.

## 9. The matrix, and what it costs in wall clock

3 systems × 2 modes × 3 concurrencies = **18 cells**, each moving the same
~50,000 resources.

| | |
|---|---|
| Systems | dbo, fhirest, HAPI |
| Modes | **batch** — whole bundle as one transaction; **record** — each entry as its own request, on one thread per bundle |
| Concurrency | 1, 10, 50 threads |
| Repetitions | 3 |

**Ordering matters more than it looks.** Running all of dbo, then all of
fhirest, then all of HAPI maps thermal drift straight onto system identity —
the last system measured is the slowest, and the benchmark has discovered its
own heatsink. Cells are therefore **interleaved round-robin across systems**.

### The between-cell protocol

Four steps, and the order of the last two is the part that is easy to get
backwards.

1. **Run the cell.** Measure.
2. **Tear the database down and recreate it**, empty, for the next server.
   Every cell starts from an empty store — that is what makes bytes-on-disk
   per resource meaningful, and it stops one server inheriting another's page
   cache and file layout.
3. **Then** wait on temperature. Dropping a database holding 50,000 resources
   is itself work, and it heats the Pi. Gating before the teardown would let
   each cell start from whatever the teardown left behind, which is exactly
   the drift the gate exists to remove.
4. **Start the next cell from the same SoC temperature as every other cell** —
   a band, not a ceiling. Every cell beginning at, say, 50–52 °C is a
   controlled start; "below 60 °C" is not, because one cell starts at 51 and
   the next at 59 and the difference lands in the numbers.

**The gate has to be reachable, and it has to be able to give up.** This Pi
idles at **49.9 °C**, so a band below roughly 50 °C can never be met and the
run would wait for ever. The band is therefore calibrated from a measured idle
floor on the day, and the wait carries a timeout: on expiry the cell is
**marked invalid and the matrix continues**, rather than hanging or quietly
proceeding from the wrong temperature. Every result records its **start
temperature** as well as its peak, so the gate can be audited after the fact
instead of trusted.

Rough budget: the 1-thread record-mode cells dominate at roughly 15–20 min
each; with 3 repetitions and cooldowns the first row is about **a day of
wall clock**. The 200 k row is four times the work and runs at one repetition.

## 10. Sequence

| Phase | Deliverable | Gate |
|---|---|---|
| 0 | JDK on `mini`; `provision-alpine.sh` reconciled with an NVMe-booted Pi already carrying PG 17; three servers up as plain JVMs, each answering `/metadata` in R5 | all three serve R5 |
| 1 | Cohort built: banded draw, offline R4→R5 conversion, checksummed, manifest committed | all three accept 100 % |
| 2 | Comparative driver — target abstraction over the three, batch and record modes, N threads | runs green off-box |
| 3 | Machine sampling — CPU / memory / disk / thermal time series | a null run produces a full record |
| 4 | Floor measurement, then the 18-cell matrix, interleaved, 3 reps | no invalid runs |
| 5 | Results committed to `bench/results/`, written up | |

Phases 0 and 1 are the ones that can surprise. Standing up fhirest and HAPI on
Alpine on ARM, and getting a clean R4→R5 conversion that all three accept, are
each capable of eating more time than the measurement.

## 11. Still open

**Is the >6,000-resource tail in scope for this round?** It is a different
question from throughput — whether a single transaction of 49,144 entries
(Dumbledore's record) completes at all on each server, and what it costs in
memory — and it can be deferred to a second round without weakening the first.

The rest is settled: bench Pi `192.168.1.128`, driver `mini`, empty database
per cell, and every cell starting from the same temperature.
