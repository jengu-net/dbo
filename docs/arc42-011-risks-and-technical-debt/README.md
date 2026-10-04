# Risks and technical debt

The outstanding work, as numbered items. Each item is a directory holding a
README whose first line is the item's state, and whatever the item needs
beside it: a measurement, a listing, a trace. A resolved item is deleted;
the commit that resolved it, or the decision record it produced, is its
record.

A risk that has not become an item is listed under Risks below, in one
sentence, until it either becomes one or stops being a risk.

## What an item carries

An issue says what is to be done and whether it is done. The specification
says how the store works once it is. An item carries what neither holds: the
problem as it stands, the order the steps become possible in, what was
decided along the way and what bit. It names the issue holding the work by
address, because a number is not something a reader here can open.

A useful item answers, in this order: what this is, where it stands, the
sequence, the decisions and why each beat the alternative, the traps, what
is deliberately not being done, and the commands that prove it.

## Items

| Item | State |
|---|---|
| [001 The documentation tree is moved to match its map](001-documentation-shape/README.md) | Open. The context chapter, the worked deployment and the guide are done: the guide is the sample applications' story, one chapter per user story, quoting their source. One disagreement is left — `using-dbo.md` is a reference where the map says it is the samples' README — and it waits on nothing now. |
| [006 The specification is cut to the house style](006-the-specification-in-house-style/README.md) | Open, and every part of the tree measures inside the band: the specification sections 7.0 per thousand with none above 8.5, the guide 4.0 over its eighteen chapters, the rest of `docs/` 5.8 or below. Each pass found its few cuts by reading, and the measure over-predicted the work every time. What is left is step 5's reading of what the guide and the sections do not cover. |
| [008 IHE profiles](008-ihe-profiles/README.md) | Not scheduled. Analysis only: no profiled surface is served and no issue is filed. |
| [009 The step-scoped API](009-the-step-scoped-api/README.md) | Open. A plain read still bypasses the step. Built: a run reaches the documents it names, its context answers only the client holding it, and the asker collects what the run produced, for a window its step declares, as the audience the step names. Left: traversal, a write refusal that needs it, and demoting the general surface; the traversal half is item 021's to design, because one answer has to serve both doors. |
| [010 Tenant kinds](010-tenant-kinds/README.md) | Open, and smaller than its title. All three costs it was filed for are closed and none was closed by a kind; the design is superseded by the activity selectors built meanwhile, and that finding is in the guide's *A tenant opens* chapter. What is left is a coarse label an outside bundle could select on, which nothing asks for. |
| [011 UBL is a face, described in FHIR's own tools](011-ubl-as-a-face/README.md) | Open. Three spikes are green and in the tree; nothing is built. The one open question narrowed: holding a signed document's received bytes as its truth keeps the store's promise about projections, and what it needs is a projection that is a whole document — which is the store's design to make, not UBL's. |
| [013 A neutral IFC repository](013-a-neutral-ifc-repository/README.md) | Not scheduled. Recorded so the reasoning exists before somebody needs it. |
| [014 The Karaf console](014-the-karaf-console/README.md) | Detached from the build. A proposal for seeing inside a running node, development and operator tooling only; its three projects are commented out of the build while the runtime compiles to Java 25, which Karaf 4.4 does not run on. |
| [015 The comparative load test](015-the-comparative-load-test/README.md) | Not scheduled. The bench runner carries the discipline; a second and third target, an ingest workload and resource sampling are missing. |
| [016 Where a neutral store earns its keep](016-where-a-neutral-store-earns-its-keep/README.md) | Not scheduled. A test for recognising the domains this engine's shape fits. |
| [017 Quality coverage is not folded](017-quality-coverage-is-not-folded/README.md) | Open. The twelve goals are declared and the tree is generated from them under a ratchet. Performance reads 1/6, and reading it out says four of the five missing points are PLANNED scaling promises no item describes — the figures item 015 would take are the fifth. |
| [019 The build repeats work whose inputs did not change](019-the-build-repeats-itself/README.md) | Open, and down to one step. The cache is on, and so is `org.gradle.parallel`: every test task that boots a world holds one permit, so two worlds never meet in one machine and the rest of the build runs beside the one there is. The definitions gate is a task the cache answers for on about nine commits in ten. Left: the ledgers and the projections declare no outputs, so they run every time. |
| [021 Asking the store a question](021-asking-the-store/README.md) | Open. The vocabulary exists with two bindings a caller cannot tell apart — `Asking` in the process and `Across` over a tenant's door — for work, records and the trail, with an observer off by default; a run comes back as an `Ongoing`, not a `Run` whose other eleven fields only one binding could fill. The guide's care chapter quotes the questions from the clinic's application. Next: joins. |
| [023 The suite runs out of heap](023-the-suite-runs-out-of-heap/README.md) | Open. What a tenant costs is measured — 226 MB for a face's first, 11 MB for the next on it — and the floor is the FHIR definition corpus, one `SimpleWorkerContext` per version per process, resident by design. The dial is 3g, which bought room and answered nothing. Needing fewer contexts is items 024 and 025's work; left here is the tail and the decision about the dial, both to be read again on a suite whose world-booting classes are now mostly story legs. |
| [024 Definitions out of the heap](024-definitions-out-of-the-heap/README.md) | Open. Eight of the nine moves are done and the write path's share of the ninth is built — conditional references answered over the bytes, the stamps read from the rows — and it moved no megabyte, which is the finding: a context is built at bring-up, one per version per process, so no per-tenant declaration makes one go away while a neighbour needs it. What is left is not this item's to do. The five serving paths at its foot that a tenant cannot declare its way out of are item 025's, and three of them are its whole scope. |
| [025 A face toolset of our own](025-a-face-toolset-of-our-own/README.md) | Open. Eleven of the twelve moves on the critical path are done. The toolchain's 225 MB per face is the form its definitions are held in, not their size; an index of flat rows over the closure of a tenant's declared types is 31 times smaller, and a checker over it answers cardinality, fixed and pattern values, slicing, required bindings and 68.4% of the compiled rules, held against the database's own answers. A tenant declares the index face (`indexFace`), a dependent asks its face for the closure it needs, and a serving node carries no definition packages: `config/carried-packages.txt` reads zero. Left: the face image cut per closure and accepted on coverage, designed and not built, and refusing a tenant that narrows below what it already holds. |
| [032 One lane for the fleet, and two levels of step](032-one-lane-for-the-fleet/README.md) | Open, and the plan is built: steps 0 to 7 — application-level steps declared in the management tenant, one level per step code, a substrate per step, the joiner, one bean performing for every tenant through each tenant's own lane, and the register, incident, per-tenant enrolment, per-row authorisation and postures. Its runs keep the run model item 036 built, through the mapping a runner uses. Left: what the reduced account in the management tenant holds, and whether an erasure reaches a joined item a processor has opened. |
| [033 The notification listener cannot unwrap a pooled connection](033-the-notification-listener-cannot-unwrap/README.md) | Open, and down to one place. The lane's pool and the doors' take their connections through the stream bundle's own driver resolution, so the separated check runs without the warning, and an unsubstitutable shared export is refused at boot. What remains is a tenant's own pool rebuilt on a second bring-up: Hikari instantiates a driver directly and the subscription engine on it can no longer unwrap. The class whose run showed it is folded into the edge round-trip story, so the crossing is next measured on a run that brings a tenant up twice. |
| [034 Where a worker's substrate and keys come from](034-where-a-workers-substrate-and-keys-come-from/README.md) | Open, and down to its last decision. The keys are the application's, because a store holding a participant's signing half could sign as it; the worker sample names only the deployment's database and mints its own key pairs, handing over the public halves. Both pools onto the substrate are sized. Left: whether a co-located worker shares the serving half's pool, and after sizing there is little on the other side of that. |
| [035 Every promise proven on one world, inside its story](035-every-promise-on-one-world/README.md) | Open; every story is on the one world. Ten story classes — the nine user stories and the erasure story — walk Rowling Land in the clinic application's tests (`samples/spring-boot-server-app`): one context, every story at once, 229 legs in about ten minutes when last timed. Thirteen harness classes still build a runtime of their own, each with its reason in the worlds ledger, and the technical stories hold what the sample world cannot be. Walking the stories found eight store defects and one test-tool defect. Next: the ten classes left for later (step 7), then the close. |

## Risks

- `./verify` stops the Gradle daemon between its phases, and the daemon is
  shared across every worktree of this repository, so a verify in one
  checkout kills a suite running in another. The migration runs its phases
  with `--no-daemon` for this reason; nothing fixes it.
- **A test that scans once and then uses what it declared is a flake waiting
  for a loaded runner.** A pass is one reconciliation, not a promise that it
  finished, so the tenant answers 404 — which is what a tenant nobody
  declared answers, so the class dies in its setup naming neither. The door
  is `UntilServed.scan`, which waits and which fails loudly with the
  runtime's own reason. Calls to `scanOnce` remain and are not all this
  mistake: a test about the scan itself asserts what one pass returned,
  correctly. Which are which is read, not grepped.
- What is built, as against what is promised, is read from the
  [requirement catalogue](../arc42-006-runtime/req-catalogue.md) and the
  tests it cites, not from prose.
