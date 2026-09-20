**Open. The guide's insurer terminology step failed twice on one commit, in the job that also runs the suite, while the same step on the same image passed in the job that runs nothing else. Next: find out whether the projection path is slow or stopped.**

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

## What is not yet known

**Whether it is slow or stopped.** Ten minutes is long enough that "the
runner was busy" is a claim rather than an explanation, and nothing in the
suite reads the insurer after the wait expires — the two steps that follow
read the hospital. So there is no evidence either way about whether the copy
arrived a minute later or never.

**Whether the hospital's wait is near its limit too.** It passes, and how
close it came is not recorded.

## What to do

1. Make the failure say what it knows: on expiry, read the insurer once more
   and report what the projection had done — nothing, a partial, or a copy
   that landed after the wait. That alone separates slow from stopped.
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
