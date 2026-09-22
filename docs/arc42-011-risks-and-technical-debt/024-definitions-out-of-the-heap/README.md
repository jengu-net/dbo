**Open. Nothing is built. A version's definitions are parsed into a HAPI
`SimpleWorkerContext` once per face and held for as long as any tenant on that
face is up: 226 MB measured, against 11 MB for the second tenant on the same
face. The store's own definitions are already in a database schema and read
with SQL, so this is the toolchain's second copy. The move has been made once
before, for terminology, and it took 40 to 50% off building the context. Next:
split the 226 MB into structures, search parameters and core code systems, so
the order of work is read rather than guessed.**

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

## What still asks for the object graph

| Holder | What it wants | Needed while serving, or only at arrival? |
|---|---|---|
| `ElementPayloads.read` | parsing FHIR JSON into an `Element` | serving — everything below inherits it |
| `InstanceValidator` | slicing, profile conformance, the rules above | serving, on every write |
| `TenantContext.cacheProfile` | snapshotting a profile before it can be validated against | **arrival** |
| `ElementAncestors.projected` | `_elements` — parse and re-compose | serving, one branch of read |
| `ElementEnvelopes.extract` | FHIRPath over a parsed document | to be established — the database does this too |
| version conversion | R4 ↔ R5 over the model | **arrival**, and a zone hop |
| the parity references | being the answer the database is compared against | tests |

**The column on the right is the plan.** A context needed only when a
definition arrives can be built, used and dropped; one needed while serving
must stay resident for as long as a tenant is up. Splitting the holders on that
line is worth more than any single removal, because everything on the arrival
side stops costing steady-state memory without anything being reimplemented.

## The sequence

1. **Split the holders by arrival against serving**, which is the table above
   with its last column filled in. Two entries are already arrival-only and one
   is unknown; establishing that is reading, not building, and it decides
   whether this item is large or small.
2. **Split the 226 MB.** Structures, search parameters, core code systems: one
   run with the histogram already built for item 023, counting instances by
   package rather than guessing. What is left after step 1 is what this
   measures.
3. **Snapshot at arrival.** A profile is snapshotted so it can be validated
   against, and a snapshot is derived data like the element rows beside it.
   Deriving it once when the definition arrives and storing it is the same move
   as the expansion, on the same trigger, and removes `cacheProfile`'s reason
   to hold a live context.
4. **Declare what tier-two validation is.** Tier one is answered in the
   database and named. What the toolchain still answers — slicing, profile
   conformance, the rules no row can carry — has no such statement, and it is
   the only thing that plainly needs an object graph. Deciding whether it is
   served per write, on request, or by a separate process is deciding whether
   any context survives.
5. **Answer framing without the definitions**, or show it needs them. Framing
   puts the engine's own facts back around a stored payload; whether that reads
   a definition or only the parsed element is a question the code answers and
   nobody has asked it here.
6. **Then the search parameters**, which are already compiled and whose
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
