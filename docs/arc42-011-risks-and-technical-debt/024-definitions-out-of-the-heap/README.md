**Open. Nothing is built. A version's definitions are parsed into a HAPI object
graph and held while a tenant serves. The criterion is that a new face must not
cost another hundred megabytes; measured, **a second face costs 444**, on top of
225 for the first. The aim is a serving process holding neither the carried
graph nor the records-backed one. Three of the four
pieces are already there: the definitions are a schema the face's SQL reads,
the database answers tier one and the envelope at parity, and a face is cut
once per release into an image of everything derived from them. Next: conversion as a step
dbo provides rather than a projection tenant that holds a face — sketched, and
resting on the fact that nothing synchronous waits on a converter today.**

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

## Conversion as a step, not as a tenant

The open piece has a shape worth writing down, and the first thing to say about
it is that **cross-version conversion is already not on a read**. Two promises
place it:

- `SYNC_CONVERT_ON_APPLY` — streamed objects are converted **at apply** into
  the receiving tenant's version, and an unconvertible one dead-letters visibly.
- `ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION` — a zone reaches a
  face it was not written in through **one projection per zone per face**, so
  the conversion happens once rather than once per tenant.

`CORE_UPGRADE_ON_READ` is the one that sounds like a counter-example and is
not: it is a schema-version hop, a tenant's own object shape moving, rather
than a FHIR version.

So nothing synchronous waits on a converter today, which is what makes the
proposal available at all.

**The proposal: conversion is a step dbo itself provides.** A tenant that has
subscribed to the registered faces holds their definitions as records, and
offers conversion as declared work over them — a run per conversion, with its
inputs, its holder and its outcome, rather than a code path inside a projection.

What it buys, in the order the gains matter:

1. **The serving nodes hold nothing.** Today a projection is a tenant: it has a
   database, it comes up, and it holds a face's object graph. Every node
   serving a face pays for that face. A converter step is run by whatever
   process implements it, so the toolchain lives in one deployment that is
   sized on purpose instead of in every node that serves.
2. **A conversion becomes visible.** `PROC_AUTOMATION_IS_DECLARED` asks for
   automation to be as auditable as a terminology overlay rather than a code
   path that happens to run. A conversion that is a run says who asked, what
   went in, what came out and whether it held.
3. **It is the projection's own idea, generalised.** "Once rather than once per
   tenant" is already the rule; this moves the *once* out of a tenant and into
   work.

**A converter is a consumer that publishes, and that is the whole mechanism.**
Both act as data arrives. A consumer holds a named cursor, acknowledges, and
resumes from the last acknowledged position; a converter does the same and
emits what it made. `FEED_ONE_PRIMITIVE` already says pagination, subscription
delivery, content streams and edge sync are one thing — an ordered, replayable
sequence with an opaque durable cursor — so a converted stream is another
instance of it rather than a new kind of pipe.

Which means nothing is injected into anybody's pipeline. A conversion is a
consumer of the zone's feed that publishes a feed on the target face, and the
tenants on that face consume it with their own cursors, exactly as they consume
anything. The cursor question disappears because there is no shared cursor to
get ahead of: each consumer has its own, and an item exists in the converted
feed only once the conversion that made it was acknowledged.

**And that is what a projection already is**, with a tenant wrapped around it.
A projection reads the zone's feed, converts, holds the result and publishes
it. Take away the database, the bring-up and the face context and what is left
is a consumer that publishes — which is the thing worth keeping.

**And the stream is where it would be injected.** Today the RECEIVER converts.
`ContentSyncEngine` holds the converters by the version they convert from and
walks the chain at apply, hop by hop, dead-lettering the item if the chain does
not reach the target:

```java
for (int hops = 0; hops < 8 && !version.equals(targetPayloadVersion); hops++) {
    PayloadConverter converter = convertersByFrom.get(version);
    ...
    payload = converter.convert(item.typeName(), payload);
```

So the tenant taking content from a zone written in another version is the
tenant holding that version's converters — and therefore its model classes.
That is the cost, in the place it is least wanted: on every receiver.

If the stream injects a conversion step between publish and apply, the receiver
applies bytes already in its own version and **never loads the source version
at all**. The chain above becomes the step's business, in one place, and the
derivation rule does not change: a projection is already synthesised from a
zone's version and the faces of the tenants that asked for it, so a conversion
step is synthesised from the same two facts.

The dead-letter gets better rather than worse. A conversion that fails is a run
that failed, with its input, its holder and its reason, instead of an entry in
a dead-letter table — and degrading the dependency is what a failed run already
does.

**What it must answer.**

- **It moves the memory rather than removing it.** A converter needs both
  versions' definitions, so whatever process implements the step holds two
  faces. That is the one place the object graph genuinely has to exist, and the
  gain is that it exists once in a fleet instead of once per serving node —
  which is worth saying out loud rather than letting the item claim a removal
  it does not get.
- **The apply path's guarantees have to survive.** An unconvertible object
  dead-letters visibly and degrades the dependency; a step that fails has to
  land in the same place rather than in a queue somebody else watches.
- **Definitions and records are not the same job.** A projection converts a
  zone's definitions so a face can be built from them, and content converts on
  apply. Whether one step serves both, or the definitions half stays where it
  is, is the first thing to decide.
- **Who runs it.** A step service that nobody deploys is a tenant that cannot
  take a zone. Either the store ships an implementation, or a deployment
  without one has to degrade in a way somebody can read.
- **Where the step sits, which decides whether a cursor is a question at all.**
  Today one consumer reads item N, converts it, applies it and advances to N,
  all in a row. A step is work — created, claimed, performed — so read and
  apply stop being one motion, and there are two places to put it.

  *Upstream*, converting before the item enters the stream the consumer reads:
  the consumer's cursor is over already-converted items, and an item does not
  exist in that stream until the run that made it finished. There is no cursor
  question, and this is what a projection already is — a tenant on the target
  face holding converted content that members sync from.

  *At the consumer*, reading item N and waiting on a run before applying: now
  the cursor is a real question, because advancing after dispatch acknowledges
  an item that has not been applied, and holding it stalls the stream behind
  every conversion.

  **And a stream collapses the choice.** A feed read as a lazy stream makes
  the conversion a stage rather than a place: an item is converted on its way
  to the terminal operation, so it cannot reach the apply unconverted and the
  cursor cannot be ahead of it. The ordering is a property of the pipeline
  rather than something the consumer has to be careful about.

  The shape is already in the tree, and in both halves. `Answered.pagedBy`
  walks a `FeedChunk` page by page as a `Stream`, lazily, holding no buffer —
  written for asking the store a question. `ContentSyncEngine` reads exactly
  the same `FeedChunk` and loops over it by hand. Same type, one of them a
  pipeline and the other a loop.

  Futures are then an optimisation rather than a requirement. A converter
  that acts as data arrives is already keeping up or already behind, and its
  lag is observable because its cursor is named. A bounded window of runs in
  flight is worth writing when one conversion at a time is measurably too
  slow — a stream has no ordered, bounded, parallel map of its own before
  `gather` — and not before.

  Delivery is idempotent and conversion is pure, so re-running a step is safe
  either way.

## The sequence

1. ~~Split the holders by arrival against serving.~~ Done: everything but the
   parity references serves.
2. ~~Weigh the two paths.~~ Done: 225 against 101, 11 against 3.
3. ~~Say who takes the fallback.~~ Done: 24 of the shared world's 25.
4. **Decide conversion as a step**, sketched above. The reading it needs first
   is which conversions are definitions and which are records, because that
   decides whether one step serves both. Nothing synchronous waits on a
   converter today, which is the fact the whole idea rests on.
5. **Write the three rules**, which is small, self-contained, and closes most
   of the divergence baseline — the remaining gap between what the database
   says and what the toolchain says.
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
