# Architecture decisions

A record here is history: the question as it stood, what was weighed, what
was measured, and what was decided. It is kept so that a reader who wonders
why the store is shaped this way can find the path.

What a record decided is stated as current fact somewhere else, and its
**Reflected in** line names that page. The link runs one way. A page
describing the store does not cite a record, because a sentence that needs
one to make sense has not yet said what it means.

A record is never edited to match a later decision. When a decision is
overturned, the new record says so and the old one's status changes.

## The records

| Record | Status | Reflected in |
|---|---|---|
| [001 DBOS runs inside a bundle](001-dbos-runs-inside-a-bundle.md) | Adopted | building blocks |
| [002 The routing layer is built here](002-the-routing-layer-is-built-here.md) | Resolved | deployment |
| [003 One bundle owns the FHIR stack](003-one-bundle-owns-the-fhir-stack.md) | Adopted | the payload seam |
| [004 Durable work sits in two planes](004-durable-work-sits-in-two-planes.md) | Resolved | running it |
| [005 Search is tiered on measured usage](005-search-is-tiered-on-measured-usage.md) | Resolved | finding things |
| [006 Adoption is identity first, storage second](006-adoption-is-identity-first.md) | Resolved | running it |
| [007 A face parses into the HL7 core model](007-a-face-parses-into-the-hl7-core-model.md) | Open | nothing: an open record has no result to state |
| [008 Only a named port may write the trail](008-only-a-named-port-writes-the-trail.md) | Resolved | records you can rely on |
| [009 A refusal and an unanswered store are different](009-a-refusal-and-an-unanswered-store.md) | Resolved | processes and work |
| [010 Why the engine is shaped this way](010-why-the-engine-is-shaped-this-way.md) | Context | the solution strategy |
| [011 A pattern language for the store](011-a-pattern-language-for-the-store.md) | Resolved | the patterns |

## The numbers a comment may still carry

Comments in the code cite `§9`, which is record 010. The former `§7.N` is
record `00N`; nothing in the tree cites it any more, and the mapping is kept
for references made outside this repository. A new reference names the
record, a REQ, or the page that states the result.
