---
title: How performance is measured
eyebrow: Technical
standfirst: >-
  Not a benchmark page. A description of the method, so that a number taken
  here can be argued with on how it was taken rather than on whether you
  believe it.
template: essay.html
---

Most performance claims about a server are unfalsifiable, because the thing
they were measured on is described as "a cloud instance" and the run that
produced them cannot be repeated. The method below exists so that this
store's numbers are the other kind.

## Measured on the hardware it is meant to run on

A Raspberry Pi 5 with NVMe, booted from a USB stick into Alpine, running plain
PostgreSQL and a plain JVM. No container runtime, because the edge does not
have one and a container layer is one more thing between the measurement and
the truth.

The Pi is chosen for two reasons, and both are about honesty. It is
**constant** — the same silicon every run, unlike a laptop with a thermal
budget it shares with a browser. And it is **the actual target**, so a number
measured there is a number a deployment will see.

## Two profiles, and only one of them may be quoted

| Profile | Where the database lives | What it measures |
|---|---|---|
| `ram` | tmpfs | The CPU and concurrency ceiling |
| `nvme` | the NVMe | What a deployment actually feels |

**The `ram` profile does not measure storage.** With the database on tmpfs,
`fsync` returns immediately and the write-ahead log never reaches media, so
write throughput is flattered — substantially.

That is not a flaw as long as nobody quotes a `ram` write number as a
production figure. It is the right profile for "did this commit make the engine
slower", because the storage variance that would drown that signal is gone. It
is the wrong profile for telling somebody what to expect.

<div class="takeaway" markdown>
Quote `nvme`. Compare commits with `ram`. A page that quoted the faster number
because it was the one available would be doing the thing this whole
arrangement exists to prevent.
</div>

## Thermal throttling is the reproducibility threat

A small machine that heats up returns a different number for the same work, and
nothing in the result says so.

So the fan is driven by lowering the governor's trip points rather than by
writing a fan speed — writing a speed moves it for one governor poll and then
the governor takes it back. With the trip points lowered the governor itself
wants maximum, so nothing has to fight it: 8,600 rpm, and the chip drops from
68.9 °C to 48.5 °C in sixty seconds, where the default governor stalls at 52 °C.

That is what makes a start-temperature *band* reachable at all. This machine
idles near 50 °C, so a gate below that would have waited for ever.

**A run is invalid unless it earns being valid.** Every cell starts from the
same temperature band, and a run that cannot demonstrate it did is discarded
rather than footnoted.

## Comparison is against real servers, on the same machine

The comparison harness runs this store against
[fhirest](https://github.com/fhirest/fhirest) and
[HAPI FHIR JPA](https://github.com/hapifhir/hapi-fhir-jpaserver-starter),
ingesting the same real patient population on the same Pi, one server up at a
time and the others proved gone.

One finding is worth repeating because it is the sort of thing a comparison
usually gets wrong: **every server validates on write, whatever its flags
say.** That was established by probing each one with resources wrong in exactly
one way rather than by reading configuration pages — because a comparison where
one server is validating and another is not is not a comparison of the same
work.

## What this page does not have

Numbers. The harness and its method are in the repository; a published results
set is not, and quoting figures here that nobody can reproduce from what is
public would be the same failure the method was built to avoid.

<div class="further" markdown>
The bench, its profiles and what makes a run valid are in
[`bench/README.md`](https://github.com/jengu-net/dbo/blob/main/bench/README.md);
the comparison harness is in
[`bench/comparison/README.md`](https://github.com/jengu-net/dbo/blob/main/bench/comparison/README.md).
The usage inventory the search tiers are sized against is
[evidence](../docs/evidence/search-usage-inventory.md).
</div>
