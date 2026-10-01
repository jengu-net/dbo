# US-DBO-VERSION-MEASURED — a version's database answers are measured before they are trusted

> Mart cuts the store's releases. Before a FHIR version ships, he has one
> question that no deployment can answer for him: when the database says a
> document is wrong, and the checkers in the process say it is wrong, and
> the specification the release carries says what is wrong, do the three
> say the same thing — not for a handful of examples, but over everything
> the version publishes?
>
> If they do not, a tenant's write is judged by whichever answerer happens
> to run, and nobody finds out which until a clinic's record is refused by
> one and accepted by the other.

## The scene

Every other story here is somebody using a deployment. This one happens
before there is a deployment to use. What is measured is a release: the
definitions a version publishes, the rows they are expanded into, the index
the process reads off those rows, and the SQL the database runs over the
same rows. None of that is behind a door. A deployment serves records and
validates writes; it does not hand anybody its definition index or its
carried packages to compare. So Mart measures on a store he can open up, with
the face's own libraries beside him.

He works through it in the order the answers depend on each other.

## What a face gave a tenant is a thing of its own

A version arrives at a tenant as definitions, and a face is cut once per
release and handed to every tenant that comes up on it. So the definitions
live in a schema of their own, move on a feed of their own, and keep a cursor
of their own. A profile lands among the definitions and a patient never
does. A subscriber taking a face reads no clinical traffic on the way to the
next profile, and a reader of what happened at a tenant is not handed the
face as well.

A type registered into the wrong one of those two places is refused when it
is registered, by name, saying what would go wrong: a definition among the
records makes the face image short, and a record among the definitions puts
somebody's patient into everybody's copy.

What a tenant needs from a face is then a list of names rather than a
filter. It is the closure of the types the tenant declared, computed where
the definitions are, because a tenant cannot compute the closure of
definitions it does not hold. The list names whole grains, a code system
together with every value set that draws on it, and the upstream selects
by it: a canonical nobody named is not sent, and a record that carries no
canonical is never withheld for missing from a list of definition urls.

## The index says what the rows say, and the rows say what the packages say

The checkers in the process read an index built from the expanded rows, not
from the packages HL7 published. The rows are what arrived, including a
tenant's own profiles, and the packages cannot supply those. Moving the
source is only safe if the answer did not move with it. Mart compares the
two element by element over a tenant's whole closure, and requires every
structure in that closure to be held on both sides, because an index that
held nothing would agree with anything.

The rows are also indexed from two front ends: expressions written out in
the face for definition types, and the parameters the cut compiled for
everything else. Over every definition both can index, they must build the
same keys. A front end that quietly indexed less would turn a search into an
empty answer that looks like a real one.

## The database against the toolchain, over the version

The database's answer is advisory. It runs beside the toolchain and acts
on nothing until it answers the same. So it is compared over every
conformance resource the version ships, by type, and what the two disagree
about is recorded in a baseline that may fall and may not rise. Each
remaining divergence is read and explained there, rather than counted and
forgotten.

Part of answering the same is finding a value that is not the kind of thing
its element declares. A date that is not a date is a finding, while a year
on its own is correct. A string where a boolean belongs is a finding, while
the other arm of the same choice is correct. The specification's own
documents still come through as well formed.

## A third answerer, over the same rows

The index checker answers from the rows the database answers from, so the
two are held to the same elements. Over the corpus they are both silent, and
the walk has to have gone deep enough for that silence to mean something.
The compiled rules both run fault the same documents. A required binding is
decided in the process against the few hundred codes the tenant holds, and
the database agrees. On documents that are actually wrong, at every depth,
both name the same elements, except inside a datatype no profile constrains.
There the index reaches further than one profile's rows, which is why a
third answerer is worth having.

A fixed or pattern value is almost never stated in what HL7 publishes, so
that half is asked of a tenant's own profiles: a pinned identifier system, a
pinned marital status, and a slice counted as the members it claims rather
than every member of the element it slices.

The index also carries the whole payload contract the element face
carries. A document read and written back is the document that arrived. A
literal stays a literal, so a decimal keeps the precision its author gave
it. A shape nobody holds is not stamped, and what the index refuses is what
the database refuses.

## What a checker in the process rests on

Some checks are not columns. A required binding needs codes, and a slice or
an invariant needs a predicate evaluated. Mart does not take the premises
those checkers rest on on trust. Everything a required binding names is
content the tenant holds. Every slice predicate is equality, optionally
conjoined. Every construct a compiled invariant uses is one somebody has
costed. If a version breaks one of these, the release fails here, by name,
before a checker moves from answering to guessing.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-VER-DEFINITIONS-LIVE-IN-A-SCHEMA-OF-THEIR-OWN` | Every definition a tenant holds, its history, and every row derived from one — the elements it was expanded into, the invariants compiled off it, the shape stamps, the concepts and value sets a vocabulary was imported into — are in one schema of their own, apart from the tenant's records. So what a face gave a tenant is a thing the database can name and hand over, rather than a filter by type inside tables shared with somebody's patients. The face's own functions read what a definition says from that schema and nowhere else, which is what makes a dump of it the whole of what the face gave rather than most of it; reaching the RECORDS is the one thing they do outside it, because a reference points at one. A record is never in the schema and a definition never outside it: it is what a face is cut from and handed to every tenant on that face, so a profile left among the records makes the image short, and a patient among the definitions puts somebody's record into everybody's copy. | PROVEN |
| `REQ-DBO-FEED-DEFINITIONS-MOVE-ON-A-FEED-OF-THEIR-OWN` | Definitions are a domain with a feed and a cursor of their own. A record never appears on that feed and a definition never appears on the record feed, so a subscriber taking a face does not read the root's clinical traffic on the way to the next profile, and the position a face is cut at is a position records cannot move past. | PROVEN |
| `REQ-DBO-TEN-A-TYPE-DECLARES-ITS-DOMAIN` | Which domain a type's rows live in is part of its registration and is checked when a tenant comes up. A definition registered among the records, or a record among the definitions, is refused by name — because nothing about a misplaced type fails on its own, and what breaks is an image taken later and handed out before anybody looks. | PROVEN |
| `REQ-DBO-VAL-THE-INDEX-IS-A-PROJECTION-OF-THE-EXPANDED-ROWS` | A tenant's definitions are read into flat arrays over one interned dictionary — path, parent, min, max, type codes, binding and its strength, and the invariants — from the ROWS a definition was expanded into, and never from the packages a version publishes. The rows are the source because they are the only place all three of what this has to hold arrive together: a version's own structures, a tenant's own profiles, and whatever a face image carried. A second expansion out of the packages would be a second specification with nothing comparing the two. What is read is the closure of the types the tenant declared rather than the version: composition is followed and reference is not, so an element typed HumanName reaches HumanName, and one typed Reference(Condition) reaches Reference and stops — Condition enters only where the tenant declares it. What the index does not hold is the prose a model built for authoring carries, the short and the definition and the comment, because a checker never reads it and declining to hold it is most of what the form is. | PROVEN |
| `REQ-DBO-VAL-DIVERGENCE-IS-MEASURED-OVER-THE-VERSION` | Everything a version publishes is put to both checkers, and what they disagree about is recorded per resource type as a baseline that may fall and may not rise. The corpus is the specification's own conformance resources — deep, sliced, bound and referenced documents of real types — because the instance examples ship in a package a store has no use for. The whole case for the database answering at all is that it answers the same, so the measurement is kept where a change to either side has to face it. | PROVEN |
| `REQ-DBO-VAL-THE-DATABASE-ANSWER-IS-ADVISORY-UNTIL-IT-IS-NOT` | On a write the database is asked what it makes of the document, against the same definitions the toolchain used, and the answer changes nothing WHERE THE TYPE HAS NOT DECLARED OTHERWISE: the verdict a caller receives is the toolchain's. Where a type declares the database and this tenant holds the rows to answer with, the database is asked FIRST and the toolchain is not run at all — so there is no second answer to compare, and the tally counts only the writes both answered. What is kept is a tally — the two agreed, one of them found something the other did not, or this tenant holds no expanded rows to compare against — by resource type and never by document, since a document here is a person. A comparison that fails is counted and never reaches the write. | PROVEN |
| `REQ-DBO-VAL-A-THIRD-ANSWERER-READS-THE-INDEX` | A third answerer checks a document against the definition index, in the serving process, with no worker context and no round trip — and what it answers is held against the database's own answer over everything the version publishes. It is not a second specification: the checks that read rows are the specification and this one is measured against them, because three answers nobody compares would be worse than two that are. How often an element may occur is counted inside the parent it occurs in, so the walk enters a backbone where the resource defines it and a datatype's own structure where it does not — one contact holding two names is wrong and two contacts holding one each is not. A contained resource is not followed: the element says only Resource, and what a document may contain is a question about the tenant's declaration rather than about cardinality. And silence is not evidence — the walk reports how far it descended, because a checker that never descended faults nothing and a correct corpus reads the same either way. | PROVEN |

Coverage: {PROVEN=7} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Measured, not served.** The index face meets the whole payload contract
  and nothing selects it for a tenant. The comparison says it could answer;
  no deployment asks it to.
- **The manifest is derived, not sent.** What a tenant needs from a face is
  computed and closed over grains, and the sync path still streams by type.
- **Some divergences remain, each explained in the baseline.** A rule the
  validator carries in its own code, a map checked as a program, a defect in
  the toolchain, and content the tenant does not hold. No checker built from
  definitions answers any of them.

## Open decisions

- **Whether a release should refuse to ship on a risen baseline** rather
  than fail a test someone re-records. Today the baseline is a file the
  release carries, re-recorded by a flag.
