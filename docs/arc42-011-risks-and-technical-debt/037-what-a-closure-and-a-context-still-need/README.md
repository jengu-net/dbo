**Open, and not started. Three things are left of taking the FHIR toolchain's
definitions out of a serving process. A face image is cut per face, so a
tenant that streams only its closure still loads an image of the whole
corpus. A tenant that narrows below what its stored documents were validated
against is accepted. And a serving process still builds a worker context
wherever a face root populates a face or a tenant with no face base expands
what it holds, because the validator still ships in the distribution.**

# What a closure and a context still need

## Where it stands

What is built is described in [the payload seam](../../arc42-008-crosscutting/the-payload-seam/README.md#a-write-judged-from-the-index):
a tenant may declare `indexFace`, and its writes are then parsed, checked and
indexed from flat rows over the closure of its declared types, with no worker
context. A dependent asks its face for that closure every round. A serving
node carries no definition packages: they are a fragment a node installs only
to populate a face, and `config/carried-packages.txt` reads zero.

What a context costs is one per version per process: 226 MB for a face's
first tenant and 445 for a second face. A tenant on a face comes up from the
face's image and builds none. `ElementVersion.contextBuilds()` is the measure,
and done is zero for a serving process. `WhatTheLoadedSpecificationCostsIT`
asserts the weaker form today: one context per face however many tenants
serve on it.

**This is also what fills a test JVM.** Over half the live heap of a suite
holding three faces is that corpus — the model objects, their strings and
those strings' arrays — resident by design and not garbage, which is why the
test heap is 3g and the stories' JVM, which expands every face the world
declares, is 4g. `WhatTheSuiteLeavesBehind` records the floor after each class
under `-Ddbo.heap.attribute=true`. Splitting a suite by face rents the
megabytes; needing fewer contexts gives them back.

## 1. The face image, cut per closure

**Per face is the wrong grain once dependents narrow.** A tenant of
hogwarts' shape reaches 7.2% of r5's elements, and a face whose tenants
between them declared every resource reaches 94%. An image cut per face
carries very nearly the whole corpus however narrow its tenants are, so the
derived closure shrinks the feed and nothing else; the database, the
expansion and the image are what it is for.

**And per face becomes wrong, not only large.** The first tenant to want a
face cuts it. With a closure per dependent, that tenant cuts its own closure,
and the next tenant on the same face with wider declared types loads an image
missing rows it needs. Nothing reports it: the index judges a document
against what the tenant holds, so missing structures mean documents accepted
unchecked.

**The design: an image is accepted on coverage, not equality.** The image's
manifest already refuses by name an image from another release. It gains the
closure it was cut over, keyed by a digest over the manifest of names
(`DefinitionRows.manifestFor` computes it), and a tenant accepts an image
whose closure contains the names its own declared types reach. Equality would
cut one image per distinct set of declared types and share none; containment
shares every image with every tenant narrower than it. A face-wide image is
the widest closure there is, so per-closure falls back to per-face rather
than failing. Cutting stays lazy: the first tenant wanting a closure nobody
has cut pays for it.

## 2. Narrowing below what a tenant holds is refused at declaration

A dependency's closure is asked for again each round, and a record that
leaves it is retracted down the ordinary delete path. A definition that
leaves a narrowed closure may be what a stored document was validated
against, so narrowing below what a tenant holds makes its own documents
uncheckable. That is to be refused when the declaration is read, naming what
it would orphan, and not discovered at the next write. The same rule is
recorded as a gap in [declared rules](../../arc42-008-crosscutting/declared-rules/README.md#what-this-costs).

## 3. A runtime that cannot build a context

The packages are out of a serving node. The validator is not:
`hapi-fhir-validation` is embedded in `dbo-fhir-stack`. A node that carries
neither cannot build a context at all, which a ratchet over the distribution's
bundle list could hold. What still asks the toolchain on a serving path:

| still needs it | what would answer instead |
|---|---|
| expanding a face, and a tenant with no face base | the index face cannot bootstrap: it reads rows that expansion makes, and expansion runs the toolchain. Either expansion stops needing it or it moves into the image cutter, a release-time tool |
| terminology — `validateCode`, expansion | the `term_*` tables answering in full, including how much of itself a held system is (`content`), which `definitions.term_system` has no column for |
| the FHIRPath the in-heap answerer declines | 31.6% of compiled rules need comparison, matching or a filter; they run in the database today |
| transaction bundles | `ElementBundles` composes through the model |
| version conversion | a process of its own; a converter holds two model graphs and no definitions |

Each is a path a tenant cannot declare its way out of, because it is what the
store does rather than what a type asks for. Taking the validator out of the
distribution before all of them are answered breaks every deployment that
relies on one.

## Traps

- **An index with no rows accepts everything.** A tenant declaring the index
  face with no definitions is served by the loaded specification, and the log
  says why. Anything that narrows rows — an image, a closure, a retraction —
  has to keep that guard true.
- **A filter cannot report what it removed.** A manifest computed once never
  learns of a definition published later inside a dependent's closure, so the
  closure is asked for each round.
- **The unit of saving is the process.** One tenant needing a context makes
  the corpus resident for every tenant in the JVM, so a per-tenant figure
  cannot show a context going away.
- **Measure with the face's rows stated.** A tenant on a face image builds no
  context with the index face or without it; a measurement that does not say
  whether the rows were already there cannot tell the two apart.

## How it is proven

```bash
./gradlew :core:harness:test --tests '*WhatTheLoadedSpecificationCostsIT' --tests '*AWriteIsJudgedFromTheIndexIT'
./gradlew :core:harness:test --tests '*WhatAServingNodeCarriesOnlyFallsTest'
```
