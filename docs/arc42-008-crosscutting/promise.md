# Promise: requirements as code (§16)

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
  compile error. The annotation **cites, never defines** — a promise proven
  by five tests has one text, in the catalogue.
- **Production code cites the same constants** — a refusal that enforces a
  promise names it (`Promise.X.code()` in the error), so the requirement is
  referable from the implementation, not only from its tests.
- **Status is derived from the proof site, never asserted**: a cited
  constant with passing tests is proven automatically; a declared constant
  with no citation is planned; a recorded manual-verification note is
  review-based assurance; a gap is a gap.

## Registration and composition

Each product keeps its promises in its **own enums implementing the shared
interfaces**, marked `@Catalogue(namespace = "REQ-…")`. Discovery is paid at
compile time, where the set of annotated types is already known:

- A small annotation processor in the promise module (standard
  `javax.annotation.processing`, zero dependencies) sees the annotated enum
  during the component's own compilation and emits its ServiceLoader
  registration into that component's jar. No classpath is ever swept, and a
  registration regenerated on every compile cannot drift or be lost.
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
  same-code areas with conflicting prose rather than picking one.
- Composition runs at build/report time on a flat classpath. Inside an OSGi
  container, ServiceLoader needs mediation — a documented boundary; nothing
  here runs inside the container.

## Projection

Wherever the catalogue is also wanted as prose — the requirement tables in
`docs/arc42-006-runtime/req-catalogue.md` — the prose is a **generated
projection** of the composed graph, never a second source. Editing a
projection is refused the way the branding ratchet refuses a leak.

Delivery epic: [#137](https://github.com/jengu-net/dbo/issues/137).
