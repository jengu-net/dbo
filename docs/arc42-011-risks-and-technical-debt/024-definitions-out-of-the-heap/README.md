**Open. Nothing is built. A version's definitions are parsed into a HAPI
`SimpleWorkerContext` once per face and held for as long as any tenant on that
face is up: 226 MB measured, against 11 MB for the second tenant on the same
face. The store's own definitions are already in a database schema and read
with SQL, so this is the toolchain's second copy. The move has been made once
before, for terminology, and it took 40 to 50% off building the context.
Nothing here is built once and dropped: every holder but the parity references
serves. What is already built is the other half — `FaceBase` is a context made
from the records a tenant holds, shared per face, and a tenant with a version
root takes it. Next: weigh that against the carried fallback, and say who still
takes the fallback.**

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

## The sequence

1. ~~Split the holders by arrival against serving.~~ Done, above, and the
   answer is that everything but the parity references is serving.
2. **Weigh the two paths side by side.** `CarriedDefinitions.contextFor`
   against `FaceBase.of`, same version, same instrument as item 023. The 226 MB
   this item opens with is one of them and nobody has said which, or what the
   other costs.
3. **Say which tenants take the carried fallback, and whether they need to.**
   `ElementStore` chooses it when a tenant has no version root in its store. If
   that set is small, or closable, the fallback stops being a steady-state cost
   and this item's headline number changes.
4. **Snapshot at arrival.** A profile is snapshotted so it can be validated
   against, and a snapshot is derived data like the element rows beside it.
   Deriving it once when the definition arrives and storing it is the same move
   as the expansion, on the same trigger, and removes `cacheProfile`'s reason
   to hold a live context.
5. **Declare what tier-two validation is.** Tier one is answered in the
   database and named. What the toolchain still answers — slicing, profile
   conformance, the rules no row can carry — has no such statement, and it is
   the only thing that plainly needs an object graph. Deciding whether it is
   served per write, on request, or by a separate process is deciding whether
   any context survives.
6. **Answer framing without the definitions**, or show it needs them. Framing
   puts the engine's own facts back around a stored payload; whether that reads
   a definition or only the parsed element is a question the code answers and
   nobody has asked it here.
7. **Then the search parameters**, which are already compiled and whose
   in-memory copy may have no caller left once 2 and 5 are done.

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
