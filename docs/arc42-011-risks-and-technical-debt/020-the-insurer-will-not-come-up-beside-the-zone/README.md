**Open. Asking the harness's shared runtime for the sample world's insurer fails at bring-up: the engine's own audit vocabulary is claimed twice. The same spec comes up fine in the guide's container. Next: find which of the two writes it.**

# The insurer will not come up beside the zone

The sample world's tenants are one definition now, brought up by the guide's
container and by the harness in its own JVM. Six of the seven come up either
way. The insurer does not come up in the harness:

```
declared but not serving after 20 passes: [gringotts]
  gringotts=IdentityConflictException: identifier urn:dbo:canonical|urn:dbo:audit
    already claimed by 01a0c27e-… (claim by 01a0c285-…)
serving=[rl, fhir-r4, fhir-r5, hogwarts, rl-on-r4]
```

## What is known

**The canonical is the engine's own.** `urn:dbo:audit` is part of the
vocabulary every tenant is given at bring-up, which is what makes a
`urn:dbo:` code resolvable in the tenant that served it.

**A projection appears beside it.** The serving list carries `rl-on-r4`,
which the runtime makes when a tenant on r4 declares a zone written in r5.
The insurer is that tenant. So the failing bring-up is the one that causes
the projection, and the two claims are a hundred and seventy microseconds
apart in id order.

**The same spec is fine in a container.** The guide's world brings up all
seven, with the same files, and has done for months.

## What is not known

Which side writes the second claim: the tenant's own vocabulary seeding, or
the projection seeding the same publication into the same store. Nor whether
the difference from the container is the number of tenants, the ordering of
one scan, or face images being on here and, until today, off there.

## Why it matters beyond a test

`EngineVocabularyDoesNotCollideWithItselfIT` exists because this vocabulary
has collided with itself before, across a stream: the second arrival was read
as a local override, parked, and re-attempted every round. That was fixed and
is proven. This is the same vocabulary colliding at a different seam, with a
different outcome — a tenant that does not serve at all — and nothing yet
says whether a deployment can reach it.

## What to do

1. Reproduce it small: one runtime, the zone, the r4 root, the insurer. If it
   holds, the count of tenants is not the cause.
2. Turn face images off for that run. If it holds, images are not the cause.
3. Find the two writers of the claim and decide which one should not be
   writing, which is the same question `EngineVocabularyDoesNotCollide`
   answered for the stream.

## Where it bites today

`ScimProvisioningIT` wanted the insurer for its half about a tenant that
declares no directory, and takes a shared shape instead. Nothing else asks
for the insurer in the harness yet, so the cast is one member short of usable
until this is answered.
