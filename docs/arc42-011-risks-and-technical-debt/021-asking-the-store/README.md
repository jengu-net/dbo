**Open. A product building a screen has no vocabulary for asking this store a
question — not about runs and the trail, and not about the records a list
screen is made of. The in-JVM binding is most of the way there already and
unnamed: a tenant registers its `ObjectStore` on the whiteboard, so what a
consumer gets is an engine handle and `Criteria`. Next: settle what asking is,
as against reaching, then the vocabulary.**

# Asking the store a question

## What this is

A product built on this store has to put things on a screen. A worklist, a
patient list, the devices on a ward, what happened to a run, who read a record
last March. Every one of those is a question, and the store has no vocabulary
for questions — it has an engine handle and a criteria builder, which is a
different thing, and it has the FHIR surface, which is FHIR's vocabulary rather
than this store's.

**The line is not platform against clinical.** That was the first draft of this
item and it does not survive contact with a real application: a list screen is
made of `Patient`, `Observation` and `Device` as surely as an operator's screen
is made of runs. Drawing the boundary there would mean a product uses the named
vocabulary for half its screens and something else for the rest.

**The line is asking against unsealing**, and the store already draws it. A
read by a credential not entitled to unseal answers the record *without* the
identifying elements rather than refusing — proven in the guide, where the
hospital's own service credential writes a person and cannot read her name
back. So a screen can list, filter, count and page without learning who anybody
is; learning who somebody is is a disclosure, and that is where a purpose and a
run belong. [Item 009](../009-the-step-scoped-api/README.md) is about reaching
a person's data, and reaching is what stays behind a step.

That distinction is what makes a query vocabulary safe to give a product, and
it needs stating before anything is built, because the obvious reading of item
009 is that no general query should exist at all.

## Where it stands

**The in-JVM binding is already registered, and it is raw.** A tenant coming up
puts `ObjectStore`, `FhirStoreFacade`, `ChangeFeed` and its `Lanes` on the OSGi
whiteboard, keyed by `tenant` and `fhir.version`. A bundle inside the framework
takes what it needs from the registry, and a host that embedded the store
reaches the same registry — the shared dependencies are Felix and the OSGi API
and nothing else. So the mechanism a consumer would use exists and is proven;
what it hands back is an engine and a criteria builder.

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

## What a host outside Felix constrains

A host that embeds the store shares Felix and the OSGi API and nothing else, so
every type the query vocabulary hands back has to come from a package the
framework exports. A vocabulary whose answers are built from a bundle's private
types looks right inside the container and is unreachable from the host that
booted it — which is this repository's characteristic defect, arriving in a new
place: it compiles, it resolves, it publishes, and it dies on first use.

So the types are part of the design rather than a detail of it, and the proof
is a host outside the framework asking a question and reading the answer.

## One vocabulary, several bindings

The vocabulary is Java and that does not make it in-JVM. The runner facade
already has three bindings — in-JVM, the lane's verbs over HTTP, and
`dbo-stream` over the store's own substrate — and the caller cannot tell which
it holds. `HttpLane` is the proof it reads well: `sample/participant` is a jar
that speaks the lane's verbs and contains no HTTP at all.

So the same shape here. The places a consumer needs this are:

- **A client**, external or the runner's own, which is across a network and
  binds over the surface.
- **An application building its screens**, which is the case that made this
  item wider than its first draft.
- **Internal logic**, extending the store's lifecycle callbacks or running as a
  bundle beside the tenant, which takes the service off the whiteboard.
- **A host that embedded the store**, which takes it off the same whiteboard
  from outside the framework.

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

## What it looks like from the consumer's side

Sketches, not a specification: the point of writing them now is that a
vocabulary is easy to argue about in the abstract and stops being arguable the
moment somebody reads a screen's worth of it. Names will move.

**One tenant, one way in, and the binding is not the caller's business.**

```java
// Inside the framework, or in a host that embedded the store: off the
// whiteboard, where the tenant already put it.
Asking hogwarts = Asking.at(store);           // an ObjectStore from the registry

// Across a network: the same vocabulary over the tenant's surface.
Asking hogwarts = Asking.over(surface);       // a Surface, holding a token
```

Nothing below changes between those two lines, which is the whole claim. A
lifecycle callback and a product in another building write the same code.

**A screen of work.** These are questions somebody actually asks, so they are
methods rather than searches anybody composes.

```java
try (Stream<Run> waiting = hogwarts.work().open().stream()) {
    waiting.limit(20).forEach(screen::add);
}

try (Stream<Run> stuck = hogwarts.work().lapsed().stream()) { ... }
try (Stream<Run> refused = hogwarts.work().fellThrough().since(yesterday).stream()) { ... }

Journey journey = hogwarts.work().journeyOf(runKey);   // milestones, in order
```

**A screen of records**, which is the half that made this item wider than its
first draft. A ward list is `Observation` and `Device`, and it is still asking.

```java
try (Stream<Stored> ward = hogwarts.records("Observation")
        .where("subject", "Patient/" + id)
        .newestFirst()
        .stream()) {
    ward.limit(50).forEach(screen::add);
}

// NOT stream().count(). A count is asked for without fetching anything,
// which is a question the store answers directly and a terminal operation
// would answer by dragging every row across to be counted here.
long active = hogwarts.records("Device").where("status", "active").count();
```

What comes back carries no identifying elements, because asking is not
unsealing. A list screen shows what it is entitled to and no name appears in it
unless somebody asks for one:

```java
// A different act, and it says why. It leaves a disclosure behind, and it
// cannot be done by a credential holding only the right to ask.
Person person = hogwarts.identify(pseudonym, Purpose.of("treatment"), run);
```

**Nothing is buffered and paging does not appear.** The store already writes a
page as it is produced — memory is one member rather than one page, and a
reader sees the first byte before the last row is read — so a vocabulary
handing back pages would be buffering on top of something that deliberately
does not. The stream follows the cursor as it is consumed, which is also what
keeps the store's promise that a record written between two fetches is not
handed over twice: the cursor is still doing the work, the caller has simply
stopped having to hold it.

Three things that follow, and each is a way to get this wrong:

**It is closed, and it is a resource.** A stream that is walked away from
leaves whatever is behind it open. Every example here is try-with-resources for
that reason, and the vocabulary should make an unclosed stream hard rather than
possible.

**Narrowing is the store's job, not the stream's.** `where` runs where the
records are; `stream().filter(...)` runs here, after everything has crossed the
wire. Both compile and one of them drags a tenant through a socket, so the
vocabulary has to offer enough `where` that nobody reaches for `filter`.

**A terminal operation is not a question.** `stream().count()` fetches
everything to count it; `count()` asks. Same for "is there any", which is a
question and not `findFirst().isPresent()` over a stream nobody closed.

**The trail**, which today is `AuditEvent` over FHIR:

```java
try (Stream<Entry> whoRead = hogwarts.trail().about(recordId).since(march).stream()) {
    whoRead.forEach(report::add);
}
```

**The observer seam**, off unless the integrator asks:

```java
Asking watched = hogwarts.watching(call ->
        metrics.timed(call.what(), call.took()));
```

Deliberately not `log.info(call)`. A call carries what was asked, and a search
carries `identifier=system|value`; the rule this store keeps is that logging is
never per-request and never carries identifying data. What an integrator does
inside their own observer is theirs, and the chapter should say that what they
record is a convenience rather than evidence — the store's trail is on the
other side of the boundary and cannot be declined.

**And from a host that never entered the framework:**

```java
// Felix and the OSGi API are the only shared dependencies, so this is all a
// host has — and every type above has to be reachable from here or the
// vocabulary is unusable exactly where embedding was supposed to help.
Collection<ServiceReference<Asking>> found =
        context.getServiceReferences(Asking.class, "(tenant=hogwarts)");
```

**What is absent on purpose.** There is no `Asking.everything()` and no tenant
argument anywhere above: one instance is one tenant, so a fleet-wide view holds
several and asks each, which is the walk the deployment chapter describes. And
there is no `where(Criteria)` — the vocabulary is the point, and a door that
took the engine's own criteria would be `ObjectStore` with a longer name.

There is also no `List<...>` anywhere, which is the same decision seen from the
other side: a method returning a list has decided how much to hold before the
caller has said what they want.

One small thing falls out of streams that is worth having: `java.util.stream`
is the JDK's, so a host outside the framework needs only the element type
exported rather than a collection type of ours. The packaging question above
gets smaller, not larger.

## Three things a query vocabulary must not become

**A cross-tenant read.** The registry keys these services by tenant and the
worked deployment says there is no cross-tenant surface — a fleet-wide view
holds one credential per tenant and asks each in turn, which is a walk rather
than a join and is slower on purpose. One service per tenant keeps that true by
construction; a convenience that spanned them would be available to anything
that ever got hold of it.

**A way to count what you cannot see.** The store already refuses an
enumeration at the front door, and the guide proves it. A count is the easiest
place to lose that: a total over records a caller may not read tells them the
records exist.

**`Criteria` as a public API.** It is rich — chained, referencing, ranges,
sorts — and exposing it wholesale makes every part of it a compatibility
commitment and moves the API ledger. The vocabulary is the point: a product
asks *what is stuck* rather than composing a search that means it.

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
- **A chapter for asking** — what work is outstanding, what happened to it, who
  read what, and what goes on the screen — which is this item's vocabulary and
  does not exist yet.
- **The remaining Core chapters are read the same way** before they are
  converted: a chapter that would only exist to walk an API is a chapter that
  should not exist. `references.md`, `transactions.md` and `validation.md` have
  not been read this way yet.

Item 001's last step is the guide rewrite and it waits on item 002; this says
what it should produce when it gets there.

## Steps

1. Settle asking against unsealing, as a crosscutting concept rather than as a
   paragraph here, because item 009 reads as forbidding this until it is
   written down.
2. Agree the vocabulary: what a product asks about runs, the trail, the
   declarations and the records a screen is made of, written as the methods
   rather than as the tables. The inventory above is the input, not the answer.
3. One binding, over the surface, with the existing `Surface` as its shape.
4. The second binding, from the whiteboard, where most of the mechanism already
   is — with a test that the same scene passes through both, which is the only
   thing that proves the caller cannot tell, and a host outside the framework
   asking one question, which is the only thing that proves the types travel.
5. The observer seam, off by default.
6. The guide chapter, replacing `search.md` in the reading order.
7. Filtered dependencies, separately and last: the four questions first, the
   feature after.

## What this is not

Not a second query language. `Criteria` exists and is what the engine already
answers; this is a named vocabulary over the platform's own types, in the words
somebody asks the question in, so that "what is stuck" is a method rather than
a search anybody has to compose.

Not a reason to widen the general read surface. If anything it narrows what
that surface is for, which is item 009's direction.
