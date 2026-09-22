**Open. The numbers are off CI now, and they say the insurer is not the
problem: through the projection it is met at attempt 2 of 120, in every guide
job, on every run read. The hospital's direct wait is the long one at 6 to 8,
because it runs first and absorbs the world starting. What is still missing is
the one job where this ever failed — `build` runs the guide as the third phase
of `./verify`, after the whole suite on the same runner, and the last two
builds died in phase one on the heap. Next: a green build.**

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

| Job | World | Runs after | Result |
|---|---|---|---|
| `guide-as-tests` | the pinned image | nothing else | passes |
| `guide-on-tree` | a tree-built world | nothing else | passes |
| `build` | a tree-built world | the whole suite, on the same runner | fails, twice |

The first and the third run the same suite. What differs is what the runner
has already done: `build` runs `./verify`, whose third phase is the guide
against this tree, so by the time the step waits the runner has just finished
every other test in the repository.

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

~~**Whether it is slow or stopped.**~~ Answered above: slow.

**Whether the hospital's wait is near its limit too**, and whether either
wait is still near it now the pin can read the images setting. It passes, and
until now how close it came was not recorded — a wait that passes says
nothing about whether it nearly did not, which is why this failed twice
before anybody could see it coming.

That is closed, and answered. Every one of the three terminology waits prints
the attempt it was met at, out of the hundred and twenty it is allowed, on the
way past — so a run that is at ninety says so before it is at a hundred and
twenty. Read off CI, the hospital's first wait is the long one at 6 to 8 and
neither of the two after it is near anything.

What is not known is the same number from `build`, and it is not known because
that job has not finished a phase one since the waits learned to speak.

## What to do

1. ~~Make the failure say what it knows.~~ Done, and it answered: slow.
2. ~~Move the pin to an image whose launcher passes the images setting
   through.~~ Done: the pin carries it, in both compose files.
3. ~~Make the wait say how close it came even when it passes.~~ Done. Three
   waits, each printing the attempt it was met at.
4. Read those numbers off a `build` run — the job where this expired, and the
   one that has the whole suite beside it.

   **They were not in any run**, and that took reading three of them to
   notice. A test's standard output goes nowhere by default and the guide's
   task forwards none of it, so three waits said how close they came into a
   stream no CI log carries: merged, running, and unreadable. Only those lines
   are forwarded now, because forwarding the stream would bury them among the
   guide's own published commands.

   Read locally, with images on and nothing else competing:

   ```
   the zone's code system at the hospital: met at attempt 13 of 120 (~60s)
   the zone's value set at the hospital:   met at attempt 1 of 120 (~0s)
   the zone's code system at the insurer,
                   through the projection: met at attempt 2 of 120 (~5s)
   ```

   The insurer — the wait that expired at a hundred and twenty attempts on a
   loaded runner — is met at the second. The hospital's first wait absorbs the
   world's start-up, which is why it is the long one and why the two after it
   are nearly free.

   **And they are now off CI too, from four guide jobs across two attempts of
   one pull request:**

   ```
   the hospital, directly:                6, 6, 8, 8 of 120  (~25–35s)
   the hospital's value set:              1, 1, 1, 1 of 120  (~0s)
   the insurer, through the projection:   2, 2, 2, 2 of 120  (~5s)
   ```

   Identical every time, and the insurer is met at the second attempt in all
   four. The pin did what the measurement said it would.

   **What is still missing is the job this ever failed in.** `guide-as-tests`
   and `guide-on-tree` run the guide and nothing else. `build` runs it as the
   third phase of `./verify`, after every other test in the repository on the
   same runner — which is the loaded runner this item is about, stated more
   precisely than "alongside". Both attempts of the last pull request died in
   phase one, on
   [item 023](../023-the-suite-runs-out-of-heap/README.md)'s heap exhaustion,
   so phase three never ran and the number that would close this was never
   taken.

   That coupling is worth naming: while the suite exhausts its heap, this item
   cannot be measured, because the only job that exercises the condition stops
   before reaching it.
5. The number to change is not this wait. A first sync runs some minutes
   after a tenant comes up, the projection adds a second hop, and a ten-minute
   wait that is marginal on a loaded runner is a schedule worth reading rather
   than a constant worth raising.

## Why it matters now

It blocks merging on red, which is the only signal this repository has. A
documentation-only change sat behind it twice.
