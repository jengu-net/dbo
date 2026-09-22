**Open. Nothing is built. A version's definitions are parsed into a HAPI object
graph and held while a tenant serves. The criterion is that a new face must not
cost another hundred megabytes; measured, **a second face costs 444**, on top of
225 for the first. The aim is a serving process holding neither the carried
graph nor the records-backed one. Three of the four
pieces are already there: the definitions are a schema the face's SQL reads,
the database answers tier one and the envelope at parity, and a face is cut
once per release into an image of everything derived from them. Next: conversion as a published
step rather than a projection tenant holding a face — sketched, and resting on
the fact that nothing synchronous waits on a converter today.**

# Definitions out of the heap

## What this is

Every face this store serves keeps a version's definitions in memory as an
object graph — `StructureDefinition`, `ElementDefinition`, and a million-odd
`StringType`, `CodeType` and `UriType` under them. It is built by
`CarriedDefinitions.contextFor` from the carried packages' own bytes, once per
version, and handed to every tenant on that face as a copy that shares the
loaded managers.

This is about not keeping it.

## Why it is not the store's copy

The definitions are already in the database, and that was decided and built
some time ago:

- `REQ-DBO-VER-DEFINITIONS-LIVE-IN-A-SCHEMA-OF-THEIR-OWN` — every definition a
  tenant holds and every row derived from one is in a schema of its own, and
  the face's own SQL functions read what a definition says from that schema
  and nowhere else.
- `REQ-DBO-VER-A-DEFINITION-IS-EXPANDED-WHEN-IT-ARRIVES` — a structure is
  expanded once, on arrival, into element rows the database checks against.
- `REQ-DBO-VAL-AN-INVARIANT-IS-COMPILED-WHEN-IT-ARRIVES` and
  `REQ-DBO-VER-AN-EXPRESSION-THAT-YIELDS-A-VALUE-IS-COMPILED` — rules and
  expressions are compiled at arrival into paths the database runs.
- `REQ-DBO-VAL-TIER-ONE-IS-ANSWERED-IN-THE-DATABASE` — cardinality, fixed and
  pattern values, bindings and reference targets are answered from those rows.
- `REQ-DBO-SRCH-THE-ENVELOPE-IS-EXTRACTED-WHERE-THE-BYTES-ARE` — the database
  builds a document's envelope from the compiled parameters.
- `REQ-DBO-VER-DEFINITIONS-INDEXED-WITHOUT-THE-TOOLCHAIN` — a definition is
  indexed from its JSON with no worker context at all.

So the object graph is a second representation of something the store already
holds in a form it prefers. What keeps it alive is the toolchain, not the
store.

## What it costs, measured

| | |
|---|---|
| a face's first tenant | **226 MB** |
| the next tenant on that face | 11 MB |
| the live heap that is definitions, their strings and those strings' arrays | **over half** |

The 226 and the 11 are the two halves of one sentence in `TenantContext`: the
shared context is one per version and a tenant gets a copy that shares the
loaded managers. Three faces in one JVM is most of a gigabyte before a tenant
is served, which is
[item 023](../023-the-suite-runs-out-of-heap/README.md) — a suite that has
failed four builds on changes that cannot reach a tenant.

**The soft reference does not save it.** `ElementVersion.BY_CODE` holds each
version behind a `SoftReference`, which would let the collector drop an idle
face under pressure. `TenantContext` holds the shared context strongly, so
while one tenant on a face is up the face's definitions cannot be reclaimed.
That is correct — a serving tenant needs them — and it means the soft
reference only ever helps a face nobody is using.

## The precedent

This move has been made once, for terminology, and the reason is written where
it happened:

> The terminology packages are carried but NOT loaded: 40-50% of this build was
> parsing them into heap, and the concepts they carry are tenant data now —
> imported into each tenant's store once and consulted there by validation.

Same shape: something the toolchain wanted in memory became rows the store
holds and the database answers from. What remains loaded, by that same comment,
is the version's own structures, its search parameters, and the code systems
the core package itself carries.

## What the database already answers

Read off the SQL and the recorded baselines, not off an impression.

| Function | Where it is answered | How close the two are |
|---|---|---|
| cardinality, fixed and pattern values, primitives | `dbo.cardinality_in`, `dbo.value_in`, `dbo.primitive_in` | — |
| a coded value is in the value set its binding names | `dbo.binding_in`, joining the tenant's terminology | — |
| a reference points at a record this store holds | `dbo.reference_in`, joining the records | — |
| a rule an element carries | `dbo.invariant_in`, from the path compiled at arrival | — |
| all of the above, under one name | `dbo.validate` | **258 documents compared, 10 divergences, 0 the database invented** |
| a document's search envelope | `dbo.envelope` and friends | **100 documents compared, 0 differing** |
| indexing a definition as it arrives | `DefinitionEnvelopes`, from the JSON | identical over every definition every face publishes |
| an ordinary read | `ElementAncestors.rendered` copies JSON tokens | no definitions touched at all |

The read is worth dwelling on: the plain path already avoids the element
model, and the comment says why — re-rendering through it drops an element the
version does not define, so a document stored under one version comes back
smaller. The context parameter it takes is used by one branch, `_elements`.

**And the ten divergences are classified, one by one, in the baseline itself.**
Three rules the validator carries in its own code and no definition states — a
canonical url must be absolute, a uuid must be lowercase — over six documents.
A StructureMap checked as a program rather than as a document. One toolchain
defect. One code the tenant's terminology does not hold. Nothing compiled from
definitions can produce the first group, and nothing about the others is a
statement about shape.

## What still asks for the object graph, sorted

The column was worth filling in, because the answer is not the one the plan
guessed.

| Holder | Wanted | Read off |
|---|---|---|
| `ElementPayloads.read` | **serving** — every write, and every read that projects | the parse is what produces the `Element` everything else takes |
| `InstanceValidator` | **serving** — every write | `ElementPayloads.check` calls it; the database's answer beside it changes nothing |
| `TenantContext.cacheProfile` | **serving** | it caches into the tenant's own serving context, which `payloadsFor` returns and `ElementStore` holds for the tenant's life |
| `ElementAncestors.projected` | **serving**, one branch | `_elements` only; the ordinary read copies tokens |
| `ElementEnvelopes.extract` | **serving**, for records | a definition type takes `DefinitionEnvelopes` and no context; a record type takes the toolchain path |
| converters a tenant declares | **serving** | the maps are cached into the same tenant context |
| the parity references | **tests** | built deliberately, to be what the database is compared against |

**So nothing is arrival-only, and the cut this plan was built on does not
exist.** A context is not something built when a definition arrives and
dropped; it is the object a tenant serves from, held for as long as the tenant
is up. That is why the soft reference never helps a face in use.

## The cut that does exist

Reading for the first one turned up a second, and it is better. There are two
ways a version's definitions become a context, and a tenant takes one or the
other:

- `CarriedDefinitions.contextFor` builds it **from the carried packages' own
  bytes**. This is the fallback: `ElementStore` takes it when a tenant has no
  version root in its store.
- `FaceBase.of` builds it **from the records a tenant holds** — "one worker
  context per face and process, built from the records a tenant holds rather
  than from the carried packages, and shared by every tenant on that face",
  keyed by version, answered from whichever tenant on the face is still
  mounted, and held softly.

**The second one is this item's own idea, already built.** Its javadoc even
carries the measurement that motivated it: a context per tenant was 86 MB
against 5 MB for a copy of a shared one, and a face base it calls a hundred
megabytes.

So the question is no longer "can the definitions come from the database".
They can, and for a tenant with a version root they already do. The questions
are which tenants still take the carried fallback and why, what the two paths
actually weigh side by side, and whether serving can drop the context
altogether now that the database answers tier one and the envelope at parity.

## The two paths, weighed

Measured by `WhatTheLoadedSpecificationCostsIT`, which exists to put a number
on exactly this sentence, and recorded in `config/memory-baseline.txt`:

| | MB |
|---|---|
| a served tenant, carried packages | **225** |
| a second tenant on that carried context | 11 |
| a face root holding the version as records | **101** |
| a tenant serving from that face base | **4** |
| **a second face served** | **444** |

**The records-backed path is 101 MB against 225, and a tenant on it is 3 MB
against 11.** Better than half off, on a mechanism that is already written,
already shared per face, already held softly, and already chosen automatically
by `ElementStore` for any tenant with a version root in its store.

Two honesties about the numbers. The base was measured in a JVM where the
carried context for the same version already existed, so whatever the two share
— loaded classes, interned strings — is counted against the carried one and not
against the base; a base measured alone could read higher. And a heap delta
after a forced collection still counts what is softly reachable, which is why
this file records rather than asserts.

**So the headline of this item is wrong, and the right one is smaller.** The
226 MB is not what definitions cost. It is what the *fallback* costs, taken by
a tenant with no version root — and the sample world's tenants that have one
are already paying 101 and 3.

## Who takes the fallback

`ElementStore` takes the base when the tenant's OWN store holds the version
root — a `StructureDefinition` whose canonical is
`http://hl7.org/fhir/StructureDefinition/Resource`. So a tenant is on the base
if it is a face root, or if it replicates the version from one over a
dependency declared `"face": true`. Everything else takes the carried packages.

**The sample world is mostly on the base. The suite's shared world is almost
entirely on the fallback.**

| World | On a base | On the carried fallback |
|---|---|---|
| the sample's seven | `fhir-r4`, `fhir-r5` (roots); `gringotts`, `hogwarts`, `st-jerome` (face dependants) | `mom`, `rl` |
| the shared world's twenty-five | `sharedr4faceroot` | **the other twenty-four** |

The shared world has four dependencies and not one of them is a face chain:
they carry a `CodeSystem` or a single `StructureDefinition`, which is content,
not the version. A tenant replicating one profile does not hold the version
root, so it takes the packages.

**And on r4 the suite pays for both.** `sharedr4faceroot` brings up a base, and
the twenty other r4 tenants build the carried context beside it — 225 and 101
for one version, in one JVM, for the same definitions.

The rough arithmetic, as an estimate rather than a measurement: three carried
contexts for r4, r5 and r6 is about 675 MB, plus 11 MB for each tenant after
the first on a face. The same tenants on bases would be about 303 MB plus 3 MB
each. Against a floor measured at 1.6 GB and a corpus that is over half the
live heap, that is the right order to explain
[item 023](../023-the-suite-runs-out-of-heap/README.md) — and the shared world
is what runs in the phase that keeps dying.

**So the first thing to try is not a change to the store.** It is whether the
shared world's tenants can take their face from a root the way the sample
world's do. That is a test-world change, it is reversible, and the instrument
to say whether it worked is already recording.

## What done looks like

**A new face must not cost another hundred megabytes.** That is the criterion,
and it is the one number above that nobody had taken: serving a second version
after the first costs **444 MB**, measured, which is not a hundred but four
hundred. R5 carries more than R4 does and the validator that reads it is the
one the build already gives two gigabytes to.

So the cost of this store scales with the versions it serves, before a single
tenant exists on them. Three faces is most of a gigabyte and it is the whole of
the ceiling item 023 keeps hitting. A store whose pitch is that several
versions serve at once over one engine cannot have "and each one costs half a
gigabyte" as a footnote.

`aSecondFaceServed` is in `config/memory-baseline.txt` now, so the number has
somewhere to fall. Done is when it is small enough not to be interesting.

## The aim is no toolchain in the runtime

101 MB is not a win, it is a smaller waste. Both paths build the same thing —
an object graph of a version's definitions — and differ only in where the bytes
came from. The aim is a serving process that holds none of it.

**Three of the four pieces already exist**, which is why this is a plan rather
than a wish:

- **The definitions are a schema**, and the face's own SQL reads them. The
  functions ship with the release and arrive no other way.
- **The database answers what the toolchain answered.** Tier one at 258
  documents against 10 classified divergences; the envelope at 100 documents
  against none.
- **A face is cut once per release into an image** of its definitions schema —
  "the definitions, their history and everything derived from them" — and a
  tenant comes up from the image rather than expanding anything.

That last one is the shape of the answer. **Whatever still needs a toolchain is
derivation, and derivation belongs in the cut.** An image cutter is a
release-time tool; it may hold HAPI, a gigabyte and a long afternoon, because
nothing is serving while it runs. What it produces is rows.

## What would have to move into the cut, or go

| What still needs HAPI | Where it goes |
|---|---|
| snapshotting a profile | derived data in the image, like the element rows beside it |
| the three rules the validator carries in its own code | explicit checks — a canonical url absolute, a uuid lowercase, an identifier under `urn:ietf:rfc:3986` a full uri. Six documents' worth, and no definition states them, so they are written once rather than derived |
| a StructureMap checked as a program | an ingest concern for a tenant that holds maps, not a serving one |
| version conversion, R4 ↔ R5 | the open one: a zone hop converts while serving. Either the maps compile the way invariants did, or conversion is a process of its own |
| `_elements` | a projection over the stored JSON, located by the element rows that already carry jsonpaths |
| parsing JSON into an `Element` | nothing needs it once the five above are gone; the database walks the document and the ordinary read copies tokens |

**The honest unknown is conversion.** Everything else is either derivation that
can be cut, a handful of rules that can be written, or a projection the rows
already locate. Conversion is a program over a model, it runs when a zone hands
content to a tenant on another version, and no part of it is answered in the
database today.

## How steps pipe together

Before the conversion case, the general one, because the conversion case is an
instance of it and reads as a pattern otherwise.

**A step says what it consumes and what it produces.** That is already the
vocabulary `StepDeclaration` carries:

```java
StepDeclaration.of("lab.result.convert", "1.0", domain)
        .consuming("…/shape/r5-observation")
        .producing("…/shape/r4-observation")
        .taking("scan", "…/shape/scan")
```

So piping is not wiring. B consumes what A produces, and the chain is in the
declarations rather than in a configuration somebody keeps in step. A run is
one step happening once, over the documents filling its slots, leaving what it
read, what it changed, when and on whose authority.

**Then the only question is where the middle lands, and there are two answers.**

- **Hand it on.** The next run takes it as an input slot. No feed, no cursor,
  no second copy. This is the ordinary case, and it is crash-safe without any
  of them: a run is checkpointed, and a step that got somewhere and then failed
  is released with its milestone intact.
- **Publish it.** It becomes a feed, and consumers take it with their own
  cursors. This is for when several things want the same result, or it has to
  be replayable.

The second is the more expensive one and it buys something specific: fan-out
and replay. Reaching for it by default would mean a stream and a cursor between
every pair of steps, which is a great deal of machinery for a chain one tenant
walks once.

## Conversion is the published kind

A zone's content converted for a face is wanted by every tenant on that face,
and `ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION` already says it is
done once rather than once per tenant. So it is the published kind: a step that
consumes the zone's feed, produces on the target face, and publishes what it
produced. Tenants on that face consume it with their own cursors, exactly as
they consume anything.

**Which is what a projection already is, with a tenant wrapped around it.** It
reads the zone's feed, converts, holds the result and publishes. Take away the
database, the bring-up and the face context and a step that publishes is what
remains — and it is the part worth keeping.

**What it changes is who holds the toolchain.** Today `ContentSyncEngine` holds
the converters by the version they convert from and walks the chain at apply:

```java
for (int hops = 0; hops < 8 && !version.equals(targetPayloadVersion); hops++) {
    PayloadConverter converter = convertersByFrom.get(version);
    ...
    payload = converter.convert(item.typeName(), payload);
```

So the receiver is what holds the source version's converters, and therefore
its model classes. With the conversion published, a receiver applies bytes
already in its own version and never loads the source version at all. The chain
above becomes one step's business, in one deployment sized on purpose, rather
than a cost carried by everything that receives.

**What it does not do is remove the memory.** A converter needs both versions'
definitions, so whatever implements the step holds two faces. The gain is that
they exist once in a fleet instead of once per serving node, which is worth
saying rather than letting this item claim a removal it does not get.

**And nothing is injected into anybody's pipeline.** `FEED_ONE_PRIMITIVE`
already makes pagination, subscription delivery, content streams and edge sync
one thing — an ordered, replayable sequence with an opaque durable cursor — so
a converted stream is another instance of it. There is no shared position to
get ahead of: each consumer holds its own cursor, and an item is in the
converted feed only once the run that made it finished.

**What it must answer.**

- **An unconvertible object must still dead-letter visibly** and degrade the
  dependency. A conversion that fails is a run that failed, carrying its input,
  its holder and its reason, which is more than a dead-letter row says — but it
  has to land somewhere a person reads.
- **Definitions and records may not be one job.** A projection converts a zone's
  definitions so a face can be built from them, and content converts on apply.
  Whether one step serves both is the first thing to decide.
- **A step nobody deploys is a tenant that cannot take a zone.** Either the
  store ships an implementation, or a deployment without one degrades in a way
  somebody can read.
- **Reading the feed as a stream is what makes the ordinary case ordinary.**
  `Answered.pagedBy` already walks a `FeedChunk` page by page, lazily and
  without a buffer, and `ContentSyncEngine` reads the same `FeedChunk` in a
  loop. One of them is a pipeline. Concurrency across items is then an
  optimisation with an observable trigger — a named consumer's lag — rather
  than something to design for in advance.

## The sequence

1. ~~Split the holders by arrival against serving.~~ Done: everything but the
   parity references serves.
2. ~~Weigh the two paths.~~ Done: 225 against 101, 11 against 3.
3. ~~Say who takes the fallback.~~ Done: 24 of the shared world's 25.
4. **Decide conversion as a published step**, sketched above. The reading it
   needs first is which conversions are definitions and which are records,
   because that decides whether one step serves both. Nothing synchronous waits
   on a converter today, which is the fact the whole idea rests on.
5. **Write the three rules.** Two are written, one of them is proven, and the
   baseline did not move — which is the useful part.

   A uuid is lowercase now and a canonical carries a scheme, both in
   `dbo.admits` where the primitive forms already live.
   `TheFaceSqlShipsWithTheReleaseIT` holds the second to it: a `baseDefinition`
   of `StructureDefinition/Patient` is refused and the absolute form is not.

   **And the divergence baseline is unchanged at ten.** It counts a document
   rather than a finding — a document diverges when one side found something
   and the other found nothing at all — so a rule appears there only by
   flipping a document, and these flipped none. Not one of the ten carries a
   relative value on an element typed `canonical`. The baseline's own note
   attributes eighteen findings to the absolute-url rule, so those are
   somewhere this did not reach: a `uri`, a `url`, or an element the expansion
   marks unenforceable.

   What the run earned anyway is that `onlyTheDatabase` stayed at zero across
   258 documents, so neither rule over-refuses. Finding where those eighteen
   are wants the findings rather than the tally, and that is the next step.
6. **Snapshot into the cut**: generate it where the image is made and carry it,
   so `cacheProfile` has nothing to build.
7. **`_elements` from the rows**, which removes the last reader of the element
   model on the serving path.
8. **Then delete the context**, and with it the carried-against-base question
   entirely — both paths, not the more expensive one.

**What this does not need.** Putting the suite's shared world on a face root
would take its floor from roughly 675 MB to 303. It is worth an hour if the
suite keeps dying, and it is not progress: it buys a cheaper copy of the thing
being removed. It belongs to
[item 023](../023-the-suite-runs-out-of-heap/README.md) as relief, not here as
a step.

## What this is not

**It is not moving definitions into the database.** They are there. It is
removing the toolchain's second copy of them, which is a different and smaller
claim than the one the title invites.

**It is not a validation rewrite.** Tier one already reads rows. What step 4
decides is where tier two runs, not what it checks.

**It is not the answer to item 023 on its own.** That item's dial went to 3g
because three builds died; this is what would let it come back down, and until
step 1 is done nobody knows by how much.

## What proves it

The parity that already guards the database's own answers is the shape to
reuse: the envelope check builds both and compares them over everything the
version publishes, per type, as a number that may rise and may not fall.
Anything moved out of the heap here is held to the same standard — the
toolchain's answer is the reference until the day nothing builds one.

```bash
# what a face's definitions weigh, and what they are made of
./gradlew :core:harness:test --tests "*MilestonesOnTheCheckpointIT" \
    -Ddbo.heap.attribute=true -Ddbo.heap.histogram=true
head -30 core/harness/build/heap-histogram.txt
```
