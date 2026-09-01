# Promise: requirements as code

A convention rather than a mechanism of the engine: nothing here runs in a
tenant's runtime, and a reader after the store's behaviour will not find it
described in this file. What it constrains is the work — how this repository
is permitted to state a behaviour and what counts as having proven one.

A business requirement here is a **promise — proven, not claimed**. The
`promise/` module is a small, dependency-free subsystem for declaring
promises once, citing them everywhere, classifying them, and composing the
catalogues of several products into one graph. It is deliberately generic:
nothing in it knows this store, so any product in the family can adopt it
against its own catalogue.

## The model

- **Promise** — the atom: one testable business promise. Declared exactly
  once, as an enum constant; **the constant's name is the code** (prefixed by
  its catalogue's namespace), so a citation cannot drift from a declaration —
  there is no string to mistype and no generator to trust. The constructor
  carries the promise text: one declaration, one prose.
- **`Promise.gap("…")`** — a promise nobody has stated yet. A gap compiles,
  is registered, and counts against coverage in every report until someone
  promotes it to a named constant. Unknown ground is named, never silent.
- **Classifications** — four views over promises, one grouping above them:
  - **Story** — a concrete case, told from the participant's side.
  - **Feature** — the system-functional view of the same ground.
  - **Quality** — the technical view: performance, reliability, scale
    (arc42 §10 / ISO 25010 quality attributes; deliberately not
    "capability", which is a load-bearing word in the face contract).
  - **Constraint** — externally imposed rules, mostly regulatory (arc42 §2).
  - **Area** — the grouping above all four: an area may cover a regulation
    paragraph-by-paragraph as constraints, each fulfilled by promises.
- **Links run down only.** A classification declares the promises that
  fulfil it; a promise never names its classifications. The inverse is
  derived by tooling — one direction, one truth, nothing to drift.
- **Coverage is a fold.** A classification's coverage is the statuses of the
  promises it declares — gaps included; an area's is the fold of its
  classifications. Coverage is computed, never asserted.

## Citation

- **`@Proving`** on a test method cites the promise constants that method
  proves. The annotation is typed to the product's own catalogue enum
  (annotation members cannot be interface-typed), so a wrong citation is a
  compile error. The framework recognises it by the **`@Cites`
  meta-annotation** on the annotation's own declaration — three lines per
  product, and the framework never learns a product's types. The annotation
  **cites, never defines** — a promise proven by five tests has one text, in
  the catalogue. Citation sites are indexed by the same processor at the
  product's own test-compile time (`META-INF/promise/proofs`), so a renamed
  or deleted proof site cannot leave a stale citation behind; the citing
  module adds the promise module to its `annotationProcessor` configuration,
  or the index is silently absent.
- **Production code cites the same constants** — a refusal that enforces a
  promise names it (`Promise.X.code()` in the error), so the requirement is
  referable from the implementation, not only from its tests.
- **Status is derived, never asserted**: *cited is proven* — a report is
  only ever generated from a green build, the same trust the close-after-CI
  gate encodes, so a failing citation fails the build before any report
  exists; a declared constant with no citation is planned; an `assurance()`
  note declared on the constant itself is review-based assurance (a property
  of the promise, not of a test that does not exist); a gap is a gap. A
  gap's code is synthetic and stable — the hash of its text, qualified by
  the declaring catalogue's namespace — so reports diff cleanly and a
  promotion to a named constant shows as exactly that.

## Registration and composition

Each product keeps its promises in its **own enums implementing the shared
interfaces**, marked `@Catalogue(namespace = "REQ-…")`. Discovery is paid at
compile time, where the set of annotated types is already known:

- A small annotation processor in the promise module (standard
  `javax.annotation.processing`, zero dependencies) sees the annotated enum
  during the component's own compilation and writes its binary name into a
  resource index (`META-INF/promise/catalogues`) in that component's jar —
  the ServiceLoader discovery contract without generated provider classes,
  which ServiceLoader itself would force, since it instantiates providers
  and enums cannot be. No classpath is ever swept, and an index regenerated
  on every compile cannot drift or be lost.
- The **Registry** loads registrations (`Registry.load()`), or takes an
  explicit `register(Class)` from tools and tests, and reads each catalogue
  whole via `getEnumConstants()` — proven, planned and gap constants alike.
  Eager and complete by construction: registration is never a side effect of
  class loading, because the constants most likely to go untouched (gaps,
  planned promises with no tests yet) are exactly the ones coverage exists
  to count.
- **Composition** folds every registered catalogue into one graph. Namespaces
  keep codes globally unique by construction. **Areas merge by code** — one
  area may span several products' catalogues — and the composer refuses two
  same-code areas with conflicting prose rather than picking one. Area
  enums therefore share the `AREA` namespace by convention: that is what
  lets a same-named area declared by two products compose into one.
- Composition runs at build/report time on a flat classpath. Inside an OSGi
  container, ServiceLoader needs mediation — a documented boundary; nothing
  here runs inside the container.

## Projection

Wherever the catalogue is also wanted as prose — the requirement tables in
`docs/arc42-006-runtime/req-catalogue.md` — the prose is a **generated
projection** of the composed graph, never a second source. Editing a
projection is refused the way the branding ratchet refuses a leak —
concretely, as a unit test that regenerates the marker-fenced block on every
build and fails on any difference, naming the regeneration command. The full
report renders on every CI run's results page via the job summary, so the
build's meaning — promises proven, by which tests, and what is still a gap —
leads, and the method inventory does not.
