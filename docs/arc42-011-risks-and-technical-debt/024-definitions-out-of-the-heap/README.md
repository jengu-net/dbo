**Open, and no longer "nothing is built". Seven of the nine moves on the
critical path are done and most were closed by reading rather than by work: the snapshot is kept and travels in the face image, `_elements` is the
same token copy a whole read is, framing held a context it had stopped
reading, and a parameter's evaluability never read a definition at all. `ElementStore` has no serving reaches left: `_include` reads the edges the
write extracted, and a tenant's conversion maps are loaded when a reshape asks
rather than held for its life. What remains is step 8 — a write judged by the
database rather than by `InstanceValidator` — and then the context itself.
None of it is a megabyte yet — a context held for any
reason is held whole — which is the point of reading the path rather than the
list. A version's definitions are parsed into a HAPI object
graph and held while a tenant serves. The criterion is that a new face must not
cost another hundred megabytes; measured, **a second face costs 444**, on top of
225 for the first. The aim is a serving process holding neither the carried
graph nor the records-backed one. Three of the four
pieces are already there: the definitions are a schema the face's SQL reads,
the database answers tier one and the envelope at parity, and a face is cut
once per release into an image of everything derived from them. Conversion as a
published step is read out now: a version converter holds no definitions at
all, so it can leave a tenant, while a shape converter resolves a map the
tenant holds and cannot — and what leaving saves is a projection **tenant**
rather than a context. Which also takes conversion off the path to deleting
the context: it was the one thing on that list nobody could see the end of, and
it was never on it. The path is written out now, nine moves with no partial
win, and the first is the snapshot into the cut.**

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

## What the serving store reaches for, counted

`ElementStore` is the object a tenant serves from, and it asks for the worker
context in seven places. They are not seven of a kind:

| | what it wants it for | when |
|---|---|---|
| framing a document, rendering one, framing a bundle | the element model, for `_elements` and for putting the engine's facts back | serving |
| resolving what an `_include` points at | walking a reference target | serving, per request |
| whether a stored `SearchParameter`'s expression is evaluable, twice | parsing FHIRPath | **arrival** — when a parameter is written |
| expanding a differential from the view | generating a snapshot | **arrival** — when a profile is written |

**Three of the seven are arrival.** A parameter arriving and a profile arriving
are both derivation, and derivation is what the image cut is for. They do not
need a context that stays; they need one that exists while the definition is
being taken in.

**Four are serving**, and three of those are the same want: the element model,
to frame or to render. The fourth is `_include`.

So the shape of the remaining work is clearer than "remove the toolchain". It
is: move three to the cut, answer `_elements` and framing from the stored JSON
and the rows that locate it, and decide where `_include` resolves. Nothing on
that list needs a decision about validation, because the database already
answers tier one — and nothing on it is the 444 MB until all four of the
serving ones are gone, since a context held for any reason is held whole.

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

**And it is not on the path to deleting the context**, which the row above got
wrong by grouping it with the rest. Read out in step 4: a version converter is
`VersionConvertorFactory_40_50` over two object models and holds no
`SimpleWorkerContext` at all. It keeps HAPI's r4 and r5 model classes resident
and it keeps no definitions. So conversion blocks *no toolchain in the
runtime*, which is the larger aim, and blocks nothing in the list below. The
shape converter is the one that holds a context, and it holds the **tenant's**
own — which is a different object from the version's and goes when the tenant's
own held maps stop being executed in the serving process.

## The critical path

The sequence below is ordered by what has to exist first. The list above is
what has to move; this is the order it moves in, and what each removal buys.
Written out because the item carried the pieces in three tables and the
dependency between them nowhere.

| # | Move | Removes | Needs |
|---|---|---|---|
| 1 | ~~**Snapshot into the cut**~~ **Built.** The snapshot is kept in `definitions.definition_snapshot`, so the schema an image is cut from carries it and the view is handed a definition with nothing left to build | `cacheProfile`'s differential expansion, an *arrival* reach | the cut, which exists |
| 2 | ~~**A parameter's expression checked at the cut**~~ **Not needed.** The check parses; parsing reads the text | nothing — these were never reaches | — |
| 3 | **The three rules** — canonical absolute, uuid lowercase, identifier under `urn:ietf:rfc:3986` a full uri | the validator's own code as a reason to hold a context | nothing; two are written and proven, the third is written |
| 4 | ~~**`_elements` from the rows**~~ **Built, and the rows were not needed.** The filter is top-level names, so the same token copy a whole read uses answers it | `ElementAncestors.projected`, one serving branch — and the context parameter with it | nothing |
| 5 | ~~**Framing and rendering from stored JSON**~~ **Closed by 4**, not by work of its own: framing already wrote the Bundle shape directly, and the rendering it delegates to stopped needing a context | three of `ElementStore`'s four serving reaches | — |
| 6 | ~~**Decide where `_include` resolves**~~ **The edges, built.** They are extracted on write and keyed by the parameter's own name; the policy half stays with [item 021](../021-asking-the-store/README.md) | `ElementStore`'s fourth and last serving reach | — |
| 7 | ~~**A StructureMap becomes a conversion-time concern**~~ **Built.** A reshape is an operator asking once, so the maps are loaded when one runs rather than held for a tenant's life | the tenant context's maps on the serving path | — |
| 8 | **Types declare `verdict: database`** | `InstanceValidator`, the largest serving reach | the database answering tier one, which it does; the divergence baseline says how far |
| 9 | **Delete the context** | the 225 MB, and the 101 with it | 1–8, because a context held for any reason is held whole |

**Nothing on it is the 444 MB until all of it is done**, which is the item's
own sentence and is the reason to read the path rather than the list: there is
no partial win. Eight removals buy nothing measurable and the ninth buys all of
it.

**Two are not this item's to decide.** Step 6 is the same question item 021 is
answering for joins, and whichever answers first answers for both. Step 8 rests
on the divergence baseline — five divergences left, one of which needs a column
`definitions.term_system` does not have — and that is measurement this item
already records rather than work it has to schedule.

**Step 2 came off the list rather than being done**, which is the second time
reading a step has been worth more than building it. `whyNotEvaluable` builds a
`FHIRPathEngine` over the context and calls `parse`, and parsing FHIRPath is a
question about the text: `Unicorn.horn.where(length > 3)` — a type no
definition anywhere declares — parses clean, while `name.where(` and an empty
expression are still refused. So the two reaches want *a* context and not the
one holding a corpus. `WhatParsingAnExpressionNeedsTest` holds both halves,
because the first assertion alone would pass against a check that answered yes
to everything.

**What step 1 cost, which is the part worth keeping.** The first cut of it
wrote the snapshot where the snapshot was generated — inside the expansion —
and that made the kept set a record of *what the process happened to generate*.
A definition whose rows are current is skipped by the expansion, so a tenant
that loaded an image inherited rows and nothing else, while a tenant that read
a chain expanded and kept. Two tenants holding one face then disagreed about a
table derived from definitions they agree on, and
`ATenantComesUpFromTheFaceImageIT` said so. The fix is to split the one skip in
two: rows are re-derived when they are stale, and a snapshot is kept when it is
missing, which are different questions about the same definition. A second
derivation that follows the first is a cache; one that follows the definition
is derived data, and only the second belongs in an image.

**Step 4 was a decision before it was a change.** `projected` parsed through
the element model, dropped the children nobody asked for and composed it back;
the filter is top-level names only, so no jsonpath and no row came into it. But
its own comment defended the model: *what the model does not know about is not
among the elements they named*. So a narrowed read silently dropped what the
toolchain did not recognise and a whole read kept it, and two reads of one
record disagreed about what was in it. Making them agree was the call, and the
way to keep them agreeing is one path rather than two that match — `projected`
is gone, `rendered` takes the names, and `ElementAncestors` no longer takes a
context at all. `resourceType` is kept beside `id` and `meta`, because what is
left of a narrowed document still has to say what it is.

It also cost a recorded finding, which is the honest way round: the UBL spike
had established that a logical model must declare the store's slots or a
narrowed read throws. That was the model path talking, and
[item 011](../011-ubl-as-a-face/README.md) says so now.

**Step 5 was already done when step 4 landed**, which is worth recording
because it did not look that way from the table. `ElementFraming` writes the
Bundle shape itself — the comment says why: it is the same in every version
this face serves, and building one through the element model would mean
holding a page — so the only reason it held a worker context was the member
rendering it delegates to. When that stopped taking one, the supplier stayed:
declared, wired at two construction sites, and read by nothing. That is the
state a reach is in just before somebody reads the table and concludes it is
still required.

So the row's three reaches were one reach counted three times, and what is
left of `ElementStore`'s four is `_include` alone.

**Steps 6 and 7 were both smaller than the table, and for the same reason
twice.** `_include` re-derived something the write had already extracted and
indexed — the edges are keyed by `pathName(parameter.getCode())`, which is the
name a caller spells — so following a reference became an indexed read and
`referencedTargets` was deleted. The maps were loaded into the object a tenant
serves from, for a conversion that only a maintenance request ever asks for, so
they are loaded when one is asked for instead.

Neither needed the element rows, the cut, or a decision. What the table
recorded in both cases was where the work *appeared* to be, and the reading
found it somewhere cheaper — which is now the rule rather than the surprise:
**five of the nine moves have come off this list by being read.**

**The branch points are 4 and 8**, and 4 is done, so what is left that can be
wrong before a megabyte moves is step 8. Everything before 4 is derivation moving to
a cut that already exists, which is mechanical. Step 4 is the first thing that
changes what a serving request does, and step 8 is the first that changes what
a write is judged by. If either turns out to be wrong, it is wrong before any
memory has been saved — which is the argument for doing them early rather than
saving them for last.

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
4. **Decide conversion as a published step**, sketched above. **The reading is
   done, and the split is not the one this step expected.**

   It asked which conversions are definitions and which are records, on the
   assumption that one step might not serve both. The converter does not
   branch on it: `PayloadConverter.convert(typeName, payload)` takes a type
   name and bytes, and a `StructureDefinition` and a `Patient` go through the
   same call. Where the two do differ is absorbed before the converter — a
   `CodeSystem` is carried whole by the grain codec, because the source stores
   a shell and a dependent cannot rebuild anything from one. So one step
   serves both, and the question that decides it is a different one.

   **The split that matters is whether a conversion needs the tenant's own
   held content.** Two kinds, and only one of them can leave:

   | | what it is | what it needs |
   |---|---|---|
   | version conversion | `R4ToR5Converter`, `R5ToR4Converter` — parse, `VersionConvertorFactory_40_50`, compose | the two model bundles, and **no `SimpleWorkerContext` at all** |
   | shape conversion | `ElementShapeConversion` — executes a StructureMap | the **tenant's own** context, to fetch a map the tenant holds and to drive the element model |

   A version converter is generated code over two object models. It resolves
   nothing, so it is publishable as a step anywhere, by anybody who has the
   two bundles. A shape converter selects a map out of the tenant's own pack
   and cannot leave the tenant without taking the pack with it — which is the
   point of pack-shipped converters rather than a defect.

   **And it corrects this item's own arithmetic.** Moving version conversion
   out of a tenant saves none of the 226 MB, because a version converter never
   loads a definition. What it removes is the **projection tenant** — a
   database, a bring-up and a face — and the 226 is in the face. The saving is
   a whole tenant rather than a context, which is larger than the sketch
   claimed and arrives for a different reason.

   The three call sites, for the record: `ContentSyncEngine.tryApply` converts
   on apply and dead-letters on failure, `PgObjectStore.upgraded` is a payload
   hop taken on read, and `Reshape.convertOne` is a maintenance run over a
   type. Only the middle one is synchronous, and it is a shape hop inside one
   version rather than a conversion between faces — so the fact the idea rests
   on holds: nothing synchronous waits on a face converter.
5. **Write the three rules.** Two are written, proven, and they closed half the
   divergence.

   A uuid is lowercase now and a canonical carries a scheme, both in
   `dbo.admits` where the primitive forms already live.
   `TheFaceSqlShipsWithTheReleaseIT` holds the second to it: a `baseDefinition`
   of `StructureDefinition/Patient` is refused and the absolute form is not.

   **The baseline went from ten divergences to five**, and every
   `CapabilityStatement` closed — eight compared, none disagreeing, where five
   did. `onlyTheDatabase` stayed at zero across 258 documents, so neither rule
   over-refuses.

   **It took two runs to learn that, and the first one lied.**
   `-Ddbo.divergence.record=true` was never forwarded to the test JVM, so the
   recording silently did nothing and the run compared against the old file and
   passed. The flag is forwarded now, beside the heap flags that had the same
   trap. A recording that quietly does nothing leaves a ratchet reading like a
   measurement.

   What remains is five, and `-Ddbo.divergence.name=true` now writes what
   decided each one to `build/divergence-findings.txt` — the error and fatal
   issues alone, because an outcome leads with warnings and the first attempt
   at this truncated one and read as though it had no errors at all.

   Read out, they are:

   | | what decided it |
   |---|---|
   | `StructureDefinition` | `cid-0`, which the toolchain cannot evaluate: *the name 'name' is not valid for any of the possible types* |
   | `StructureDefinition` | **the third rule** — *if identifier.system is 'urn:ietf:rfc:3986', then the identifier.value must be a full URI*, twice, on an example value |
   | `StructureMap` ×2 | checked as programs: an unknown source or target context, a target path not on the type |
   | `ValueSet` | about a hundred and ten errors, every one *Unknown code … in the code system 'http://snomed.info/sct'* on `compose.include.concept.designation.use` |

   **The third rule is written too**, as `dbo.identifier_in` rather than in
   `dbo.admits`: it is a relation between two elements — the system says the
   value is a uri — rather than a primitive form. It is keyed on the system
   alone, since nothing else can carry `urn:ietf:rfc:3986`, and
   `TheFaceSqlShipsWithTheReleaseIT` holds it to all three of its edges: a
   label under that system is refused, a real uri is not, and an identifier
   under another system is left alone.

   **And it does not close the divergence, which is the interesting part.** The
   corpus's occurrence is not a `StructureDefinition.identifier`. It is inside
   `snapshot.element[9].example[0].value.ofType(Identifier)` — an example value
   on an element definition.

   **The walk cannot reach it, and neither can any other check.**
   `dbo.instances` descends the `definition_element` rows down their parent
   chain, so it goes exactly as far as the expansion did — and the expansion
   follows the profile's own snapshot. A snapshot names an element of a complex
   type and stops: it says `StructureDefinition.snapshot.element` is an
   `ElementDefinition` without saying what an `ElementDefinition` holds.
   Asserted rather than reasoned — there is no row whose path begins
   `StructureDefinition.snapshot.element.`, and there are rows under
   `Patient.identifier` on a profile that constrains one.

   So the reach of everything in `dbo.validate` is the same: **a datatype's
   insides are checked where a profile constrains them and nowhere else.** The
   toolchain walks further because it holds each datatype's own definition,
   which is one more thing the object graph is for. That is a limit worth
   knowing before anybody counts on tier one covering a document, and it is
   held by a test now rather than inferred from a rule that did not fire.

   **And the `ValueSet` turns out to name a check the database does not have.**
   Its findings are not about the binding. `dbo.binding_in` fires only where
   `binding_strength = 'required'`, and `designation.use` is extensible — which
   the toolchain agrees with, reporting the binding itself as a warning. What
   it reports as an ERROR is different: the code does not exist in
   `http://snomed.info/sct` at all, *answered from this tenant's terminology*.

   The terminology SQL answers whether a code is in a value set —
   `dbo.in_value_set`, three-valued, with NULL for cannot say. Nothing answers
   whether a code exists in the code system it names. So a code naming a system
   this tenant holds and absent from it produces no finding, at any binding
   strength.

   That is not the unresolvable case the store already reasons about: a system
   the tenant does not hold cannot be judged, and is not judged. A system it
   does hold can be.

   **And it cannot be built today, for a reason worth knowing.**
   `definitions.term_system` records a url, a version, a concept count and when
   it was updated. It does not record the code system's `content` — whether
   what was imported is the whole of it or a fragment. So "this tenant holds
   the system" cannot mean "this tenant holds all of it", and a check that
   refused a code for being absent would refuse valid data wherever a system
   was imported in part. Which is the normal case for SNOMED, and is precisely
   this document.

   So the baseline files it as content and is right twice over: carrying the
   codes closes it, and the database cannot honestly say otherwise until a held
   system says how much of itself it is. That is the smallest change that would
   make the check possible, and it is a column rather than a design.

6. **Snapshot into the cut**: generate it where the image is made and carry it,
   so `cacheProfile` has nothing to build. This and everything after it are
   ordered in *The critical path* above, with what each removal buys and what
   it needs first.
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
