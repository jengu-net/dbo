# The comparison harness

dbo against [fhirest](https://github.com/fhirest/fhirest) and
[HAPI FHIR JPA](https://github.com/hapifhir/hapi-fhir-jpaserver-starter),
ingesting the same real patient population on the same Raspberry Pi 5.

The design and its reasoning are in
[`docs/plans/load-comparison.md`](../../docs/plans/load-comparison.md). This
file is what the pieces are and how to run them.

## What is where

| | |
|---|---|
| `cohort-builder/` | Draws the banded cohort from `rowling`, converts R4→R5 once, offline, writes files + a manifest. `ProviderExport` does the same for the directory. |
| `prune-dangling.py` | Removes references to entries a bundle does not contain — see below. |
| `driver/Driver.java` | The load driver. Runs off the Pi. Two modes, N concurrent writers. |
| `pi/server.sh` | Brings up exactly one server and proves the others are gone. |
| `pi/sample.sh` | Machine sampling: temperature, CPU, memory split JVM/Postgres, disk. |
| `pi/cool.sh` | Returns the SoC to the same starting temperature every cell. |
| `validation-probe.sh` | Asks each server what it actually checks on write. |
| `matrix.sh` | The whole matrix, repetition-major. |

## Three things that were measured rather than assumed

**The fan is the only lever that holds.** Writing `cur_state` moves the fan for
one governor poll and then `step_wise` takes it back; `user_space` is not
compiled into this kernel. **Lowering the trip points** makes the governor
itself want maximum, so nothing has to fight it — 8,600 rpm, and the SoC drops
from 68.9 °C to 48.5 °C in 60 s where the default governor stalls at 52 °C.
That is what makes a start-temperature *band* reachable at all: this Pi idles
near 50 °C, so a gate below that would wait for ever.

**Every server validates on write, whatever the flags say.** Probed, not read
off a config page — each probe is a resource wrong in exactly one way:

| Probe | dbo | HAPI | fhirest |
|---|---|---|---|
| invalid code value | refused 422 | refused 400 | refused 400 |
| malformed date | refused 422 | refused 400 | refused 400 |
| wrong JSON type | refused 422 | refused 400 | refused 400 |
| unknown element | accepted | accepted | **refused 400** |
| dangling reference | accepted | accepted | accepted |

Parsing *is* validation for all three, so "validation off" never meant no
checking. The one asymmetry is fhirest's strict parsing of unknown elements,
which is a little more work per write than the other two do.

**The cohort had a defect, and one server found it.** Every rowling bundle
carries a `Provenance` listing targets its own theming stripped — 8,950
dangling references across 50 bundles. dbo refuses such a transaction; HAPI
and fhirest accept it. Neither behaviour is wrong, but feeding one server a
refusal and the others a write measures referential strictness rather than the
write path, so `prune-dangling.py` corrects the cohort once, for all three,
and records what came out in `PRUNED.tsv`.

Synthea also references organisations *conditionally*
(`Organization?identifier=…`), which are resolved against what is already
stored. So the provider directory is loaded before the patients — setup, not
measurement, and the same bytes into all three.

## Running it

```bash
bench/deploy-dbo.sh                     # this working tree onto the Pi
RESULTS=~/bench-results REPS=1 bench/comparison/matrix.sh
```
