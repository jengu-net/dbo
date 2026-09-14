# A pattern language for the store

The proposal this language came from. All twenty-four patterns are now written
under [crosscutting concepts](../arc42-008-crosscutting/patterns/README.md);
what this document keeps is the reasoning behind the set, the page shape, the
icon rules and the reading list.

## What is being proposed

The *Why DBO* essays already read as one argument: work first, access granted
to a step, the run as the reason, the trail as records, the person as a key,
and so on. What they do not do is give each link of that argument a **name a
reader from another discipline can carry away**. A regulator, a security
officer, a hospital administrator and a product owner all need to talk about
the same twenty ideas, and today each of them has to re-derive the idea from
an essay written for somebody else.

Enterprise Integration Patterns solved that problem for messaging: a name, a
picture, a problem stated as a question, a solution stated in one bold
sentence, and the patterns it is built from. The proposal is to do the same
for the store, in language that does not need an engineer to decode it, and to
place the result under crosscutting concepts so the patterns sit beside the
mechanics they name.

## Where it goes

```
docs/arc42-008-crosscutting/patterns/
  README.md                       the map: every pattern, its family, its relations
  pattern-property-not-policy.md
  pattern-work-is-the-reason.md
  ...
```

One directory, one file per pattern, every file prefixed `pattern-`. The
existing concept pages and `why-*.md` essays stay where they are; a pattern
page links down to them for the mechanics and the proofs, and the essays gain
one line each linking up to the pattern they are the long form of. The
`§` numbering is untouched: the patterns are a reading of the concepts, not a
new section.

## The shape of a pattern page

Every page has the same seven parts, in this order, so a reader who has read
one knows where to look in all of them.

1. **Name**, and a one-line intent under it.
2. **You are** — the situation, in two or three sentences, with no vocabulary
   from this store in it.
3. **The question** — the problem, stated as one question.
4. **The forces** — the two or three things pulling against each other,
   as a short list.
5. **Therefore** — the solution, one bold sentence, then a paragraph.
6. **What each reader gets** — four lines: for a regulator, for security,
   for an administrator, for the business. This is the cross-domain part and
   it is not optional.
7. **Relations** — *builds on*, *makes possible*, *composed of* (the
   Enterprise Integration Patterns or literature patterns it is made from, if
   any), and *written up in* (the concept page, the essay, and the promises in
   the REQ catalogue that prove it).

The language rule for the whole directory: a sentence a regulator could not
follow is rewritten, not footnoted. Module names, class names and protocol
names appear only under *written up in*.

## The patterns

Grouped into six families. The first pattern is the root; everything else
either builds on it or exists to make it true.

### Foundation

**Property, Not Policy** *(root)*
A policy is a commitment somebody can break, forget, or be compelled to set
aside. A property is something the system does not permit. Every promise this
store makes is meant to be the second kind, and every other pattern is a way of
turning one policy into one property.
Builds on: nothing. Makes possible: everything below.
Related literature: Clark and Wilson on enforced integrity; Parnas and Clements
on design as derivation.

**The Tenant Is a Database**
Each organisation's records live in a database of their own, not behind a
filter over a shared one. A query with a bug in it cannot cross to another
tenant because there is nothing to cross to, and "we have removed your data"
means a database was dropped.
Builds on: Property, Not Policy. Makes possible: Leaving Is the Nightly Path;
The Trail Is Records.
Composed of: database-per-tenant isolation.

### Work

**Work Is the Reason**
Nobody reads a regulated record for no reason; the reason is a step of a
process. So access is granted to the step, for the length of one attempt at it,
and there is no general read behind it to fall back on. The record of the work
is the record of the reason.
Builds on: Property, Not Policy. Makes possible: The Run Is a Record; The Trail
Is Records; Nobody Is Pushed.
Composed of: Process Manager and Correlation Identifier (EIP).
Related literature: Clark–Wilson access triples; task-based access control;
purpose-based access control.

**The Run Is a Record**
One attempt at one step is stored as an ordinary record in the tenant's own
store. It therefore has history, an audit trail, an owner, and survives a
restart of anything, because it was never in flight. Nothing was added to get
those four.
Builds on: Work Is the Reason; A Type Declares What It Is. Makes possible:
A Sweep Is Found, Not Started; Falls to a Person, and Is Counted.
Composed of: Message Store (EIP).
Related literature: artifact-centric business process management; case
handling.

**Nobody Is Pushed**
The store never reaches out to the laboratory, the border post or the
supplier's system. Participants ask what is available to them, claim it, do
it, and report back. An organisation behind a hostile network, or one that is
asleep for the weekend, needs no hole opened towards it.
Builds on: Work Is the Reason. Makes possible: Two Parties Bound the Claim;
One Feed, Every Consumer.
Composed of: Polling Consumer and Competing Consumers (EIP).
Related literature: workflow resource patterns, resource-initiated allocation.

**Two Parties Bound the Claim**
What a participant may take is the intersection of two declarations: what its
credential covers, and what the step admits. Neither alone is enough. The same
shape governs jurisdiction and override everywhere in the store: the outer
party declares the set, the inner one chooses within it and may narrow, never
widen.
Builds on: Nobody Is Pushed; A Role Is a Period on a Record. Makes possible:
A Country Is a Zone.
Related literature: separation of duties; least privilege.

**A Role Is a Period on a Record**
The records a tenant keeps anyway say who works there, in what role, from when
until when. Those records are the grants. There is no second directory to keep
in step, and revoking access is ending a period on an ordinary record.
Builds on: A Type Declares What It Is. Makes possible: Two Parties Bound the
Claim.
Related literature: role-based access control; enterprise ontology, where an
actor role may be filled by a person or a system.

**Falls to a Person, and Is Counted**
When work is offered and no automated participant takes it, it falls through
to a person. That is expected. What matters is that the fall-through is
counted, per step and per zone, so the automation backlog is a fact you can
query rather than an opinion you commission.
Builds on: The Run Is a Record; Nobody Is Pushed.
Related literature: workflow resource patterns, automatic execution and its
fallback.

**A Sweep Is Found, Not Started**
Some work converges on a condition rather than over a list: erasing a person,
applying a configuration. Such a run is found rather than started, so a crash
halfway through resumes the same run instead of opening a second one beside
it. "Started twice" is the one property an erasure must not have.
Builds on: The Run Is a Record. Makes possible: Erasure Destroys a Key; One
Declared Set, Applied.
Composed of: Idempotent Receiver (EIP).

### Evidence

**A Type Declares What It Is**
Append-only, versioned, auditable, retained for how long, identified by what.
Declared once per kind of record and enforced by the engine, not left to the
habits of whatever code happens to write it.
Builds on: Property, Not Policy. Makes possible: The Run Is a Record; The Trail
Is Records; A Role Is a Period on a Record.
Composed of: Canonical Data Model (EIP), for the part about one shape everyone
agrees on.

**The Trail Is Records**
Who read this, who changed it, on whose authority, when. Kept as ordinary
records in the tenant's own store, append-only against everyone including the
party running the deployment, exported and restored with the tenant, and
answerable years after the machines are gone. Applications may enrich it and
cannot backdate it or sign it in somebody else's name.
Builds on: Work Is the Reason; A Type Declares What It Is; The Tenant Is a
Database. Makes possible: Carrying Is Not Reading; Asked, Not Scanned.
Composed of: Wire Tap and Message History (EIP).
Related literature: Clark–Wilson on the audit of transformation procedures.

**Carrying Is Not Reading**
A hop that carried work leaves a travel entry about the task. A participant
that opened a payload leaves an access entry about the document. So "who read
this" is answered from the document, "where did this go" from the task, and the
trail can say that nobody looked.
Builds on: The Trail Is Records; Bytes Framed, Not Rebuilt.
Composed of: Message History (EIP) for the travel entry; Claim Check (EIP) for
carrying without opening.

**Asked, Not Scanned**
Evidence you cannot find is not evidence, and a search that quietly answers a
narrower question with a wider result is the failure the caller cannot see. So
the store answers exactly what was asked or refuses and says what would have
worked. It never approximates.
Builds on: The Trail Is Records; A Type Declares What It Is.
Related literature: none named; this is the store's own stance.

### Change

**Committed with the Change**
Every change event is a row written in the same transaction as the change.
There is no separate publish step and no window in which the write succeeded
and the notification did not. No broker, no cache tier, no second source of
truth about what happened.
Builds on: The Tenant Is a Database. Makes possible: One Feed, Every Consumer.
Composed of: Guaranteed Delivery, Transactional Client and Event Message (EIP);
the transactional outbox.

**One Feed, Every Consumer**
Paging through results, subscribing to changes, keeping a dependent copy
current, and reconciling an appliance that was offline all weekend are one
primitive: an ordered, replayable sequence and a durable position. Every
consumer is a name and a position, so progress, lag and replay mean the same
thing for each and "how far behind" is one question.
Builds on: Committed with the Change; Nobody Is Pushed. Makes possible:
A Cursor That Holds Still.
Composed of: Durable Subscriber, Polling Consumer and Message Store (EIP).
Related literature: the log as the unifying abstraction.

**A Cursor That Holds Still**
Rows shift while you page, and offset paging pushes that cost onto every
caller as deduplication and defensive workarounds. A position here is opaque
to the consumer and stable under concurrent writes. The work is designed out
rather than worked around.
Builds on: One Feed, Every Consumer.

### The person

**The Person Is a Key**
Identifying material is encrypted where it is written, with a key belonging to
that person. Every copy the store makes of itself carries ciphertext because
of where the encryption happens, not because each path was written correctly.
Re-identification goes through one place.
Builds on: Property, Not Policy; The Tenant Is a Database. Makes possible:
Erasure Destroys a Key; Leaving Is the Nightly Path.
Composed of: Claim Check (EIP), the identity held aside and a reference
travelling in its place.
Related literature: revocable backup by key destruction.

**Erasure Destroys a Key**
Two requirements point in opposite directions: every version kept immutably is
what makes an audit trail worth anything, and a person may still require that
their data be gone. They coexist because erasure destroys a key rather than
rewriting anything. The entries remain, complete and in order; the person
evaporates from them.
Builds on: The Person Is a Key; The Trail Is Records; A Sweep Is Found, Not
Started.
Related literature: crypto-shredding; the right to erasure.

**Leaving Is the Nightly Path**
Getting out is one sealed archive, which the party operating the store cannot
read and somebody else can verify without asking anybody. It is the path that
runs every night as backup, not a project scoped when the relationship ends.
Builds on: The Tenant Is a Database; The Person Is a Key.
Composed of: Document Message and Envelope Wrapper (EIP).
Related literature: the right to data portability.

### Shape and operation

**Engine and Faces**
The engine knows records, custody, history and erasure and has never heard of
a patient. A face is the standard a tenant declares in one line of its
configuration, and it is a real server of that standard, not a gateway.
Mechanism in the engine, policy in the face; the test for where something
belongs is whether it carries domain meaning.
Builds on: Property, Not Policy; A Type Declares What It Is. Makes possible:
Bytes Framed, Not Rebuilt; A Country Is a Zone.
Composed of: Canonical Data Model, Message Translator and Normalizer (EIP);
ports and adapters.

**Bytes Framed, Not Rebuilt**
Data crosses the line between engine and face as bytes, parsed once and framed
rather than rebuilt. What is carried is not opened, which is what lets
Carrying Is Not Reading be true.
Builds on: Engine and Faces. Makes possible: Carrying Is Not Reading.
Composed of: Envelope Wrapper and Claim Check (EIP).

**A Country Is a Zone**
Which identifier systems establish that a person is who they say they are, and
which brokers may authenticate one, are properties of a country. Building them
into an application is how a product becomes unexportable, so they are declared
as a zone, and a tenant chooses within its zone and may narrow, never widen.
Builds on: Two Parties Bound the Claim; Engine and Faces.

**One Declared Set, Applied**
Value sets, profiles, search parameters, which tenants a deployment serves,
whether a step is automated: one declared set, read from a repository and
applied to a tenant, then the next tenant, then the appliance, without being
rewritten for either. Applying it is itself a run.
Builds on: A Sweep Is Found, Not Started; Engine and Faces.
Composed of: Control Bus (EIP); declarative configuration.

**Status Is a Lifecycle, Not a List**
Declared, provisioned, brought up, served, and one day taken away. Only the
middle of that is a state a running node reports, and the state worth knowing
about is the one a list of served tenants leaves out.
Builds on: One Declared Set, Applied; The Tenant Is a Database.
Composed of: Control Bus (EIP).

## The map

Drawn in Lini like every other figure in the tree, not in Mermaid: the house
compiler already gives the palette, the fonts, the accessible title and the
drift ratchet, and a second diagram language in the same repository would have
none of those.

The source is `patterns/diagrams/the-pattern-map.lini`. It is a grid of the
twenty-four patterns with the links declared as `a -> b` statements and
`routing: orthogonal` set once on the scope, so the figure states the
dependency rather than drawing it by hand. The root is the only sealed box,
because it is the only pattern that rests on nothing.

--8<-- "assets/diagrams/the-pattern-map.svg"

## Every pattern gets an icon

Enterprise Integration Patterns is remembered by its icons, and a
cross-domain vocabulary needs the same: a regulator who has seen the glyph
once recognises the pattern on a slide that is not ours.

The icons are one Lini source, `patterns/diagrams/pattern-icons.lini`, which
is both the specimen sheet and the preset. Lini has no import, so a pattern
page's own figure copies that file's header verbatim — which is already the
convention every figure in this tree follows, since each one restates the
palette for the same reason.

--8<-- "assets/diagrams/pattern-icons.svg"

A glyph is drawn from three marks and no others, which is what makes
twenty-four drawings read as one family:

| Mark | Class | Means |
|---|---|---|
| An open amber stroke | `glyph`, `ring`, `rec`, `bar`, `arrow` | the thing the pattern is about |
| A solid green fill | `solid`, `sdot` | what the store guarantees |
| A faint dashed outline | `ghost`, `ghostline` | what is absent, refused, or merely claimed |

Every icon is the same 72-pixel rounded square, `tile`, laid out as a stack so
its parts are composed with `translate` from the centre rather than placed by
hand. A pattern page opens with its own tile at display size; the sheet above
is what a reader sees on the family's index.

Two constraints worth writing down before the first page, because both cost a
rebuild if they are discovered late. A class name may not shadow a Lini
built-in, so the sheet's caption wrapper is `slot` rather than `cell`. And a
bare string is always centred in its parent whatever `align` says, so any
label that must wrap is wrapped in a block with its own `max-width` — which
is why the map's nodes hold a `lbl` rather than a bare string.

## Where each pattern already lives

| Pattern | Long form today |
|---|---|
| Property, Not Policy | the *Why DBO* index |
| The Tenant Is a Database | `data-isolation/why-a-tenant-is-a-database.md` |
| Work Is the Reason, Nobody Is Pushed, Falls to a Person, A Sweep Is Found | `processes-and-work/why-work.md` |
| The Run Is a Record | `processes-and-work/why-work.md` |
| Two Parties Bound the Claim | `processes-and-work/why-work.md`, `declared-rules/why-zones.md` |
| A Role Is a Period on a Record | `who-may-act/why-one-api.md` |
| A Type Declares What It Is | `records-you-can-rely-on/why-a-type-declares-what-it-is.md` |
| The Trail Is Records, Carrying Is Not Reading, Asked, Not Scanned | `records-you-can-rely-on/why-the-audit-trail.md` |
| Committed with the Change, One Feed, A Cursor That Holds Still | `change-and-who-is-listening/why-subscriptions.md` |
| The Person Is a Key | `data-isolation/why-personal-data.md` |
| Erasure Destroys a Key | `data-isolation/why-erasure.md` |
| Leaving Is the Nightly Path | `data-isolation/why-leaving.md` |
| Engine and Faces | `engine-and-faces/why-engine-and-faces.md` |
| Bytes Framed, Not Rebuilt | `the-payload-seam/README.md` (no essay yet) |
| A Country Is a Zone | `declared-rules/why-zones.md` |
| One Declared Set, Applied | `running-it/why-applying-configuration.md` |
| Status Is a Lifecycle, Not a List | `running-it/why-tenant-status.md` |

Two essays carry four patterns each. That is not a problem for the essays,
which are arguments, but it is the reason the pattern pages are needed: a
reader looking for one idea should not have to read four.

## Open questions

- **Granularity.** Twenty-four patterns is more than a reader holds in mind at
  once and fewer than the concepts actually present. The Change family could
  merge into one pattern; the Work family could split further. The proposal
  errs towards more names, on the grounds that a name nobody uses costs little
  and a missing name costs a meeting.
- **Ordering of the site.** The *Why DBO* index is a narrative; the pattern map
  is a graph. Both should exist, and the index should point at the map once,
  near the top.

## References

The literatures the patterns draw on, so a page can cite one by author and
year without repeating this list.

Foundations of the pattern form

- Alexander, C., Ishikawa, S., Silverstein, M. (1977). *A Pattern Language:
  Towns, Buildings, Construction.* Oxford University Press.
- Hohpe, G., Woolf, B. (2003). *Enterprise Integration Patterns: Designing,
  Building, and Deploying Messaging Solutions.* Addison-Wesley.
- Buschmann, F., Henney, K., Schmidt, D. C. (2007). *Pattern-Oriented Software
  Architecture, Volume 4: A Pattern Language for Distributed Computing.* Wiley.
- Parnas, D. L., Clements, P. C. (1986). "A Rational Design Process: How and
  Why to Fake It." *IEEE Transactions on Software Engineering* SE-12(2),
  251–257.
- Fielding, R. T. (2000). *Architectural Styles and the Design of Network-based
  Software Architectures.* Doctoral dissertation, University of California,
  Irvine. Chapter 5, "Representational State Transfer".

Access granted to a procedure, a task, a purpose

- Clark, D. D., Wilson, D. R. (1987). "A Comparison of Commercial and Military
  Computer Security Policies." *Proceedings of the IEEE Symposium on Security
  and Privacy*, 184–194.
- Thomas, R. K., Sandhu, R. S. (1997). "Task-based Authorization Controls
  (TBAC): A Family of Models for Active and Enterprise-oriented Authorization
  Management." *Database Security XI: Status and Prospects* (IFIP WG 11.3),
  Chapman & Hall, 166–181.
- Atluri, V., Huang, W.-K. (1996). "An Authorization Model for Workflows."
  *Computer Security — ESORICS 96*, LNCS 1146, Springer, 44–64.
- Sandhu, R. S., Coyne, E. J., Feinstein, H. L., Youman, C. E. (1996).
  "Role-Based Access Control Models." *IEEE Computer* 29(2), 38–47.
- Byun, J.-W., Bertino, E., Li, N. (2005). "Purpose Based Access Control of
  Complex Data for Privacy Protection." *Proceedings of the 10th ACM Symposium
  on Access Control Models and Technologies (SACMAT)*, 102–110.
- Byun, J.-W., Li, N. (2008). "Purpose Based Access Control for Privacy
  Protection in Relational Database Systems." *The VLDB Journal* 17(4),
  603–619.
- Park, J., Sandhu, R. (2004). "The UCON_ABC Usage Control Model." *ACM
  Transactions on Information and System Security* 7(1), 128–174.

Process as the primary structure of the information system

- Hollingsworth, D. (1995). *The Workflow Reference Model.* Workflow Management
  Coalition, document TC00-1003.
- Dumas, M., van der Aalst, W. M. P., ter Hofstede, A. H. M. (eds.) (2005).
  *Process-Aware Information Systems: Bridging People and Software through
  Process Technology.* Wiley.
- Russell, N., van der Aalst, W. M. P., ter Hofstede, A. H. M., Edmond, D.
  (2005). "Workflow Resource Patterns: Identification, Representation and Tool
  Support." *Advanced Information Systems Engineering (CAiSE 2005)*, LNCS
  3520, Springer, 216–232.
- van der Aalst, W. M. P., Weske, M., Grünbauer, D. (2005). "Case Handling: A
  New Paradigm for Business Process Support." *Data & Knowledge Engineering*
  53(2), 129–162.
- Nigam, A., Caswell, N. S. (2003). "Business Artifacts: An Approach to
  Operational Specification." *IBM Systems Journal* 42(3), 428–445.
- Hull, R., Damaggio, E., Fournier, F., Gupta, M., Heath, F. T., Hobson, S.,
  Linehan, M., Maradugu, S., Nigam, A., Sukaviriya, P., Vaculín, R. (2011).
  "Introducing the Guard-Stage-Milestone Approach for Specifying Business
  Entity Lifecycles." *Web Services and Formal Methods (WS-FM 2010)*, LNCS
  6551, Springer, 1–24.
- Dietz, J. L. G. (2006). *Enterprise Ontology: Theory and Methodology.*
  Springer.
- Weske, M. (2019). *Business Process Management: Concepts, Languages,
  Architectures.* 3rd edition, Springer.
- Object Management Group (2016). *Case Management Model and Notation (CMMN)*,
  version 1.1.

Change as a log, delivery without a broker

- Kreps, J. (2013). "The Log: What every software engineer should know about
  real-time data's unifying abstraction." LinkedIn Engineering.
- Richardson, C. (2018). *Microservices Patterns.* Manning. Chapter 3, the
  transactional outbox.

Erasure and custody of the person

- Boneh, D., Lipton, R. J. (1996). "A Revocable Backup System." *Proceedings of
  the 6th USENIX Security Symposium*, 91–96.
- Regulation (EU) 2016/679 (General Data Protection Regulation). Article
  5(1)(b) purpose limitation; Article 17 right to erasure; Article 20 right to
  data portability; Article 30 records of processing; Article 32 security of
  processing.
