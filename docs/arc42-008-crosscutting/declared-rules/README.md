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
    { "code": "hogwarts.admission.admit", "slots": { "patient": "Patient" } }
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
