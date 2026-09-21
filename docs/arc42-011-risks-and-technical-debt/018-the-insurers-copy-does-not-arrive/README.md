**Open. It is SLOW, not stopped, and most of the wait is a tenant the runtime makes for itself reading a whole face through the chain. The fix for that is already on main and the pinned image predates it. Next: move the pin, then see whether the wait is still marginal.**

# The insurer's copy does not arrive, in one job only

The guide's terminology story waits for the zone's code system to reach two
tenants. The hospital takes it directly. The insurer takes it through a
projection, because the zone speaks R5 and the insurer R4.

The insurer's wait is 120 attempts five seconds apart, so **ten minutes**.
It expired:

```
TheGuideRunsIT > terminology, and the routes it arrives by >
  the insurer declared the code systems and not the value sets FAILED
    the zone's terminology never reached the insurer
```

## What makes it a defect rather than a flake

It repeated on the same commit, which is the chapter's own line between the
two. And the same run tells us where it does not happen:

| Job | World | Runs alongside | Result |
|---|---|---|---|
| `guide-as-tests` | the pinned image | nothing else | passes |
| `guide-on-tree` | a tree-built world | nothing else | passes |
| `build` | the pinned image | the whole suite | fails, twice |

The first and the third run the same command against the same image. What
differs is what else the runner is doing.

## Answered: slow, and what the time is

The failure now reads the insurer once more, waits a further minute, reads
again and says which word applies. Run against a healthy world with the wait
cut to a single try, it said:

```
the zone's terminology never reached the insurer.
  at the insurer, when the wait expired: 1 entries
  at the hospital, which takes it directly: 1 entries — so the zone published it
  at the insurer, a minute after that: 1 — SLOW: it arrived after the wait
  what the world said about the projection:
    tenant rl-on-r4 is reading its face through the chain: no image directory is configured
    tenant rl-on-r4 took its face from fhir-r4: image=false events=6679 in 28426ms
    tenant up: code=rl-on-r4 fhir=r4 pdi=false in 30400ms
    stream sync.rl.rl-on-r4.definitions carried events=21 in 488ms
```

Three facts fall out of those four lines.

**The carrier is a tenant, and it has to come up first.** `rl-on-r4` is the
projection the runtime makes when a tenant on r4 declares a zone written in
r5. Nothing reaches the insurer until it is serving.

**Coming up cost it thirty seconds, and twenty-eight of those were the
face** — six and a half thousand definition events, read through the chain
because no image directory was configured.

**The carrying itself took 488 milliseconds.** The sync that moved the zone's
definitions is not the slow part and never was.

So the ten minutes is mostly one tenant expanding a version, and the step
waits behind it. On a runner already running the whole suite, that is the
difference between marginal and over.

## Why the pin is the next move

Face images were switched on in the world's compose file, and the pinned
image the failing job runs cannot read the setting: the launcher only learned
to pass `DBO_FACE_IMAGES` after that image was cut. Measured on a tree-built
world, images take a seven-tenant bring-up from 521 seconds to 106, and a
face from twenty-eight seconds to four and a half.

That is the same twenty-eight seconds this step is waiting behind.

## What is not yet known

**Whether it is slow or stopped.** Ten minutes is long enough that "the
runner was busy" is a claim rather than an explanation, and nothing in the
suite reads the insurer after the wait expires — the two steps that follow
read the hospital. So there is no evidence either way about whether the copy
arrived a minute later or never.

**Whether the hospital's wait is near its limit too.** It passes, and how
close it came is not recorded.

## What to do

1. ~~Make the failure say what it knows.~~ Done, and it answered: slow.
2. If it is slow, the number to change is not this wait. The step's own
   comment says a first sync runs some minutes after a tenant comes up; the
   projection adds a second hop, and a ten-minute wait that is marginal on a
   loaded runner is a schedule worth reading rather than a constant worth
   raising.
3. If it is stopped, the subject is the projection path under load, and the
   test has been right twice.

## Why it matters now

It blocks merging on red, which is the only signal this repository has. A
documentation-only change sat behind it twice.
