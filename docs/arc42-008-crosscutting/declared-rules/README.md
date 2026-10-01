# Declared rules, and how they layer

## What a tenant declares

A tenant is one file. Everything about it that is a contract rather than data
is written there, and nothing about it is discovered by reading a class:

```json
{
  "code": "hogwarts",
  "face": "r5",
  "zone": "rl",
  "pdi": true,
  "audit": { "level": "writes" },
  "retention": { "perType": {
    "Observation": { "keepAtLeast": "P10Y", "removeAfter": "P30Y" } } },
  "dependencies": [
    { "name": "fhir-r5", "face": true,
      "types": ["StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"] },
    { "name": "rl", "types": ["CodeSystem", "ValueSet"] }
  ],
  "steps": [
    { "code": "hogwarts.admission.admit", "slots": { "patient": "Reference(Patient)" } }
  ],
  "types": [
    { "name": "Patient", "identity": "identifier", "systems": ["urn:rl:nid"],
      "handling": "operational" },
    { "name": "ValueSet", "identity": "canonical", "handling": "replicated" }
  ]
}
```

It says four kinds of thing:

- **Who it is** — its code, whether it holds people behind the vault, who
  manages it, and whether it is itself a face root or a jurisdiction.
- **What it serves** — the face it speaks, and the types it admits. A type
  declares how its records are identified (by canonical url, by an identifier
  in a named system, or internally) and what kind of data it is:
  `operational`, which the tenant owns and may edit, or `replicated`, which
  belongs to whoever published it and is read-only here. **There is no default
  for that**, because a default is a silent decision about a hospital's data.
- **Where it gets things** — the tenants it depends on, and per dependency the
  types that stream from it.
- **What it must do** — its audit level, its write discipline, its retention,
  and the jurisdiction whose facts it operates under.

## What fills a slot

A slot says which of four things fills it, in FHIR's own notation:

| Declared | What fills it |
|---|---|
| `Reference(Patient)` | one patient this tenant already holds |
| `Patient` | one patient given with the run, held by nothing else |
| `Reference(Patient)[]` | several of the first |
| `Patient[]` | several of the second |

**Referring is what this store adds.** Whoever asks for a run names a record
without holding it, without being entitled to read it, and without sending it:
the store resolves the reference where the data already is, and whoever
performs the step is handed the object. That is why a worker in another
organisation can be given work about a patient it has never seen and could not
have fetched.

**Giving is for what has no record.** A proposal, a draft, a document that
arrived with the request — there is nothing for a reference to point at. It
travels with the run and is opened by whoever performs it, and this store
writes it nowhere. A step that decides it should be kept says so in its result,
and the tenant writes it then — so *this arrived* and *this was kept* stay two
statements rather than one silent one.

A slot is one or the other and never both, which is why the run records it
without saying which: a string is a reference, an object is one given here, and
an array is several of whichever.

### A reference can be a search

A referred slot is filled by `Patient/01a0…` or by a search:

```json
{ "inputs": { "patient": "Patient?identifier=urn:rl:nid|38102030405" } }
```

Which is what a caller usually has. It knows the person by their national
identifier, not by the id this store happened to give the record, and looking
the id up first would mean a credential for the records surface — which a
worker deliberately does not hold.

**The search is resolved when the run is authored, and the run records what it
matched.** Not when the work is performed: a run that kept the query would be
over whatever matched at the moment somebody got round to it, two performers
could be handed different sets, and the register could not say what was opened.

**Matching none, or several where one was declared, is refused** — with the
count, so the caller can narrow it. A run over whichever record came back first
is a run nobody can account for. A slot declared `Patient[]` takes however many
there are.

**It buys no reach, and a search on an identifying element is refused here
outright.** The narrowing is compiled by the face's own search compiler and run
by the engine, and this door states no purpose and accepts none — so
`Patient?identifier=…` on a tenant behind the membrane is refused, and a
`Purpose-Of-Use` header does not open it.

That is deliberate rather than an omission. On the records surface a stated
purpose is exactly what turns an identifying search into an exact lookup
through the vault; accepting one here would make the step door a way to ask
*is this person here*, on a credential the tenant's own records surface
refuses. Name the record by id, or find it on the records surface with a
credential for that.

## What a step writes

A step's result may carry records for the tenant to hold, and the declaration
says which types it may carry, beside what it takes:

```json
{ "code": "hogwarts.admission.register", "slots": { "patient": "Patient" },
  "writes": ["Patient", "Encounter"] }
```

**The tenant writes them, never the step.** Whoever performs the step holds a
credential for work and none for records, and is given none for this. Its
result says what should be written; the tenant writes it under the run, through
the same path a transaction bundle posted to its records surface takes. So the
profile validates the records, the identity rules hold for them, and what
identifies a person is sealed as it is for any write. All of one result lands
or none of it does, and one record may refer to another by the `urn:uuid` it
was given.

**A type the step does not declare is refused by name**, before any record is
read. A step declaring nothing writes nothing, which is the default for the
same reason a slot has one: reach is granted by what was written down, on the
way out as on the way in.

**A refused result ends the run.** A record the profile rejects, an identity the
tenant already holds, an undeclared type — each would be refused again in the
same words, so the run is not handed back for another attempt. It ends with the
tenant's reason, and the application that asked for it reads the run as
failed, with that reason. A step that crashed or ran out of time is a different
thing and is released, because another attempt may succeed.

## The graph is tenants, and nothing underneath it

**A tenant's upstreams are tenants.** A jurisdiction is a tenant. A face root
is a tenant. Neither is a configuration file, a bundled resource or a
deployment artefact — which is what lets both be versioned, audited, exported
and streamed by the machinery that moves any other content.

A dependency names **the direct upstream only**, and the types that travel from
it. There is no transitive declaration: what reaches a tenant two hops away
arrives because the tenant in between declared it and republished it, so every
edge in the graph is one somebody wrote down.

**Two of those edges mean different things, and a tenant chooses each on its
own.** At most one dependency is marked as the **face chain** — the root a
tenant takes its version's definitions from, which is what a resource *is*. The
jurisdiction chain is what is *true here*: which brokers exist, which
identifier systems people are resolved by, which terminology is official. They
are never the same chain. A tenant may speak R5 in one country and R5 in
another, and the two are the same face and different zones.

## Narrowing, in one direction

Outer declares the set; inner chooses within it and may narrow, never widen.
**The set is the outer party's**, and that is the whole of what the rule
governs.

The same shape governs four unrelated-looking things in this store:

- a **dependency** declares the types that stream, within what the upstream
  publishes — so a tenant takes a face's vocabulary without taking all of it;
- a **zone** declares which brokers exist and a tenant narrows them, and may
  further restrict which it *accepts*;
- a **step** declares who may override it, and precedence selects only among
  those;
- a **credential** and a step intersect to decide what a participant may claim.

In each case the permissive direction requires an act by the party with
standing, and the restrictive direction is always available to the party
underneath. That is what makes a shared deployment safe to join: nothing an
inner party declares can grant it more than the outer party allowed.

Restricting is a **policy, not a capability claim**: a tenant narrowing its
brokers is declining to honour the others, not asserting they do not work.

## What narrowing is not

**The rule is about authority over somebody else's set, not about size.** A
tenant grows freely in its own direction, and none of that is widening:

- it declares **types of its own** — the operational data it holds, which no
  upstream published and none of which it took from anybody;
- it publishes **profiles of its own**, constraining a type its face defines,
  and the store reads which is which from the definitions rather than from the
  shape of a name: a `specialization` introduces a type, a `constraint`
  profiles one;
- it carries **extensions**, which is the mechanism the base standard provides
  for exactly this — a document says more than the base foresaw without the
  base being altered to allow it.

So a tenant may hold far more than its face publishes, and be strictly inside
the rules the whole time. What it may not do is take more of an upstream's set
than the upstream declared, or relax something the upstream fixed. Adding a
constraint of your own is not widening; removing one of theirs is, and that is
the direction the rule closes.

**Which is why the two never conflict.** A profile narrows the documents it
governs while the tenant that wrote it is adding a definition nobody else had
— one is a rule getting stricter, the other is a party holding more of its own
— and reading both as "narrowing" is what makes the rule sound like it forbids
ordinary work.

## Two questions with one mechanism

**What must this tenant do?** — what is remembered about every action, what may
never be unwritten, when things must be gone.

**What does its jurisdiction say?** — which identity brokers exist, which
identifier systems people are resolved by, which terminology is official.

They look like different kinds of thing: one is a party's own regulatory
posture, the other is a fact about where it operates. They share a mechanism,
and that is the concept:

> Rules are **declared**, they are **records**, and they **layer in one
> direction** — the outer scope declares what exists, the inner one chooses
> within it and may narrow, never widen.

Nothing here is a caller convention, a code path somebody remembered to add, or
a setting discovered by reading a class.

## Declared, validated, enforced, published

A tenant's posture is declared as configuration beside the rest of its
definition — which version of a standard it speaks, and then what it must do:

```json
{
  "audit":           { "level": "writes" },
  "writeDiscipline": { "default": "append-only", "perType": { "…": "standard" } },
  "retention":       { "perType": { "…": { "keepAtLeast": "P10Y", "removeAfter": "P30Y" } } }
}
```

Four properties, and each is load-bearing. It is **validated at registration**,
so a nonsensical posture is refused before it governs anything. It is **enforced
by the engine**, so a rejected write is refused with the policy named rather
than by a caller remembering not to try. It is **published** in the description
of what the tenant serves, so a consumer can see the rules it is operating
under. And **changing it is itself an auditable event**, because a policy change
is exactly the thing somebody will later need to place in time.

## What is remembered

Audit has three levels — nothing, every mutation, or reads and searches too —
and the entries are **ordinary records in the tenant's own store**. That is not
an implementation note. It means the trail rides the change stream so anything
can watch access patterns, it is exported and restored with the tenant so
accountability travels with the data, and it is **pseudonymous by construction**:
a trail naming people would be a copy of personal data outside the vault.
Re-identifying a line requires the vault, like everything else.

**The trail is open upward and closed downward.** Applications contribute
business-level events — a report released, an override used, an access refused —
because a trail containing only what the storage engine noticed is not an
account of what happened. But the trust rule is strict: **the caller supplies
the what, and the machinery asserts the who and the when** from the validated
token and its own clock, overriding anything the caller claims. The trail can be
enriched and cannot be forged.

**Audit is unconditionally append-only**, exempt from whatever write discipline
the tenant chose for its own data. No update, no tombstone, under any policy. A
tenant that could relax its own trail would not have one.

## What may never be unwritten

History is immutable by construction; this governs the *current state* surface.
A tenant chooses `standard` — updates and deletes as usual — or `append-only`,
where deletions are refused and correction happens by superseding: the wrong
record stays, marked as entered in error, and the right one follows it. Either
can be set per type, for artefacts that must never change in place once issued.

**Append-only and the right to erasure coexist deliberately, and this is the
resolution of what looks like a direct conflict.** Shredding never rewrites a
record: the record remains and the person evaporates. So statutory retention and
a person's right to be forgotten stop pulling against each other — one is about
the record, the other about the identity inside it.

## When it must be gone

Storage limitation is the third declaration, and it is two-sided:

- **`keepAtLeast`** is the floor. Until it passes, append-only holds *even
  against policy*.
- **`removeAfter`** is the ceiling. Past it, the engine must remove.

They compose without conflict because they govern different actors: append-only
refuses **caller** deletions, while retention removal is **policy execution**. A
record can be undeletable for ten years and un-keepable after thirty, both
declared, both enforced, with no contradiction.

Removal is **the one sanctioned mutation of history**. A scheduled, checkpointed
sweep removes expired versions from state and history, and the removal is itself
audited — what went, when, under which declared rule — without retaining what it
removed.

**A restore replays policy before it serves.** Both the record of what was
shredded and the retention sweep are re-applied first, so an old archive cannot
resurrect what the law required gone. Archives carry their own expiry, so the
file layer obeys the same declaration as the store.

## A jurisdiction is a tenant too

The facts that vary by jurisdiction are not deployment trivia — they decide who
can be authenticated and by what name. So a **zone is a tenant whose declarations
are records**: which identity brokers exist and how they are reached, and which
identifier systems people are resolved by.

Being records rather than configuration files, they are versioned, audited,
exported, and streamed down to dependent tenants through the same machinery that
distributes any other shared content. The jurisdiction chain and the content
chain are one mechanism.

**Secrets are never in the declaration.** A broker record names the broker; the
machinery resolves the credential from custody by that name. A declaration that
carried a secret could not be exported, streamed or read by an operator — all
things a declaration is supposed to be.

**The set is jurisdictional; the choice is organisational.** A region declares
which brokers exist — a jurisdiction may well have several, one for government
bodies and another for the private sector. A tenant then declares which it uses,
and may further restrict which it accepts, which is the narrowing rule above
arriving at authentication.

## Two structural rules underneath

**No code path reads or writes without an explicit tenant context.** Not a
convention — the absence of an ambient default is what makes "which tenant?"
impossible to forget.

**Quotas and rate limits are first-class configuration**, enforced where serving
happens. Fairness between tenants sharing a deployment is declared like
everything else here rather than being discovered when one of them is noisy.

## What this costs

**A posture has to be decided before a tenant serves**, and getting it wrong in
the strict direction is felt immediately — an append-only tenant refuses
deletions its integrator expected to work. That is the correct place to feel it.

**Retention is a promise the deployment must keep running.** The sweep is
scheduled work; a deployment that stops running it is quietly out of compliance
with its own declaration, which is why the sweep is durable work with a record
rather than a cron line.

**Jurisdictional declarations are somebody's job to maintain.** When a national
broker changes, a record has to change — and until it does, authentication in
that zone is running on a stale fact.

**And narrowing content is not yet refused where it should be.** The rule says
the restrictive direction is always available to the party underneath, and for
brokers and steps it is. For the types a dependency streams it is not safe: a
tenant that narrows below what its stored documents were validated against is
refused at declaration; instead it is accepted and discovered at the next
write — the wrong place and the wrong party. Recorded as
[a face toolset of our own](../../arc42-011-risks-and-technical-debt/025-a-face-toolset-of-our-own/README.md).

## Where the detail is written down

- **The exact rules and their proofs** — the policy, zone and tenancy entries in
  the [REQ catalogue](../../arc42-006-runtime/req-catalogue.md).
- **Who the actor in a trail is, and where authority comes from** — [who may
  act](../who-may-act/README.md).
- **Why a trail can be exact and still contain no personal data**, and what
  shredding does — [data isolation](../data-isolation/README.md).
- **How declarations reach dependent tenants** — [change, and who is
  listening](../change-and-who-is-listening/README.md).
- **How a trail is rendered** for a reader of a particular standard — [work
  through a FHIR face](../the-fhir-face/README.md).
