**Open. Seven of the twelve moves on the critical path are done, an eighth
is half built and half costed, and a ninth is spiked; four were
measurements, the fifth was the decision they were gathered for, and the
checker exists. A face costs 225 MB because of the form its definitions are
held in, not their size: 92,807 element definitions as object graphs, 1.1
million primitive wrappers, one byte array per string. The three cheap ways
out are closed by measurement — lazy loading is 133 MB worse, eviction is
impossible because the toolchain discards the reload path at first use, and
the shape is fixed by a manager that holds model objects. What is left is a
definition index of our own: flat rows in heap, per tenant, over the closure
its declared types reach, under a face base the image already carries. **A
closure is now counted**, and it needed no face root after all — the walk
reads the carried packages, which is the same claim the index makes.
Hogwarts' eleven declared types reach 78 of r5's 389 structures and **1,164
of its 16,150 elements, 7.2%**. The shape of that is the finding: a fixed
kernel of 64 datatype structures, 462 elements, that every resource type
reaches, and one to three structures per type above it. It is a
**per-tenant** win and not a per-face one — a face whose tenants between
them declared every resource would reach 94% of the corpus, so one index
shared by a face's tenants would save nothing. **And the form is now
measured too**: the same 78 structures cost **190 KB flat against 5,966 KB
as model objects, 31 times**, 167 bytes an element against 5,249. A tenant's
whole index is 190 KB where a face's context is 225 MB, and r5's entire
corpus in the flat form is 2.6 MB. The premise is no longer a premise. **And
something reads it**: a cardinality checker over the index faults none of
the 6,532 conformance resources the release carries, and finds the faults
that are there. It answers at every depth, entering a backbone where the
resource defines it and a datatype's own structure where it does not —
276,258 descents over that corpus, five path segments at the deepest. **And
it has been compared against the answerer that currently decides**: on what
is missing the two agree exactly, 3 reported and the same 3 found over 402
documents, none missed and none added. On what is repeated they do not, and
the checker is the one that is right — an element allowed once and sent
twice is dropped by the toolchain in silence, so
`{"gender":["female","male"]}` is parsed into a Patient with no gender at
all and accepted. That is a data-loss defect on this store's write path
today, recorded below and independent of whether any of this is built. **And
the whole-version figure is now measured rather than extrapolated**: all
three carried versions held at once are 41,013 elements in **4.3 MB**, 108
bytes each, against a recorded 226 MB for one face and 445 for a second —
which is item 024's criterion answered as a question about the form. **Step
4 is taken: yes, build the modules** — settled by reading the distribution,
which corrected the last move and found a missing one. The ratchet was aimed
at the validator jars and could never have fired: `SimpleWorkerContext`
ships beside the 1,517 model classes conversion needs. It is the definition
PACKAGES that have to go, 65 MB of them, and conversion and transaction
bundles do not block that. The missing move is larger: nothing replaced
`elementmodel` on the PAYLOAD path, so a finished checker still leaves every
write parsing through a populated context, and the 225 MB survives it. That
is now step 9, and it was spiked immediately because it is what would have
reversed the decision. **It holds**: 6,532 documents and 86 MB go in and
come back unaltered with no context and — the surprise — no type knowledge
either, since reading and writing a document losslessly is a JSON problem
and the definitions are for checking it. 91.9% of r5's search expressions
are plain paths a typed walk evaluates; the open 8.1% is a hundred needing
`ofType`, `where` or a cast. **Steps 5 and 6 are built.**
`core/dbo-fhir-index`, a bundle whose Import-Package is the JDK and nothing
else, reading the index out of `definitions.definition_element` rather than
out of a package. Over a face root's closure — 44 structures of the 740 it
holds rows for, **5.9%** — it agrees with the packages **element for
element, 750 of them, no divergence**. And it corrected the shape: the three
modules are two, because the expansion that already runs when a definition
arrives IS the compiler the design wanted to write. **And the word "third"
is now a fact**: `core/dbo-fhir-validate` checks cardinality against the
index with no context and no round trip, and stands in the comparison beside
the toolchain and the database. Over 258 of the version's own documents
neither it nor the database faults anything, across 21,267 descents; on
documents that ARE wrong they name the same elements at every depth. The one
divergence is deliberate and the index is the side that reaches further — a
datatype's insides are in the rows only where a profile constrains them, and
the index holds the closure. Nothing on the serving path asks either module
yet and the reach ledger names both: comparing is not being asked. **Step 7
is half of itself**: fixed and pattern are built and agree with the database
over a tenant's own profile, which is where pinned values actually live —
the closure they sit in has zero in its base structures, so a checker proven
only against what HL7 publishes would never have run that code. Running it
against a profile found two silent defects in the walk, both of which
reported a clean document: it left the profile's own structure on reaching a
datatype and walked past the constraint, and it took its root path from the
canonical's last segment, which is the profile's name rather than the type.
The other two checks are costed rather than built, and both came out small:
a required binding over a closure is 58 elements naming 43 value sets, all
held, all a whole code system, **598 codes** — not an expansion engine — and
a slice is **203 predicates, 1.5%**, every one of them equality optionally
joined by and. Two traps on the way to the first number each gave a
confident wrong answer: a binding names a canonical with its version and the
terminology is keyed without one, and a compose is stored under this store's
own plural names. Next: build the two now that neither is a guess, or step
9's other half.**

# A face toolset of our own

## What this is

A compact definition index, read out of the rows a definition was already
expanded into, and a validator that reads the index. A serving process holds
the index. It does not hold a worker context, and eventually it does not carry
what builds one.

The index was to be compiled from the HL7 packages at build time. Step 5
found that it should not be: the rows are the only place a tenant's own
profiles and what a face image carried arrive at all, and the expansion that
writes them is the compile.

Conversion is not part of it and stays with the toolchain.

## Why the cost is the form

Asked of the JVM rather than reasoned about
([item 023](../023-the-suite-runs-out-of-heap/README.md)):

```
 3,660,386  byte[]              449 MB
 3,629,356  java.lang.String     87 MB
 1,126,342  StringType           63 MB
    92,807  ElementDefinition    18 MB
```

One byte array per string to within a percent, and 69% of every string alive is
a `StringType`, `CodeType`, `UriType`, `IdType` or `MarkdownType` inside a
definition. An element's `path` is a `String` in a `StringType` in an
`ElementDefinition`: three objects and their headers for one piece of text that
repeats thousands of times. R5's definitions are tens of megabytes of JSON and
cost 225 MB parsed into a model built for authoring resources rather than for
checking them.

## Why the cheap ways out are closed

Each was tried or read before this was written, and each is recorded in
[item 024](../024-definitions-out-of-the-heap/README.md).

**Loading lazily is worse.** Offering the carried definitions by name, through
the toolchain's own proxy, was built behind a flag and measured: 133 MB worse
across the same scenarios, with a second tenant on one face going from 11 MB to
135. Deferring moves the parse to whoever asks next.

**Evicting is impossible.** `CanonicalResourceManager.unload()` exists and sets
`resource = null` only `if (proxy != null)` — and `getResource()` ends with
`proxy = null`. The reload path is destroyed at first use, so a worker context
grows and never shrinks.

**The shape is not ours to change.** The manager holds `CanonicalResource`
model objects because the validator navigates them. Nothing can be swapped
underneath without owning both.

## What a checker reads

`definitions.definition_element` already says it, because the database's own
checks were written against it: path, steps, parent, min, max, types, fixed,
pattern, binding strength, binding value set, and an invariant row beside it.
Flat columns.

The same rows in heap are arrays and an interned dictionary — a path stored
once rather than per element — which is single-digit megabytes where the object
graph is hundreds.

## The shape

**Two layers**, because a closure divides into a part every tenant of a face
shares and a part that is one tenant's own.

| | what it holds | how it arrives |
|---|---|---|
| **base** | the face's definitional closure: `StructureDefinition` and what its snapshot composes, `ElementDefinition` above all | the face image — already a Postgres dump, COPY per table into a zip, manifest written last and carrying the feed position it was cut at |
| **overlay** | this tenant's profiles, its extensions, its zone's terminology | content sync, as today |

The base is identical for every tenant on a face and is the expensive half. The
overlay is small and particular. This is the shape `FaceBase` and
`TenantContext` already have in object form, arrived at from the data side.

**Three modules — and step 5 found it is two.** The compiler was to read the
packages at build time, expand, snapshot and compile the invariants. All of
that already happens when a definition arrives, once, and the face image
carries the result: the cutter is the compiler, so there is nothing for a
module to do that is not already done. What is left is the two below, and the
first of them exists.

- ~~`fhir/compiler`~~ — **not needed.** The expansion behind
  `definitions.definition_element` is it.
- `fhir/index` — the format and its reader. No toolchain. Built, as
  `core/dbo-fhir-index`.
- `fhir/validate` — the checks over the index. No toolchain. Built, as
  `core/dbo-fhir-validate`, with cardinality in it; the rest is step 7.
- `fhir/validate` — the checks over the index. No toolchain.

## The closure, and what it does not reach

A tenant operates on the types it declares, so what it needs is those
structures, what their elements are composed of, and the value sets bound at
required strength on any of them.

**Composition is followed. Reference is not.** An element typed `HumanName`
needs `HumanName`. An element typed `Reference(Condition)` needs `Reference`,
and `Condition` enters only where the tenant declares it. `contained` typed
`Resource` takes `Resource` and stops. An undeclared type is reached only where
a declared one is made of it.

Every column this needs is stored — `types`, `base_definition`,
`binding_valueset`, `structure_type`, `derivation` — so it is a recursive walk
over rows that exist:

```sql
WITH RECURSIVE seed(canonical) AS (
    VALUES ('http://hl7.org/fhir/StructureDefinition/Patient')
           -- one row per resource type the tenant declares
),
closure(canonical) AS (
    SELECT canonical FROM seed
  UNION
    -- One self-reference, because Postgres allows one: both edges an element
    -- offers come out of the same lateral.
    SELECT reached.canonical
      FROM closure c
      JOIN definitions.definition_element e ON e.canonical = c.canonical
     CROSS JOIN LATERAL (
           SELECT 'http://hl7.org/fhir/StructureDefinition/' || (t ->> 'code')
             FROM jsonb_array_elements(e.types) AS t
            WHERE t ->> 'code' ~ '^[A-Z]'
           UNION ALL
           SELECT e.base_definition WHERE e.base_definition IS NOT NULL
     ) AS reached(canonical)
     WHERE reached.canonical IS NOT NULL
)
SELECT count(*) FROM closure;
```

**Three ways it escapes**, none answered here: an extension is a structure and
a document may carry any the tenant holds; a contained resource may be of any
type unless the tenant declares what it contains; a profile that constrains a
reference target pulls that target in.

## What it reaches, counted

`WhatAClosureReachesTest` walks the carried r5 packages and answers it. It
needs no face root, no database and no worker context — which is worth saying
first, because a closure that could only be counted by the toolchain would be
one the index could not compute either.

| | structures | elements | required value sets |
|---|---|---|---|
| what r5 carries | 389 | 16,150 | 286 |
| the kernel every type reaches | 64 | 462 | — |
| hogwarts' eleven declared types | **78** | **1,164** (7.2%) | 50 (17.5%) |
| every resource declared | 293 | 15,193 (94.1%) | — |

**It is worth building.** A tenant of a realistic shape holds a fourteenth of
the elements a face does.

**The shape is a kernel and a thin edge.** 64 structures — `Extension`,
`CodeableConcept`, `Coding`, `Reference`, `Identifier`, `Period`, `Meta` and
what they are made of — are reached by every resource type there is, and cost
462 elements. Above that each declared type adds one to three structures and
its own elements: `PractitionerRole` 23, `Organization` 30, `Patient` 49,
`Observation` 64, `Encounter` 81, `StructureDefinition` 157. Eleven types add
14 structures and 702 elements to the kernel.

**The win is per tenant, and that decides the shape.** A face whose tenants
between them declared every resource would reach 94% of the corpus. So an index
shared by a face's tenants saves nothing, and the thing that pays is one per
tenant over its own declaration. What a face *can* share is the kernel, which
is 2.9% of the corpus and identical for everyone on it — which is a smaller and
more precise version of the base layer than the table above describes, and the
table is left as written because what arrives in the image is a question about
rows, not about what a process holds in heap.

## What the form costs, measured

`WhatAFlatIndexCostsTest` holds the same 78 structures twice and asks the JVM
which is smaller. Not a context on either side: a context is a face's whole
corpus and would be measuring something else. Differentials are stripped from
both, because the flat side was never asked for them and leaving them in would
measure the omission rather than the form.

| | total | per element |
|---|---|---|
| the closure as model objects | 5,966 KB | 5,249 bytes |
| the closure as flat arrays | **190 KB** | **167 bytes** |
| | **31×** | |

Carried out to whole versions, and **measured rather than multiplied**,
because the per-element rate does not hold at scale — the dictionary is shared,
so the more elements there are the more of their paths and type codes are ones
it has already interned. It gets better, not worse:

| | structures | elements |
|---|---|---|
| r4 | 737 | 13,707 |
| r5 | 389 | 16,150 |
| r6 | 322 | 11,156 |
| **all three, held at once** | | **41,013 elements in 4.3 MB — 108 bytes each** |

108 against the 147–167 measured on one tenant's closure. Against the store's
own recorded baseline for the same content in the model form: **226 MB** for a
face's first tenant and **445 MB** for a second face on top of it.

That is item 024's criterion answered as a question about the form. What it
says must stop being true is that another face costs another few hundred
megabytes. Every version this release carries, together, is 4.3 MB.

A face measuring 225 MB also reconciles with the model side: r5's 16,150
elements at 5,123–5,249 bytes is about 80 MB, and the rest is the
differentials, the terminology resources, the tools package and the context's
own indexes. That reconciliation is the check that the model side is measuring
what it claims to.

**The figures move a little between runs** — the closure has measured 167 KB
and 190 KB, the ratio 31× and 35× — because a forced collection reclaims a
different amount each time. Recorded here is the conservative end of each: the
larger cost, the smaller ratio, following the same rule
`config/memory-baseline.txt` states for its own numbers.

**The flat side holds what a checker reads**, not a subset chosen to flatter
it: path, parent, min, max, type codes, binding and its strength, and the
invariants — 1,341 of them, interned, because `ele-1` is inherited onto nearly
every element there is and one expression stored once is pointed at from a
thousand rows. `fixed` and `pattern` are not held, and the test counts them to
show why: there are **zero** in the closure's base definitions, and an
assertion fails if that ever stops being true. That is a statement about BASE
definitions only — a tenant's own profiles are where fixed and pattern values
actually live, none are in this measurement, and a profile-heavy tenant would
put them back.

**What is not stripped is the documentation.** Each model element carries its
`short`, its `definition` and its `comment`, and the flat form does not. That
difference is left in deliberately, because it is the argument rather than a
flaw in the comparison: a model built for authoring holds the prose a human
reads, a checker never reads it, and declining to hold it is most of what the
flat form is.

**What this still does not say is that anything can check a document.** It
measures the data, not the reader. The 16,150 also does not explain item 023's
92,807 live `ElementDefinition` objects — that figure is every resident version
with its differentials and the tools packages, not one version's snapshots.

## The closure is also what to replicate

A tenant declares the types it operates on and a dependency naming what streams
from an upstream. The second follows from the first: what a tenant needs from a
face is the closure of what it declared, so the definitional dependency is
derivable rather than written, and a declaration derived from another cannot
disagree with it.

What stays written is the rarer case — content a tenant does not operate on,
held for a screen or a lookup, which no closure implies.

**What has already arrived is not streamed again.** An image is loaded FROM and
never merged into, and its manifest records the feed position it was cut at —
so `FaceBringUp` stands the consumer at that cut, and the tenant streams what
the face published afterwards and nothing before. A narrowed closure changes
which rows the image carries; it does not change that the cursor comes with
them. What it does add is a second question for a narrowing to answer: a tenant
that later declares another type reaches definitions the cut did not carry, so
it needs them fetched rather than waited for — which is the catch-up question
item 021 has now answered: a snapshot of what the manifest names plus the
cursor it stood at, never a replay of the upstream's history through a
predicate.

This shrinks the database, the expansion, the image and the index from one
derivation. It needs the closure computed by whoever holds the definitions —
the face root, or the cut publishing a manifest of canonicals per resource
type — because a tenant cannot compute the closure of what it does not yet
hold. The mechanism is
[item 021](../021-asking-the-store/README.md)'s filtered dependencies, with the
filter computed instead of declared, and its four questions **are answered**
there in a form this case can use: a filter is a manifest of names rather than
a predicate that travels, so computing it at the face root is selection rather
than one tenant executing another's query; it closes over grains; catch-up is a
snapshot and a cursor; and a definition that leaves a narrowed closure is
retracted down the delete path. The one left open there is the one that bites
here hardest — a tenant narrowing below what its stored documents were
validated against is to be refused at declaration, not discovered at the next
write.

## How it stands beside the database

The database is the specification and the source; the index is a projection of
it.

One expansion feeds both: the cutter writes `definition_element`, and the index
is those rows in arrays. The SQL checks stay, because they are what
`verdict: database` uses and what an embedded deployment with no Java validator
can still answer from. The index is the in-process path, which costs no round
trip per document.

`TheTwoAnswersAreComparedOverTheVersionIT` keeps them honest: a third answerer
joins the comparison and is done when its divergences are the five already
explained.

## What makes it stick

**The ratchet this section first proposed does not work, and the distribution
says why.** It was to assert that `dboRuntimeModules` names no
`hapi-fhir-validation` and no resource jars. Both halves are wrong:

- There is **no `validation-resources` jar in the distribution at all.** The
  definitions arrive as `.tgz` packages fetched at build time and embedded in
  `dbo-fhir-element`, whose jar is 66 MB of which 65 MB is packages — 34.6 for
  r6, 16.3 for r5, the rest terminology and tools.
- Dropping the validator would not remove the ability to build a context.
  `org.hl7.fhir.validation` is 1.1 MB and `hapi-fhir-validation` 0.3 MB, but
  `SimpleWorkerContext` and `elementmodel.Element` sit in
  `org.hl7.fhir.r5-6.10.2.jar` **beside 1,517 model classes**, and those model
  classes are what version conversion needs. HL7 ships them as one artifact, so
  the class that costs the memory cannot be dropped without dropping the model.

**The ratchet that does work is the packages.** A serving node that carries no
definition packages cannot populate a context, whatever classes it holds:
`SimpleWorkerContext` with nothing to load is a class, not a graph. It is
assertable in the same shape as the branding check — `dboRuntimeModules` is the
bundle list, and a green build proves the property rather than a counter
suggesting it. It takes 65 MB out of a distribution as a side effect. And, the
part that decides whether any of this is worth building, it does **not** wait
on version conversion or on transaction bundles.

That is reachable only when nothing on the serving path BUILDS a context.
[Item 024](../024-definitions-out-of-the-heap/README.md) took that to its own
limit — eight of nine moves, no serving reach left, the toolchain not run where
a type declares the database — and stopped at a table of five serving paths a
tenant cannot declare its way out of, because they are what the store does
rather than what a type asks for. **Three of those five are this item**:
parsing and composing a document, FHIRPath compiled once rather than per
document, and the element half of terminology. That is the relation between
the two items — 024 is not superseded by this one, it is completed by it.

The other two — version conversion and transaction bundles — need the MODEL
classes and not a populated context, which is why taking the packages off a
serving node does not wait for them. That distinction is the whole reason the
corrected ratchet is reachable and the original was not.

## Something reads it

`AThirdAnswererChecksCardinalityTest` checks documents against the index. No
toolchain and no database: it is a token scan over the bytes against flat rows,
which is the whole shape the design proposes.

Cardinality first, because `dbo.cardinality` already answers it, so a third
answerer joins a comparison that exists rather than starting one.

| | |
|---|---|
| conformance resources checked | 6,532 |
| nodes the walk descended into | 276,258 |
| deepest path reached | 5 segments |
| faulted | **0** |

**Both directions, because one of them alone proves nothing.** A checker that
never speaks faults nothing, so the corpus is only half of it: the other half
puts an `Observation` with no `status` and no `code`, a `Patient` with two
`gender`s, a `Patient` with many `name`s, and an `Observation` whose
`valueQuantity` has to be recognised as `value[x]`, and asserts it reports the
first two and stays silent on the last two.

**The corpus is the release's own conformance resources**, not the
specification's examples — the core package ships none, they are a package of
their own this store does not carry. These are better: thousands of documents
written by the people who wrote the definitions, and four of the seven types
are ones the sample world's tenants declare.

**It answers at every depth, and the walk changes structure where the document
does.** A backbone — `Patient.contact` — is defined inside its own resource, so
the walk stays in that structure and goes a path deeper. An element typed as a
datatype — `Patient.name` is a `HumanName` — is defined in that type's own
structure, so the walk moves there and starts again at its root. That
difference is what makes a nested maximum per parent instance rather than per
document, and it is asserted both ways: two names in one contact is faulted,
two contacts holding one name each is not.

**The descent is counted, because a clean corpus is also what a checker that
does nothing reports.** A walk that stopped at the root would pass the corpus
test in silence. 276,258 descents and five segments at the deepest are what
say it did not.

**What it does not follow is a contained resource.** The element says only
`Resource`, and walking a contained Patient against `Resource` would find its
own elements undeclared and be right about nothing. What a document may contain
is a question about the tenant's declaration rather than about cardinality.
Zero false positives is still not the same as no false negatives, and only the
first is claimed.

## Two answers, compared

`TheThirdAnswererAgreesWithTheToolchainTest` puts the same documents through
the index checker and through `ElementPayloads`, which is what decides today.
A checker measured only against documents it was written beside is how a second
implementation quietly becomes a different specification.

**On what is missing, they agree exactly.** Over 402 documents the toolchain
reported 3 minimum violations and the checker reported the same 3 — none
missed, none added. The comparison had to read the toolchain's *message*
rather than its location to get there: a missing `Observation.status` is
reported at `Observation`, so comparing locations would have compared notation
and collapsed two missing children of one parent into one.

**On what is repeated, they do not — and the checker is right.**

| document | the toolchain | the checker |
|---|---|---|
| `{"gender":["female","male"]}` | accepts, 0 errors | reports `Patient.gender` |
| a contact with two `name`s | refuses, but for `pat-1` | reports `Patient.contact.name` |
| a name with two `family`s | accepts, 0 errors | reports `Patient.name.family` |

An element the definition allows once, arriving as an array, is **discarded in
silence**: the three round-trip as `{"resourceType":"Patient"}`,
`{"contact":[null]}` and `{"name":[null]}`. The write is accepted and what is
stored is not what was sent. Where the toolchain does refuse — the contact case
— it refuses incidentally, because `pat-1` fires on the contact the data
vanished from, not because anything noticed the name.

**This is a defect on the write path now**, not a fact about a design that does
not exist: `ElementPayloads.read` is what every write goes through. It is
recorded here because this is where the evidence was found, and it is a defect
whether or not any of this item is ever built.

It is also the case for a second answerer put more sharply than any memory
figure. The argument for one had been that it is cheaper to hold. The argument
now is that it sees a document being silently emptied.

## The critical path

The sections above are evidence, in the order it was gathered. This is the
order the work goes in, and what each move needs before it can start. Written
out because the item had measurements and a design and no sequence, so there
was no way to tell what was next from what was merely undone.

| # | Move | What it buys | Needs |
|---|---|---|---|
| 0 | ~~**Count what a closure reaches**~~ **Done.** 78 structures and 1,164 of r5's 16,150 elements for hogwarts' eleven types; a fixed kernel of 64 and one to three per declared type | the decision that narrowing is worth anything at all, and that it is per tenant | nothing — the carried packages, which any machine can read |
| 1 | ~~**Measure what the form costs**~~ **Done.** 31× on one closure; all three carried versions together are 41,013 elements in 4.3 MB against a recorded 226 and 445 | the claim that the cost is the form and not the content, stated as a quantity | 0 |
| 2 | ~~**Something reads it**~~ **Done.** Cardinality at every depth, 6,532 documents, no false positives, 276,258 descents | the answer to "a cheap store nothing can read is not a step towards anything" | 1 |
| 3 | ~~**Compare it against the toolchain**~~ **Done.** Minima agree exactly; maxima diverge and the toolchain is the one at fault | the check that a second implementation has not become a second specification — and, unplanned, a data-loss defect on the write path | 2 |
| 4 | ~~**Decide whether the spike becomes modules**~~ **Taken: yes**, and argued below | nothing by itself; it was the gate, and everything above was deliberately done without it so the decision rested on figures | 0–3, which is why they came first |
| 5 | ~~**Build the index from ROWS rather than packages**~~ **Done.** `core/dbo-fhir-index`, one bundle importing nothing but the JDK; 750 elements over a face root's closure, element for element against the packages, **no divergence** | the base-and-overlay split, the face image path, and a tenant's own profiles — none of which a package can supply. And, unplanned, one of the three modules turned out not to be needed | 4 |
| 6 | ~~**The database leg**~~ **Done.** `core/dbo-fhir-validate` stands in `TheTwoAnswersAreComparedOverTheVersionIT` against `dbo.cardinality`: 258 documents, 21,267 descents, no divergence — and one divergence found on purpose, where the index reaches further | the word "third" in "third answerer", which was a plan until this. NOT reachability: comparing is not being asked | 5, and a tenant |
| 7 | **The rest of a checker** — fixed and pattern values, slicing, required bindings. **Fixed and pattern are DONE** and agree with the database over a tenant's own profile; the other two are costed rather than built, and both came out small — 598 codes, one predicate form | a checker that covers what a tenant's own profiles actually say, rather than what base definitions happen not to | 5, because profiles arrive as rows |
| 8 | **FHIRPath compiled at the cut**, into rows, as `definition_parameter` already is in part | invariants, which are the largest thing the toolchain still answers alone | 5 |
| 9 | **The payload path without `elementmodel`** — read and write EVERY type from JSON, not only check it. **Spiked, and it holds**: 6,532 documents and 86 MB in and out unaltered with no context, and 91.9% of search expressions are plain paths | the last reason a serving node builds a context at all; it is row one of item 024's foot and belonged in neither item's steps | 5, 7 |
| 10 | **Derived subscriptions** — the closure as what to replicate, the filter computed rather than declared | the database, the expansion, the image and the index all narrowed from one derivation | 5, and [item 021](../021-asking-the-store/README.md)'s answers, which are written |
| 11 | **The distribution ratchet**: a serving node carries no definition packages | the 225 MB, 65 MB of jar, and the property that a context cannot be POPULATED rather than merely is not | 7, 8, 9 |

**Nothing on it is a megabyte until the last one**, which is the same shape
item 024's path has and for the same reason: an index held beside a context
saves nothing, because the context is held whole either way. Steps 0 to 3 moved
no memory and were never going to. What they bought is the right to decide step
4 on measurements instead of on an argument, and one of them found a defect
that has nothing to do with whether this is ever built.

## Step 4, taken

**Yes — the spike becomes the three modules.** The test this item set for
itself was that the last move be reachable, because a form that is cheaper and
can never replace what is expensive saves nothing it can keep. Reading the
distribution to check that is what settled it, and it changed two things.

**The ratchet was aimed at the wrong thing, and the right thing is easier.**
Dropping the validator jars would not have made a context impossible, because
`SimpleWorkerContext` ships beside the 1,517 model classes that version
conversion needs. Dropping the definition PACKAGES does, and conversion and
transaction bundles — the two rows at item 024's foot this item never claimed —
need the model classes rather than a populated context. So they do not block
it. The last move stopped depending on the two pieces of work nobody could see
the end of.

**A move was missing, and it is the largest one left.** Nothing in either item's
steps replaced `elementmodel` on the PAYLOAD path. A checker that reads the
index answers what a document is wrong about; it does not parse or compose one,
and `ElementPayloads` parses every write into an `elementmodel.Element`, which
needs a populated context whether or not anything validates. So the 225 MB
survives a finished checker. That is now step 9, and the plan was wrong to go
to the ratchet without it.

**What the decision rests on**, stated so it can be argued with: 31× on the
form and 4.3 MB for every carried version against a recorded 226 and 445; a
closure of 7.2% that narrows per tenant; a checker that agrees with the
toolchain exactly on what is missing; and a data-loss defect the toolchain does
not report and this one does. Against it: three modules and a second
implementation of element checking, standing, forever.

**What would still reverse it** is step 9. If the payload path cannot leave
`elementmodel` — if parsing and composing every type from JSON turns out to
need the definitions in a form only a context gives — then steps 5 to 8 are a
faster cardinality check and nothing more, and the honest thing then is to stop
and say so rather than to keep building toward a ratchet that cannot fire.
Step 9 should therefore be attempted EARLY rather than in its dependency order,
as a spike against one type, before the modules carry much.

## Step 6, the database leg

`core/dbo-fhir-validate` — the checks over the index, in a bundle that imports
the index, the vocabulary a finding is carried in, and the JDK. **Not a JSON
library either**: a document is read by a forty-line scanner of its own,
because another bundle already embeds one privately and a second exporter
would be a split package, and because the property this module exists to have
is that a node holding it carries nothing. Numbers and booleans arrive as the
text that was written and are never interpreted — nothing here asks what a
value means, and reading a decimal as a double is how a written precision
disappears in silence.

**The word "third" is now a fact.** `CardinalityCheck` stands in
`TheTwoAnswersAreComparedOverTheVersionIT` beside the two that were already
there.

| | |
|---|---|
| documents compared | 258 |
| documents either answerer faulted | **0** |
| nodes the walk descended into | 21,267 |
| deepest path | 5 segments |
| divergences | **0** |

**The silence is asserted as silence.** Both answerers say nothing about every
one of those documents, so that half is an agreement about silence — worth
having, because it is the claim that neither faults the specification's own
conformance resources, and worth naming, because it is not the claim that they
say the same thing when something IS wrong. A second half asks that: a required
element absent, an element allowed once and sent twice, one backbone instance
holding two of something 0..1, two instances holding one each. They name the
same elements on all of them. And the descent count is what keeps the first
half from being a checker that never ran — a walk that stopped at the root
would report the same clean corpus.

**One divergence, found on purpose, and the index is the one that reaches
further.** `Patient.name.family` sent twice: the index reports it and the
database reports nothing. That is the promise the database makes rather than a
defect in it — a definition is expanded from the profile's own snapshot, and
Patient's snapshot names `Patient.name` as a `HumanName` and stops, so a
datatype's insides are in the rows only where a profile constrains them. The
index holds the CLOSURE, so it enters `HumanName`'s own structure and answers.

That is the first argument for a third answerer that is not about memory. It
was going to be cheaper to hold; it turns out also to answer where one
profile's rows stop.

**And it is still reached by nothing.** The line in `config/reach-ledger.txt`
was expected to come out at this step and does not, which is worth saying
plainly: comparing is not being asked. What takes it out is a write judged by
this answerer, and no step below is that yet.

## Step 7, and what it found by being run against a profile

**Fixed and pattern are built.** The index carries what a profile pinned, and
`ElementChecks` answers equality and containment over it — jsonb's own
relations, because the database answers the same two questions with
`IS DISTINCT FROM` and `@>` and the whole case for a third answerer is that it
says the same thing.

The comparison is on the tenant that authors profiles, against the same kind
of fixture the database's side is already proven on: a pinned identifier
system and a pinned marital status. Five documents — exactly what is pinned
with more beside it, the wrong system, a status the profile does not state,
both wrong at once, and two identifiers of which one is wrong. Both answerers
name the same elements on all five.

**It could not have been proven a step earlier, and that is the point of the
step.** A version states minima and maxima everywhere and pins almost nothing:
the closure this profile sits in has **zero** fixed or pattern values in its
base structures. A checker proven only against what HL7 publishes would never
have run this code at all.

**Running it against a profile found two defects, and both were silent.**

- **The walk left the profile's own structure.** On reaching
  `Patient.identifier` it moved into `Identifier`'s structure on the strength
  of the type — and walked straight past `Patient.identifier.system`, which is
  where the tenant's fixed value is. The rule is now the snapshot's rather
  than the type's: where a structure enumerates anything below an element,
  that is what applies and the walk stays. A backbone was already the same
  case, which is why this looked right for a whole step.
- **The root path was taken from the canonical's last segment.** That is the
  type for a base definition and the profile's NAME for a profile, so the walk
  began at `IndeksIkPatsient` and found nothing in a document whose paths all
  begin `Patient`. It reports zero findings, which reads exactly like a clean
  document. The index knows the root because the rows say it, so nothing
  guesses now.

Neither would have been found by cardinality over base definitions, and
neither failed loudly. That is the second time in this item that the thing
which looked finished was answering about nothing.

## What the other two checks would cost

Counted rather than guessed at, because a required binding needs CODES — which
are not definitions — and a slice needs a PREDICATE evaluated, which the
database gets from Postgres and anything in heap would have to do itself.

**A required binding is not an expansion problem.** Over a face root's closure:

| | |
|---|---|
| elements with a required binding | 58, naming 43 distinct value sets |
| of those, held by the tenant | **43** |
| the shape of all 43 | a whole code system, no filter and no nesting |
| codes behind them | **598** |

Six hundred strings beside the index. That is the answer: whatever answers a
required binding in heap holds a few hundred codes, not an expansion engine —
for a tenant of this shape, and the number is per closure, so a tenant
declaring more types is the case to measure next.

**Two traps on the way there, and both gave a confident wrong answer first.**
Every one of the 43 bindings names a canonical WITH its version —
`…|4.0.1` — and the terminology is keyed without one, so asked as written the
tenant holds none of them. And a compose is stored under this store's own
names, `includes` and `excludes`, not FHIR's singular ones, so read with the
wrong names every value set is a shape nothing recognises. Each read as a
clean, plausible measurement.

**A slice needs one form.** Over everything the tenant holds rows for:

| | | |
|---|---|---|
| one plain step | 12,675 | 90.6% |
| several, a choice | 366 | 2.6% |
| with a predicate | **203** | **1.5%** |
| no step at all | 747 | 5.3% |

And every one of the 203 is equality, optionally joined by `and`, over a path
of one to three segments — 174 of them the bare `? (@."x" == "y")`. Not a
comparison, not a regex, not an existence test. So slicing in heap is an
evaluator for a single form, and a test fails the moment a sixth shape
appears and says which it is.

## Step 9, spiked out of order

The move the decision rests on, attacked first, because if it fails steps 5 to
8 are a faster cardinality check and nothing more.
`WhatThePayloadPathWouldNeedTest` asks its two halves.

**A document in and out: 6,532 documents, 86 MB, none altered.** No context
anywhere. The bar was not that a JSON tree round-trips — that is trivially
true — but that nothing the wire carries is lost over the release's own
documents: order, repeats, the `_field` form a primitive's extensions arrive
in, and a decimal's written precision, which FHIR makes significant and which a
parser reading numbers as doubles silently destroys. `1.500` comes back
`1.500`, asserted on its own because the corpus might not contain one and it is
the failure that would be silent.

**And it needed no type knowledge at all.** The round-trip does not consult the
index once. Reading and writing a FHIR document losslessly is a JSON problem;
the definitions are needed for CHECKING it and for extracting an envelope from
it, which is a smaller claim than the payload path looked like it was making
and makes this half of step 9 nearly free.

**The envelope: 91.9% of expressions are a plain path.** Of r5's 1,231 search
parameter expressions:

| shape | count | |
|---|---|---|
| a plain path | 1,077 | 87.5% |
| plain paths joined by `\|` | 54 | 4.4% |
| a path with `ofType()` | 54 | 4.4% |
| a path with `where()` | 29 | 2.4% |
| a path with a type cast | 8 | 0.6% |
| an extension lookup | 3 | 0.2% |
| something else | 6 | 0.5% |

A walk over a JSON tree with the index for types evaluates the first two, which
is 1,131 of 1,231. **The remaining 8.1% is what is not answered**: a hundred
expressions needing `ofType`, `where`, a cast or an extension lookup. They are
narrow forms rather than arbitrary FHIRPath, so a small evaluator is plausible
and unproven — and a tenant that declares eleven types never reaches most of
them, which is a reason to size this against a tenant's parameters rather than
against a version's.

**So the decision at step 4 stands.** What would have reversed it was the
payload path being unable to leave the element model. It can.

## Step 5, built

`core/dbo-fhir-index` — one bundle, one exported package, and an
`Import-Package` of `java.lang`, `java.sql`, `java.util` and `javax.sql`. Not
the toolchain, which is the point; not the JDBC driver either, because the
rows are read through `java.sql` against whatever `DataSource` the caller
already has. A process holding this holds a definition set and no worker
context.

**The source moved and the answer did not.**
`AnIndexBuiltFromTheRowsSaysWhatThePackagesSayIT` builds the index out of
`definitions.definition_element` on a face root and puts it beside one built
out of the packages the spike read, element for element.

| | |
|---|---|
| structures in the closure | 44, and both sides hold all 44 |
| elements compared | 750 |
| **divergences** | **0** |
| the dictionary | 1,034 words for the 4,562 strings the index answers with |

**And the closure is now walked over rows.** One recursive statement over
columns that already exist, seeded with the types the tenant declared: 44
structures of the 740 that face root holds rows for, **5.9%**. That is the
same claim the package-side count made — 7.2% for eleven types on r5, 5.9%
for six on r4 — arrived at from the side the design actually uses. Composition
is followed and reference is not, and the test says so in both directions:
`HumanName` and `CodeableConcept` are reached, `Device` is not, though
`Observation.subject` may point at one.

**What a package could never have given** is the reason the move was on the
critical path at all, and each of the three is now reachable: a tenant's own
profiles are rows and were never in any package; a face image carries rows;
and what a tenant holds is what arrived rather than what HL7 published, which
is what makes a closure a narrowing rather than a filter over a corpus held
anyway.

**Three modules are two.** `fhir/compiler` was to read the packages at build
time, expand, snapshot, compile the invariants and write the index. Every one
of those already happens — it is what the expansion does when a definition
arrives, once, and what the face image carries afterwards. The cutter is the
compiler, so the module is not written; `fhir/index` exists and `fhir/validate`
is step 7. That is a correction to the design above, made by building the
thing rather than by reading it.

**Nothing reads it yet, and that is recorded rather than implied.**
`config/reach-ledger.txt` names `DefinitionRows` as NOT REACHED with the
reason, because an index nothing reads saves no megabyte and a line in a
ratchet is harder to forget than a paragraph here. Step 6 is what takes the
line out.

## What is not known

~~**How large a closure is.**~~ **Answered**, and above: 7.2% of a version's
elements for a tenant of hogwarts' shape. It was thought to need a face root,
which this machine cannot populate; it needed the carried packages, which any
machine can read.

~~**What an element costs in the index.**~~ **Answered**, and above: 167 bytes
against 5,249, a factor of 31, with the invariants held and the comparison made
fair in both directions.

~~**Whether anything can read it.**~~ **Answered for one check**: cardinality,
at every depth, over 6,532 documents, no false positives, faults found. What
that does not settle is the rest of `fhir/validate` — terminology, FHIRPath,
slicing, fixed and pattern values — and the risk has moved rather than gone: a
second implementation still either earns its keep or becomes the thing this
item says it must not be.

**Whether the THIRD answer agrees.** Two of them now do, on minima, and
diverge on maxima with the toolchain at fault. The database has not been asked:
`TheTwoAnswersAreComparedOverTheVersionIT` is where that happens and it needs a
tenant. What stood in the way was that the index was a spike in a source set
with no database, and that is gone — an index is built from a tenant's rows.
What is left is to put the checker over it and stand it in that comparison,
which is step 6, and until then the word "third" is a plan rather than a
fact.

**How far the agreement goes.** Minima are one check of one kind. Terminology,
FHIRPath, slicing, and fixed and pattern values are each a place the two could
disagree and have not been asked to.

**Terminology.** `validateCode` and expansion are not element checking. The
`term_*` tables answer some of it, and nothing has compared them.

**FHIRPath.** Compiling once and caching is the whole win; rebuilding per
document would lose more than the form saves.

## What this is not

**Not a FHIR validator for the world.** It answers what this store answers, and
what it does not cover is named in item 024: a StructureMap checked as a
program, an expression the toolchain cannot evaluate either, an identifier
inside an example value the walk cannot reach.

**Not a second implementation to maintain.** The database checks are the
specification and the comparison harness is the test. Three answers nobody
compares would be worse than two that are.

**Not conversion.** `VersionConvertorFactory_40_50` is generated rules over the
two model graphs, needs no worker context, and holds tens of megabytes rather
than hundreds. There is no specification to copy and no memory to win.
