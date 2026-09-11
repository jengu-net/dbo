# US-DBO-STANDARD-MOVES — data outlives the shapes it was written under

> A record written in 2026 has to still be readable in 2029, when the
> profile that shaped it has moved twice and nobody who wrote it still
> works here. Without an answer to that, "upgrade" means either a
> migration ceremony or a silent misreading, and the second one is worse
> because nothing announces it.
>
> The store's answer is one decision: **what an object was validated
> under is a fact of the accept event**, recorded beside the payload
> rather than inside it. Everything a migration needs follows from
> that — stock countable per version, findable by bound, convertible in
> place, and data newer than the pack understands refused rather than
> half-read.

## The scene

The clinic carries its own profile. It is ordinary content, not
configuration, so revising it is a write rather than a release.

## What it was validated under, and what it claims

An accepted observation records the pack version it was validated under. The
conformance claim it makes stays an **unversioned** canonical, because
conversion moves an object's shape and never its identity.

Those are two different facts and the store keeps them apart. Echoing the
served document back does not accumulate a second stamp: the stamp is derived
on accept, not carried by the caller, so a client that politely returns what
it was given does not slowly grow the record.

## The pack moves

The profile advances. The next accept moves the stamp with it, and the version
written under the old pack keeps its own stamp in history — because what the
record claimed last year is a fact about last year, and a store that
overwrites it cannot answer questions about why somebody decided what they
did.

## What that makes possible

Stock is countable per profile and version, which is what makes a migration a
plan rather than a hope. It is findable by version bound, so "what do I still
have below the current major" is a query rather than a scan somebody writes
by hand. And it is convertible in place, resumably, as an ordinary operation.

Two axes are deliberately never conflated. The storage format the bytes are
held in is one thing; the pack version an object was validated under is
another. An R4-to-R5 conversion changes no shape, and a profile revision
changes no bytes.

## What it refuses

A shape whose version has no leading integer is refused **when the shape
arrives**. Only a major is breaking-with-converter, so a version nothing can
order would stamp stock no bound could ever match — and that is discovered
mid-migration rather than at the door.

Data stamped above what the tenant understands can be stored and cannot be
read. Every arrival path converges at serving, so one rule covers all of them
including lanes not yet invented. The honest consequence is stated rather than
hidden.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED` | Every object accepted through the face carries, as a fact of the accept event stored beside the payload, the version of each declared pack profile it was validated against; re-accepting replaces the stamp, never accumulates it. | PROVEN |
| `REQ-DBO-SHAPE-STAMP-IS-DERIVED` | The shape stamp is a per-version fact column in state and history; the envelope's shape dimension is rebuilt from it on reindex, and every history version serves its own stamp. | PROVEN |
| `REQ-DBO-SHAPE-SERVED-BESIDE-THE-CLAIM` | The stamp is served in meta as the published urn:dbo:shape extension beside the unversioned meta.profile canonical; an echoed copy of the engine's own stamp is dropped at accept, so stored bytes stay the author's claims. | PROVEN |
| `REQ-DBO-SHAPE-MIRRORED-KEEPS-ITS-STAMP` | The stamp travels the sync wire beside the storage-format version, so a mirrored copy keeps the stamp of the store that validated it; only an authored accept restamps. | PROVEN |
| `REQ-DBO-SHAPE-UNPARSEABLE-VERSION-REFUSED` | A pack shape whose version has no parseable leading integer major is refused at accept, by name — it would stamp objects no version bound can ever match. | PROVEN |
| `REQ-DBO-SHAPE-STAMP-OUTLIVES-ITS-PACK` | A stamp is a fact about a past accept: withdrawing or re-numbering a pack version leaves stock stamped with it findable, countable and convertible. | PROVEN |
| `REQ-DBO-SHAPE-STOCK-COUNTED` | The tenant inventory counts shape stock per type, profile and stamped version — including objects that declare a profile and carry no stamp at all — so the same report runs before and after a migration and diffs line by line. | PROVEN |
| `REQ-DBO-SHAPE-QUERYABLE-BY-VERSION` | Objects are searchable by shape-stamp bound — below, or at and above, a stated major for a stated profile — pageable like any search, on every serving surface. | PROVEN |
| `REQ-DBO-SHAPE-RESHAPED-IN-PLACE` | The store converts stamped objects to a target major in place: each rewrite is an ordinary versioned write, so history keeps the pre-conversion object with its own stamp and the new version carries the new one. | PROVEN |
| `REQ-DBO-SHAPE-RESHAPE-RESUMABLE` | A reshape is paged and rate-bounded, hands back a cursor and its counts, and a re-run finds only what is still behind. | PROVEN |
| `REQ-DBO-SHAPE-REFUSED-OBJECT-LEFT-BEHIND` | An object no converter covers is named and left behind rather than stranding the rest; the run reports it and the next run tries again. | PROVEN |
| `REQ-DBO-SHAPE-HANDBACK-CLAIMS-WITHOUT-LOCKING` | Claiming stock for conversion elsewhere writes nothing and holds nothing: the version check on the way back is the only guard, so an abandoned claim strands no data and a duplicated one converges. | PROVEN |
| `REQ-DBO-SHAPE-HANDBACK-KEEPS-THE-DISCIPLINE` | Converted forms handed back are re-accepted through the face — validated, re-stamped, version-checked — and accounted exactly as the in-process lane accounts, so the hardest conversions do not run with the least discipline. | PROVEN |
| `REQ-DBO-SHAPE-NEWER-DATA-REFUSED` | An object stamped above what the tenant's pack declares for that shape is refused on every read — naming the object, the stamp and the pack's version — never served best-effort and never silently omitted from a search. | PROVEN |
| `REQ-DBO-SHAPE-TOO-NEW-IS-ITS-OWN-ANSWER` | The refusal is a distinct, documented error a consumer can gate on, told apart from a fault, a permission and a malformed request. | PROVEN |
| `REQ-DBO-VER-CONCURRENT-VERSIONS` | Tenants (and domains within a tenant) on different FHIR versions run concurrently in one container. (R6) | PROVEN |
| `REQ-DBO-VER-TRANSITION-BY-CONVERTERS` | Moving a tenant between FHIR versions is converters plus reindex, not a data migration ceremony. | PROVEN |
| `REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE` | A face brings the definitions it validates and extracts against. Bringing a tenant up fetches nothing over the network and needs no writable cache outside the store's own state. | PROVEN |
| `REQ-DBO-VER-DEFINITIONS-INDEXED-WITHOUT-THE-TOOLCHAIN` | A definition — structure, search parameter, value set, code system, map — is indexed from its JSON along the version's own search parameters, with no worker context, and the index is the one the toolchain would have written: identical over every definition every carried face publishes. It exists because the toolchain needs the version's definitions to parse one, and a definition arriving at a tenant is exactly what the tenant does not hold yet. | PROVEN |
| `REQ-DBO-VER-A-DEFINITION-IS-EXPANDED-WHEN-IT-ARRIVES` | A structure arriving at a tenant by any path — a feed, the face, a restore — is expanded once into element rows the database checks against: what may stand at each element, how often, what it must equal or contain and what it is bound to, located by a jsonpath with the choice keys and slice members already resolved. Bringing a tenant up reads those rows; nothing expands a definition per boot or per write, and the rows are rebuilt from the record by a reindex like any other projection. | PROVEN |
| `REQ-DBO-VER-THE-FACE-SQL-SHIPS-WITH-THE-RELEASE` | The functions a tenant's database answers with are installed into its own schema by the dbo release that carries them, and arrive no other way — never through a chain, a restore or a feed, because a function that could be replicated would be a way to run code on a tenant by writing to a stream. They are plain SQL with no server extension, so they run wherever the store runs, and a bring-up whose release carries the SQL already in place installs nothing. | PROVEN |
| `REQ-DBO-VAL-TIER-ONE-IS-ANSWERED-IN-THE-DATABASE` | The database answers, from the rows a definition was expanded into, what a toolchain answered from an object graph: how often an element may occur — counted inside the parent it occurs in — what it must equal or contain, whether a coded value is in the value set its binding names, and whether a reference points at a record this store holds. The last two are joins, to the terminology and to the records, which is why they are answered here at all. What nothing here can judge is reported by nobody — a code from a system this tenant does not hold, a reference to another server or to something contained in the document — because unresolvable is not invalid. The checks read rows and name no FHIR version, so one set of them serves every face. | PROVEN |
| `REQ-DBO-VAL-THE-DATABASE-ANSWER-IS-ADVISORY-UNTIL-IT-IS-NOT` | On a write the database is asked what it makes of the document, against the same definitions the toolchain used, and the answer changes nothing: the verdict a caller receives is the toolchain's. What is kept is a tally — the two agreed, one of them found something the other did not, or this tenant holds no expanded rows to compare against — by resource type and never by document, since a document here is a person. A comparison that fails is counted and never reaches the write. | PROVEN |
| `REQ-DBO-VAL-DIVERGENCE-IS-MEASURED-OVER-THE-VERSION` | Everything a version publishes is put to both checkers, and what they disagree about is recorded per resource type as a baseline that may fall and may not rise. The corpus is the specification's own conformance resources — deep, sliced, bound and referenced documents of real types — because the instance examples ship in a package a store has no use for. The whole case for the database answering at all is that it answers the same, so the measurement is kept where a change to either side has to face it. | PROVEN |
| `REQ-DBO-VER-AN-ELEMENT-THAT-DOES-NOT-TRANSLATE-IS-REFUSED-BY-NAME` | An element the database cannot locate — a slice told apart by following a reference, a count that is no number — is refused when the definition arrives, naming the definition and the element. A definition is never held with an element nothing can check, because a checker holding no row for an element enforces nothing about it and says so to nobody. | PROVEN |
| `REQ-DBO-VER-BALLOT-RECORDED-PER-VERSION` | A stored version records the exact version it was authored under — a ballot by its full spelling, never the release it anticipates — so a later version has something to convert from and a reader is never told a guess. | PROVEN |
| `REQ-DBO-VER-BALLOT-SERVED-AS-AUTHORED` | A version still at ballot promises no normalized truth form and no conversion to or from another version: what an author wrote is what a reader receives. Normalising under a ballot's understanding would bake it into bytes that are never rewritten, and the next ballot moving an element would lose what it moved. | PROVEN |
| `REQ-DBO-CORE-UPGRADE-ON-READ` | Old payload versions are upgraded lazily by registered converters; a schema-version transition never requires a big-bang rewrite. | PROVEN |
| `REQ-DBO-CORE-IDENTITY-SURVIVES-CONVERSION` | Conversion between FHIR versions or object shapes never changes identity; canonical urls and identity-bearing identifiers are preserved bit-exact and verified after every conversion. | PROVEN |

Coverage: {PROVEN=29} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **No version-ahead.** Writing at a storage-target shape while serving the
  operating one, and down-converting on read, is deliberately not built. The
  registry keeps room for the round-trip property it would need, and it
  becomes an issue when a consumer's release cadence actually calls for it.
- **Somebody has to run the conversion.** Conformance is a fact about the
  past, so the store accumulates stamped stock and does not migrate itself.

## Open decisions

- **Whether a withdrawn pack version should stop new accepts** as well as
  leaving old stock findable. Today only a pack declaring an *older* version
  than the stamp is a conflict.
