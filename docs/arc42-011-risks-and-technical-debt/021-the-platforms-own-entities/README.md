**Open. The store's own records — runs, the trail, step declarations, what a
sync parked — have a rich write surface and almost no way to ask about them. A
product operating on this store asks those questions constantly and has to
reach for the FHIR surface to do it, which is the door
[item 009](../009-the-step-scoped-api/README.md) wants demoted. Next: agree the
vocabulary, then the bindings.**

# The platform's own entities cannot be asked about

## What this is

There are two kinds of thing in a tenant's database and only one of them has a
way to be asked about.

**Records about people** are reached by performing work. That is the store's
own claim and item 009 is the item for it: a step declares what it needs, a run
names the documents, and the context answers for those and nothing else.

**Records about the store's operation** — a run, a trail entry, a step
declaration, a dead letter, a shredded key — are not care delivery and nobody
performs a step to read one. An operator asks what is stuck. A product shows a
person the work waiting for them. A compliance officer asks who read a record
last March. Today each of those goes through the FHIR surface with
`system/*.read`, which is exactly the credential item 009 says should not be
how anybody reaches anything.

## Where it stands

**The write surface is complete and the read surface is one method.** `Runs`
offers `pipeline`, `of`, `sweep`, `pass`, `item`, `selected`, `fellThrough`,
`claim`, `checkpoint`, `milestone`, `released`, `produced`, `closed`, `reopen`,
`held`, `tally`, `correlated` — and for asking, `lapsed(now)`. There is no way
to ask what is open, what an executor is holding, what fell through, or what a
correlation covers.

**The pattern already exists, written one method at a time.**
`ContentSyncEngine.deadLetters()`, `.shadowedEvents()`, `.origins()`,
`TenantRuntimeManager.tenantStates()`, `.troubles()`, `Runs.lapsed()`. Each was
added when somebody needed it, none of them is called a query surface, and
together they are the shape of one. What is missing is coverage and a name.

## The inventory

What a product operating on this store has to ask, and cannot ask well:

| About | What somebody asks | Today |
|---|---|---|
| Runs | what is open, what this executor holds, what fell through, what a correlation covers, what a run produced | `lapsed` only; otherwise `Task` over FHIR |
| The trail | who acted on what, when, under which run | `AuditEvent` over FHIR |
| Step declarations | what this tenant offers, which are mandatory, who may perform them | the step surface answers one at a time |
| Milestones | where a run got to, which is what an operator stares at when something is stuck | on the run, unqueryable |
| Disclosures | who learned who somebody is, which is a regulated question | recorded, not askable |
| The erasure ledger | what was shredded and how far each got | `PDI_SHRED_LEDGER` records it |
| Provenance | why this tenant holds this object | `origins()`, already there |
| Sync trouble | what is parked, what dead-lettered, what is shadowed | already there, on the engine |

## One vocabulary, several bindings

The vocabulary is Java and that does not make it in-JVM. The runner facade
already has three bindings — in-JVM, the lane's verbs over HTTP, and
`dbo-stream` over the store's own substrate — and the caller cannot tell which
it holds. `HttpLane` is the proof it reads well: `sample/participant` is a jar
that speaks the lane's verbs and contains no HTTP at all.

So the same shape here. The two places a consumer needs this are:

- **A client**, external or the runner's own, which is across a network and
  binds over the surface.
- **Internal logic**, extending the store's lifecycle callbacks, which is
  inside the JVM and binds directly.

Neither should be able to tell, and the guide's chapters should not have to say.
That is what makes this documentable as the store's vocabulary rather than as
one deployment's API.

## The observer seam, and what a client's log is not

The interface is the natural place to let an integrator see what their own
product asked for. It is not the place to log it by default, and the reason is
this store's own rule: logging is never per-request and never carries
identifying data — *a search URL carries `identifier=system|value`*. A surface
that logged every call would breach, in the sample meant to teach the rule, the
rule it teaches.

So: an observer the integrator supplies, off unless they ask for it. And it is
worth saying plainly in the chapter that what a client records is a
convenience and never evidence — the party being audited controls it. The
store's own trail is on the other side of the boundary and cannot be declined
by a client that would rather not write anything down.

## Filtered content dependencies

A tenant's spec declares what it takes from an upstream by TYPE and nothing
else: `Dependency(String name, Set<String> types, boolean face)`. Subscriptions
already carry criteria and topic filters; replication does not, so a tenant
that wants a slice of a zone takes the zone.

Four questions a design has to answer, none of them rhetorical:

1. **A record that stops matching.** It arrived, the upstream edits it, the
   filter no longer covers it. Retracted, or kept? Silence means the dependent
   holds what its own declaration says it should not.
2. **Where the filter runs.** Upstream is efficient and means one tenant
   executing another's query, against the rule that a tenant declares only
   against its direct upstream. Downstream is honest and moves everything
   anyway.
3. **Grain.** Terminology's grain is a CodeSystem together with its value sets,
   and that is a proven promise. A filter matching half a grain breaks it.
4. **Catch-up.** A newly declared dependency catches up from the upstream's
   whole history; a filtered one has to do that through the predicate, over
   content written before the predicate existed.

## What this does to the guide

The guide's Core group is organised by store feature — records, history,
search, references, transactions, validation — and the map says the guide is
the sample application's story. Those are different things, and the difference
shows up first at `search.md`: it walks FHIR's query surface, which FHIR
documents, and teaches the general read door as the way to get records.

So the chapter list changes with this item, not before it:

- **`records.md` and `history.md` are already scenes** and survive: somebody
  arrives, and two people change one record. They have been rewritten over the
  sample and read as stories.
- **`search.md` goes.** What is this store's about it is three claims, and each
  belongs where a story meets it: an unrecognised parameter is refused rather
  than answered more broadly; the CapabilityStatement is generated from what is
  actually served; pages are held by a cursor, so a record written between two
  fetches is not handed to you twice. The rest is FHIR.
- **A chapter for asking the platform** — what work is outstanding, what
  happened to it, who read what — which is this item's vocabulary and does not
  exist yet.
- **The remaining Core chapters are read the same way** before they are
  converted: a chapter that would only exist to walk an API is a chapter that
  should not exist. `references.md`, `transactions.md` and `validation.md` have
  not been read this way yet.

Item 001's last step is the guide rewrite and it waits on item 002; this says
what it should produce when it gets there.

## Steps

1. Agree the vocabulary: what a product asks about runs, the trail and the
   declarations, written as the methods rather than as the tables. The
   inventory above is the input, not the answer.
2. One binding, over the surface, with the existing `Surface` as its shape.
3. The second binding, in-JVM, with a test that the same scene passes through
   both — which is the only thing that proves the caller cannot tell.
4. The observer seam, off by default.
5. The guide chapter, replacing `search.md` in the reading order.
6. Filtered dependencies, separately and last: the four questions first, the
   feature after.

## What this is not

Not a second query language. `Criteria` exists and is what the engine already
answers; this is a named vocabulary over the platform's own types, in the words
somebody asks the question in, so that "what is stuck" is a method rather than
a search anybody has to compose.

Not a reason to widen the general read surface. If anything it narrows what
that surface is for, which is item 009's direction.
