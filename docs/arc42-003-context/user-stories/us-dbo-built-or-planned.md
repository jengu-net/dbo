# US-DBO-BUILT-OR-PLANNED — what the store actually does is answerable, and the answer is not prose somebody typed

> Ines is deciding whether to build the clinic group's platform on this
> store. She does not want a feature list. She wants to know which of the
> things it says about itself are carried by a test, which are only
> intended, and which are admitted gaps — and she wants to be able to tell
> those apart without trusting whoever wrote the page.
>
> She reads one generated table, sorts by status, and knows.

## The scene

Every other story here is somebody using the store. This one is somebody
deciding to, and what they meet first is a claim: *this store does the
following*. A builder who cannot check that claim is being
asked to take a document's word for a system's behaviour, which is the
position this catalogue exists to end.

Ines comes with three questions and gets them answered from one place.

## What is promised is a constant, not a sentence

A promise is declared exactly once, in code, and its code is derived from the
name of the constant. There is no list of requirement numbers maintained
beside the requirements: renaming the constant renames the code, and a
citation that outlives its declaration fails the build.

So the first thing Ines can rely on is that the catalogue cannot contain a
promise nothing declares, and cannot lose one that something does.

## What is proven is what a test says, not what a page says

A promise's status is computed. A promise some test cites reads PROVEN; one
that exists and nothing cites reads PLANNED; a gap declared as a gap reads as
a gap and counts against coverage.

That is the distinction Ines is actually buying. A feature list cannot tell
her which entries are aspirations, because the same sentence describes both.
Here the difference is mechanical: somebody wrote a test, or nobody did.

The citation is typed — a test cites through its product's own enum-typed
annotation — and the index of citation sites is built during the product's
own compilation. A proof site that is renamed or deleted cannot leave a
promise reading proven: the index is rebuilt from the sources that compile.

## The page she reads is generated from the model

The catalogue's prose form is projected from the composed model and never
maintained beside it. A hand-edit is a ratchet failure rather than a small
improvement that drifts. Every CI run leads its results page with the same
coverage, so the number she reads in the documentation is the number the
build computed.

She never has to ask which copy is current, because there is one.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PRM-NAME-IS-THE-CODE` | A promise is declared exactly once, as an enum constant; its code derives from the constant's name and its catalogue's namespace, so a citation cannot drift from a declaration — there is no string to mistype and no generator to trust. | PROVEN |
| `REQ-DBO-PRM-GAP-IS-FIRST-CLASS` | Unstated ground is declared as a gap with plain text; a gap registers, carries a stable code, and counts against coverage until promoted to a named promise. | ASSURED |
| `REQ-DBO-PRM-REGISTERED-AT-COMPILE-TIME` | An annotated catalogue is registered during its own component's compilation — no classpath is swept, and a registration regenerated on every compile cannot drift or be lost. | PROVEN |
| `REQ-DBO-PRM-CATALOGUE-READ-WHOLE` | The registry reads a catalogue's constants whole — proven, planned and gap alike — never as a side effect of what happened to be class-loaded. | PROVEN |
| `REQ-DBO-PRM-AREAS-MERGE-BY-CODE` | Composition merges same-code areas across catalogues and refuses two with conflicting prose rather than picking one. | ASSURED |
| `REQ-DBO-PRM-CITATION-IS-TYPED` | A test cites promises through its product's own enum-typed annotation, recognised by meta-annotation — a mistyped citation is a compile error, and the framework never learns a product's types. | ASSURED |
| `REQ-DBO-PRM-PROOFS-INDEXED-AT-COMPILE-TIME` | Citation sites are indexed during the product's own compilation; a renamed or deleted proof site cannot leave a stale citation behind. | PROVEN |
| `REQ-DBO-PRM-STATUS-IS-DERIVED` | A promise's status is computed — cited is proven, named-uncited is planned, assurance is declared on the constant, a gap is a gap — never asserted at a proof site. | PROVEN |
| `REQ-DBO-PRM-COVERAGE-IS-A-FOLD` | A classification's coverage is the fold of its declared promises' statuses, gaps included; an area's is the fold of its classifications. | PROVEN |
| `REQ-DBO-PRM-DOWN-LINKS-ONLY` | A classification declares the promises that fulfil it; a promise never names its classifications; the inverse is derived. One direction, one truth. | ASSURED |
| `REQ-DBO-PRM-A-STORY-IS-CITED-NOT-CLAIMED` | A user story is a constant beside the promises, and promises declare the stories they serve; a story's legs are projected from those declarations rather than written by hand, so a story cannot cite a promise that does not exist, cannot claim a leg nothing promises, and reads unproven while every leg it rests on is only planned. A story claims no evidence: coverage arrives only through promises citing it. | PROVEN |
| `REQ-DBO-PRM-PROJECTION-IS-GENERATED` | The catalogue's prose form is generated from the composed model, never a second source; a hand-edit or a stale projection fails the build. | PROVEN |
| `REQ-DBO-PRM-COVERAGE-ON-THE-RESULTS-PAGE` | Every CI run's results page leads with the composed promise coverage report. | PROVEN |

Coverage: {PROVEN=9, ASSURED=4} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Coverage is a count, not a judgement.** A promise carries no weight, so
  thirty small ones and one load-bearing one fold the same way. Reading the
  fold as importance is a mistake the number invites and does not prevent.
- **A proof is a citation, not a review.** Status says a test named the
  promise; it does not say the test is a good one, and nothing here
  measures that.
- **No history of the answer.** The catalogue says what is true now. Whether
  a promise has been proven for two years or since Tuesday is in the git
  log and nowhere the page can show.

## Decided in review

- **This is a story, not an exception.** These promises were the only ones in
  the catalogue belonging to no story, and the first instinct was to leave
  them there on the grounds that nobody in a clinic meets a catalogue. That
  reads the audience wrong: the chapter says a story is told from the
  perspective of the person building on the store, and the first thing a
  builder does is find out what the store is. Leaving them storyless would
  have been a classification hole dressed as a principle.
