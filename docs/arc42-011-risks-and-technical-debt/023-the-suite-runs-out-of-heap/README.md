**Open. Seven tenants in four classes now, across three documentation-only changes
— one of them this item's own. What a tenant costs IS measured: 226 MB for a
face's first, 11 MB for the next on it. The floor is attributed now — twelve
classes keep 1755 MB and seventy more keep 477, against a 2 GB heap. Nothing is
being left unclosed: the floor is the FHIR definition corpus, loaded once per
face and resident by design — the model objects, their strings, and those
strings' arrays, which together are over half the live heap. Next: whether one
JVM holds three faces.**

# The suite runs out of heap

## What happened

A pull request carrying documentation, one working-rule line and a deleted item
failed the build:

```
TenantRuntimeIT > everyTenantAnswersTerminologyFromItsOwnStore FAILED
  bring-up FAILED for: [terms5]
  {terms5=java.lang.OutOfMemoryError: Java heap space}; serving=[terms4, teine]

ZoneIT > initializationError FAILED
  bring-up FAILED for: [haigla, kliinik]
  {kliinik=OutOfMemoryError, haigla=OutOfMemoryError}; serving=[ee]
```

Nothing in that change can reach a tenant's bring-up. What it met is the
machine.

## Two runs, same base, opposite answers

The measurement nobody had taken is what the filing itself produced. Two
documentation-only pull requests ran within an hour of each other against the
same `main`:

| what it carried | `build` | outcome |
|---|---|---|
| two item findings and a deleted item | 44m 03s | passed |
| **this item** | 37m 28s | `at-once-3` died of heap exhaustion |

Same base, same kind of change, minutes apart, opposite results. That is the
non-deterministic failure this document describes, observed under conditions
somebody controlled rather than inferred from a red build somebody remembers.

**The change that files this could not be merged because of what it files.**
That is the strongest sentence available about how much the ceiling costs, and
it is put here rather than left as an anecdote, because the obvious response —
re-run until it passes — erases exactly the signal worth keeping. It was
re-run once, deliberately, and only after this was written down.

**Four tenants, three classes, two runs.** `terms5` in
`TenantRuntimeIT`; `haigla` and `kliinik` in `ZoneIT`; `at-once-3` in
`SeveralTenantsDeclaredAtOnceComeUpTogetherIT`. None of the three classes was
touched by either change.

**And a third change, carrying this item's own instrument, died the same way.**
The change that attributed the floor — documentation, a test listener and an
editor's preview config, nothing that can reach a tenant — failed at 25m53s:

```
TenantRuntimeIT > everyTenantAnswersTerminologyFromItsOwnStore FAILED
  bring-up FAILED for: [terms5]
  {terms5=java.lang.OutOfMemoryError: Java heap space}; serving=[teine, terms4]
```

**It is the same tenant, in the same test, with the same two neighbours
already serving.** Twice now, and that is not what a heap running out at random
looks like. `terms5` is a terminology tenant on the R5 face, and R5's first
tenant is the 226 MB this item measured — the definition corpus, not the
tenant. Whichever run leaves that corpus unloaded until `TenantRuntimeIT` asks
for it is the run where 226 MB has to be found at a floor of about 1.6 GB, and
`terms4` and `teine` are already up because they are on faces somebody paid for
earlier. So the coin flip has a shape: it is whether this tenant is the one
that pays for a face.

**A fourth run refuted the general form of that.** The next build died in
`SeveralTenantsDeclaredAtOnceComeUpTogetherIT`, with `at-once-2` out of heap
where three of its four siblings came up. All four are declared `"face":"r4"`,
and r4's definitions are long resident by the time that class runs — so nothing
there was paying for a face. The 11 MB a later tenant on a loaded face costs is
not what ran out.

So the shape holds for the pair of `terms5` failures and does not generalise.
What generalises is duller and worse: the floor is high enough that any
additional demand can tip it, and which demand happens to be the one that tips
it is not predictable from the demand. Four runs, three distinct classes, two
distinct causes of the last megabyte.

**Checked rather than argued, as far as it can be without another red run.**
`terms5` is declared `"face":"r5"` in the test itself and `terms4` is `r4`,
which is why one of the pair dies and the other does not: r4's definitions are
long resident by the time this class runs, and r5's need not be. And in the run
that produced the attribution above — a run that passed — `TenantRuntimeIT` is
not among the twelve classes keeping 40 MB or more. It paid nothing that time,
which is what a class that found the corpus already loaded looks like.

## Why it is filed now rather than earlier

**It was already known and had no item.** The risks list records it: a class
that brings four tenants up at once "has died as Java heap exhaustion inside
one of them, on a change that touched documentation only… It has no item
because it has not been reproduced deliberately."

**It has stopped being one class's problem.** Two more classes have now failed
the same way in one run, and the second is `ZoneIT` — which was fixed today for
a different reason and is not a class that brings up a crowd.

**And it is now legible, which it was not this morning.** The failure names the
tenant and the reason. Before the wait was changed to say what it knows, the
same exhaustion arrived as "declared but not serving after 20 passes" with an
empty trouble ledger — a tenant that simply never appeared, with nothing to
search for.

## What is known about the dials

`gradle.properties` carries the two, with their reasoning:

- `dboTestHeap=2g` — a floor rather than a default. Clearing it does not give
  "the default": each module keeps its own minimum, because Gradle's own heap
  dies inside HAPI as a null-message fault that names nothing.
- `dboTestParallelism=1` — one fork. The suite is already refusing to run test
  classes in parallel, which is how much room there is.

CI passes no test flags: it runs `./verify`, which reads that file, so a
laptop and the workflow cannot disagree about what the check is.

## What a tenant costs, which turns out to be recorded

The first draft of this item said nobody had measured one. It is measured, it
is recorded, and it is under a ratchet that fails on a rise of more than thirty
per cent — `config/memory-baseline.txt`, generated by
`WhatTheLoadedSpecificationCostsIT`, as heap in use after a forced collection:

| | |
|---|---|
| one served tenant, before any write | **226 MB** |
| the first validated write | 0 MB |
| the rest of the validator pool | 36 MB |
| a second tenant **on the same face** | **11 MB** |

So the price is a FACE and not a tenant. The first tenant on a face costs two
hundred and twenty-six megabytes; the next one on that face costs eleven. A
suite that serves r4, r5 and r6 pays the large number three times however few
tenants it has, and pays almost nothing for the crowd.

**And the floor is in that test's own comment.** Measuring the deltas, it
records what was resident when it started: "measured alone that read 23 MB and
inside the suite **1.3 GB**, which is a fact about the suite and not about a
tenant."

Against a two-gigabyte heap that leaves about seven hundred megabytes, and
three faces at 226 MB is six hundred and seventy-eight. That is the ceiling,
in numbers this repository already held: not a crowd of tenants, but a suite
whose floor has risen until the faces no longer fit above it.

It also says why the failures look the way they do. The class that fails most
brings four tenants up **at once**, and the ones that failed tonight were
bringing up tenants on faces nothing else in the run was holding.

## What the floor is made of

Measured, by recording heap in use after a forced collection at the end of
every class. Two hundred and two classes, a floor that climbs 244 → 577 →
1172 → 1536 → **1610 MB**, and a suite that passed with 438 MB to spare — which
is why this is a coin flip rather than a certainty. The margin is about two
faces wide, and a face's first tenant is 226.

| | classes | kept |
|---|---|---|
| kept 40 MB or more | **12** | **1755 MB** |
| kept 1–39 MB | 70 | 477 MB |
| kept nothing, or gave some back | 120 | — |

**It is both.** A first reading of the top of the list says a short list of
offenders; the tail says otherwise. Seventy classes keeping an average of seven
megabytes each are a quarter of the floor, and no single one of them would ever
be noticed. Attacking only the twelve leaves about a third of the problem.

The twelve, largest first:

```
 +423  ATenantDeliversWhatItSubscribedToIT
 +227  R6TenantIT
 +215  MilestonesOnTheCheckpointIT
 +179  TenantOsgiIT
 +153  TheTwoAnswersAreComparedOverTheVersionIT
 +124  DelegationIT
 +110  ArchiveProvenanceIT
  +90  ADefinitionIsExpandedWhenItArrivesIT
  +83  EmbeddedContainerIT
  +60  ACommaMeansOrInASearchIT
  +46  AFaceIsCutOnceAndBroughtUpFromIT
  +45  TheFaceSqlShipsWithTheReleaseIT
```

**Not all of them are a defect.** `R6TenantIT` at 227 is a third face's first
tenant, which is the 226 the baseline records: the cost working as measured.
`EmbeddedContainerIT` and `TenantOsgiIT` hold a framework each, which is what
they are for.

**The top and third look like something not being closed.** A subscription
dispatcher and a checkpointing run are threads, pools and engines rather than
faces, and several hundred megabytes surviving the class that made them is not
explained by anything in the baseline.

### And what it is made of

`-Ddbo.heap.histogram=true` asks the JVM for its own `GC.class_histogram` at the
end of a run and writes `build/heap-histogram.txt`. Narrowed to the two classes
the list accused, 904 MB were live at the end, and the top of it is not a
thread, a pool or a connection:

```
  num     #instances         #bytes  class name
    1:       3660386      449485360  [B
    2:       3629356       87104544  java.lang.String
    3:       1126342       63075152  org.hl7.fhir.r5.model.StringType
    8:         92807       17818944  org.hl7.fhir.r5.model.ElementDefinition
```

Ninety-two thousand `ElementDefinition`s and 1.1 million `StringType`s are a
StructureDefinition corpus. Counted by name, `org.hl7.fhir.*` types hold 233 MB;
the byte arrays and strings above them are what those objects are made of, so
the real share is larger than 233 and the histogram cannot say by how much. It
reports what a type's own instances weigh, never what they keep alive.

**So the hypothesis was wrong, and it was worth being wrong out loud.** Neither
class is holding a dispatcher or an engine it failed to close.
`MilestonesOnTheCheckpointIT` ends by validating a Bundle on r4, r5 and r6 in
one loop, through `R4FhirVersion.INSTANCE` and `R5FhirVersion.INSTANCE` —
singletons, so what they load is resident for the life of the JVM by design.
Its 206 MB reproduce when it is the only other class in the run, so this is not
an artefact of what ran before it.

**And it agrees with what a tenant costs.** 226 MB for a face's first tenant and
11 MB for the next on it was always the shape of a cost paid once per face and
shared by every tenant on it. The 226 is the definitions; the 11 is the tenant.
Three faces resident in one JVM is most of a gigabyte before a single tenant is
served, which is the floor this item has been walking around.

### Whose the byte arrays are

The largest single entry is the byte arrays, and the item expected that to need
a heap dump read for retained size. It did not. Two histograms, one of the pair
of classes and one of the subscription class alone, answer it by counting:

| | two classes | subscription alone |
|---|---|---|
| live | 904 MB | 702 MB |
| `[B` | 428.7 MB, 3,660,386 | 300.5 MB, 3,085,823 |
| `java.lang.String` | 83.1 MB, 3,629,356 | 70.0 MB, 3,058,830 |
| byte arrays per String | **1.009** | **1.009** |

**One each, to within a percent, in both.** A String on this JDK holds its
characters in a byte array, so the biggest line in the histogram is not a
second owner competing with the definitions — it is what the Strings are made
of. Together they are 57% and 53% of the live heap.

And the Strings are the corpus. The FHIR primitive wrappers each hold exactly
one: 2.09 million of them in the narrower run against 3.06 million Strings, so
**69% of every String alive is a `StringType`, `CodeType`, `UriType`, `IdType`
or `MarkdownType` in a definition**, before counting the strings inside
`ElementDefinition` and its components.

So the floor has one owner and three shapes: the model objects, their strings,
and those strings' arrays. Shallow sizes could answer it after all — not by
adding the columns up, which is what shallow sizes cannot do, but by noticing
that the counts line up one to one.

**And one is a caution about item 003.** `DelegationIT` keeps 124 MB, and it is
the first class moved onto the shared cast. The shared world's tenants are
never dropped by design, so a class moving down the ladder transfers its
retention to the shared runtime rather than removing it. Moving classes saves
bring-up time; whether it saves memory is a separate question and nobody had
asked it.

## What is still not known

**Whether the ceiling moved today or the load did.** Two changes this session
are candidates and neither has been measured against it. The wait now holds for
four minutes rather than giving up after twenty passes, so tenants that would
have been abandoned early now stay alive and overlap with more neighbours. And
the cast added a member to the shared world.

**Whether raising the heap is a fix or a postponement.** Two gigabytes is a
floor somebody chose. Four would pass tomorrow and say nothing about why a
suite of fifty-odd classes needs it.

## What to do

1. ~~Measure one tenant, resident, after bring-up.~~ Done: 226 MB for a face's
   first, 11 MB for the next on it.
2. ~~Attribute the floor.~~ Done, and the instrument is kept:
   `WhatTheSuiteLeavesBehind` records the floor after each class when asked
   with `-Ddbo.heap.attribute=true`. It writes after every class rather than at
   the end, because the suite it measures is the one that dies.
3. ~~Read the top of the list.~~ Done, and the answer was no. It is not
   something left unclosed; it is the definition corpus, once per face, held on
   purpose.
4. ~~Say whose the 449 MB of byte arrays are.~~ Done, and without the heap
   dump: there is one byte array per String to within a percent, so they are
   the Strings' own storage, and 69% of the Strings are FHIR primitives in a
   definition. Counting beat measuring retained size.
5. Decide how many faces one JVM holds. This is now the question the floor
   actually poses, and it is a suite-shape decision rather than a defect:
   splitting the run by face buys back a face's definitions, at the cost of a
   second JVM's startup.
6. Say whether the four-minute wait raised the peak. It is one change and it is
   reversible, and an honest answer is worth more than the wait.
7. Then the tail — seventy classes at seven megabytes each — which is the other
   quarter and is nobody's fault in particular.
8. Then decide about the dial, with all of that in hand. Raising it before that
   is buying quiet.

## Why it matters beyond a red build

Three items are waiting on the same constraint from different directions.
[Item 019](../019-the-build-repeats-itself/README.md) found that the container
suites are the forty minutes and no cache reaches them.
[Item 003](../003-tests-move-down-the-ladder/README.md) moves classes onto a
shared world so fewer tenants exist at once, and
[item 002](../002-sample-application/README.md) is the application those stories
would run against. Each of them is, underneath, a way of holding fewer tenants
alive — and none of them can be judged without knowing what one costs.

The temporary rule about not proving a bring-up on the shared runtime is the
same constraint again, written as a working rule because it was cheaper to
obey it than to measure it. This item is the measurement that rule is standing
in for.
