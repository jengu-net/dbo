# A neutral IFC repository, and what it would take

**Status: not scheduled.** Recorded because the reasoning is worth having on paper before
somebody needs it, in the same spirit as the tier-2 search design. Nothing here is a
commitment, and the two questions in *What a spike would answer* are what decide whether it
is ever more than a document.

## The problem this would solve

IFC (ISO 16739) is a capable, versioned, standardised model for buildings. In practice its
interchange works far worse than the standard allows, and the reason is not technical: the
parties exporting and importing it compete, and each authoring tool has an interest in its
own ecosystem. What arrives is a dialect — the same building decomposed differently, with
the tool's real knowledge left behind on export.

So a party either stays inside one vendor's world, or accepts loss at every boundary.
Neither is a technical problem anybody has failed to solve. It is a **structural** one: no
participant can be the exchange point, because every candidate is an interested party.

That is the shape of thing a neutral store with governed processes over it can fix, and it
is the same shape as a regulated clinical exchange — a strong standard, divergent
interests, and a regulator or buyer who needs the exchange to happen anyway.

## Why this engine fits it

Most of what such a repository needs already exists here, built for another domain:

| Need | What it is here |
| --- | --- |
| federation, and conflicts a human resolves | content sync with shadowing — a local decision that differs parks rather than being overwritten |
| a supplier's model, held but not corrected | `Handling.mirrored` — somebody else's publication, read-only here, held as published |
| registry data published to many parties | `Handling.replicated` and the zone dependency it streams over |
| requirement checking | tenant-authored profiles; IDS is a profile in all but name |
| provenance a regulator can rely on | versioned history, append-only audit, custody |
| personal data in a building model | the membrane and coarsening — `IfcOwnerHistory` and `IfcPerson` carry names and addresses |
| assembling a model from stored parts | the grain codec: stored form smaller than the transported form |

The last row is the load-bearing one. With no query language in the domain (see below),
model assembly *is* the read path.

## The idea that makes round-trip fidelity possible

Round-trip fidelity is the whole product: a repository that loses a party's work on the way
through is one nobody uses twice. The usual assumption is that a neutral store must reduce
everything to the standard's common denominator, and that assumption is what makes neutral
stores lossy.

It is not necessary here, because **nothing says a stored object may carry only what the
standard defines**. The payload is the truth and projections are derived from it, so an
object can hold:

- the **shared core** — what the standard defines and every party can read;
- **tool-specific data the standard can express**, as property sets, which is IFC's own
  extension mechanism and therefore discoverable the IFC way rather than as a local
  convention;
- **tool-specific data the standard cannot express**, preserved as declared opaque extras.
  The store keeps what it cannot interpret, exactly as it holds another authority's
  imperfect publication rather than tidying it.

**What is shared is then an edge's declaration, not the store's decision.** A party
configures what leaves its edge; the same declaration tells it how to merge an incoming
change back into its own richer internal model. The machinery for that is the membrane —
what may travel, and how it is reduced before it does — pointed at commercial
confidentiality instead of at personal data. It does not care which motive it serves.

Above that sits the one rule an edge does not get to make: **a zone can require a minimum**.
The zone publishes the required shape, the party declares what else it shares, and a
submission is validated against the zone's requirement. That is the health pattern
unchanged — a zone publishes terminology and profiles, tenants validate against them.

### Where extensions genuinely help, and where they do not

They solve **extra**: anything a tool knows that the standard has no slot for.

They do not solve **different**: two tools that both model a wall, one as a single element
and one as three layers, disagree about structure rather than about extra data. That is the
dialect problem proper and it needs agreed canonicalisation, or a requirement specification
both sides conform to. Extensions preserve; they do not reconcile.

Parametric intent is the honest limit. A constraint binding one element to another is not
expressible in the standard at any level of extension. The originating tool's own
representation can be preserved so that *it* loses nothing, but no other party can act on
it.

### Why the limit matters less than it sounds

For design collaboration, parametric loss is the whole difficulty. For **regulatory work it
is close to irrelevant**: a permitting authority needs the declared building, its
provenance, and its conformance to requirements — not the designer's parametric intent.

So the value is highest precisely where the hard problem is absent, and the crowded part of
the market is the part the limit constrains.

## What the domain does to this repository's contract

Written up in full on the inward-contract issue; the two findings that matter here:

- **The object boundary is a decision this domain forces and FHIR never did.** Only rooted
  entities carry a stable identity; geometry entities carry none and number in the
  millions. What counts as one stored object — plausibly a rooted entity plus the
  non-rooted subgraph it owns — has to be decided before anything can be stored, and the
  engine's contract has no row for it because a resource made it invisible.
- **The domain defines no query language, so a face declares none.** That is the capability
  model working rather than failing: a client learns *fetch by identity, fetch the model*,
  which is the domain's own API. It does mean model assembly is the only read path, and it
  raises a question the contract has not had to answer — whether the engine's own search
  must be withheld for a tenant whose face declines to translate queries, since an
  engine-native query path would be a convention no client should have to learn.

## What a spike would answer

Two questions decide this, and neither is architectural:

1. **Does identity survive a round trip?** Extensions can only reattach to an object that
   keeps its identifier through export and re-import. Exporters that regenerate identifiers
   break the whole model, and this is where real projects fail. Test with one real model
   through one open, standard-native editor.
2. **What is the object boundary, on a real file?** Decide it against a genuine model rather
   than a diagram, and measure what the decision costs: objects per model, bytes per
   object, and what a single edit touches.

A third, if the first two pass: **what marks an extra stale?** When a shared core changes in
a way a preserved extra depended on, keeping it silently is worse than losing it. That is a
rule to design, not one that is inherited.

## What would have to be true commercially

- The open, standard-native tools can round-trip genuinely; the proprietary ones realistically
  give a good export and a lossy import. Design it as two tiers rather than discovering it
  as a disappointment.
- Prior attempts at model servers with per-entity storage and revisions exist and did not
  take the market. What is different now — mature open tooling built directly on the
  standard, requirement specification becoming a real standard, and public mandates
  creating a buyer who must have provenance — is an argument that has to be made, not
  assumed.
- Neutrality is a governance property, not a technical one. An exchange point owned by a
  participant is not trusted as one, which constrains who may operate it and how it may be
  paid.
