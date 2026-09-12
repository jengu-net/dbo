---
title: "Rules the code cannot skip"
headline: "A type declares it; the engine enforces it"
eyebrow: Why DBO
standfirst: >-
  Append-only, versioned, auditable, retained for how long, identified by what.
  Declared once per type and enforced by the engine — not left to the habits of
  whatever code happens to write it.
why: 10
template: essay.html
---

Two organisations that compete will use a shared store only if they can rely on
what it holds. That reliance is not one property. It is several, at different
levels, and each can fail on its own while the others look fine:

the record is the bytes somebody wrote; it is the same record next time; it
says what shape it claims to be; it cannot be quietly changed; and it can be
rebuilt.

Miss any one and the rest stop being worth much. A perfect index over a payload
nobody can reproduce is a rumour. An immutable history of records whose
identity was guessed is an immutable record of a mess.

--8<-- "assets/diagrams/a-type-declares-what-it-is.svg"

<p class="diagram-caption">The behaviours on the right are not things the
writing code opted into. They are consequences of the four lines on the left.</p>

## The payload is the truth

A stored object's payload is the single source of truth, and everything
searchable is derived from it.

That sentence decides an unusual amount. The searchable envelope — the typed
values a query actually runs against — is a *derivation*, so changing how
objects are indexed is a background operation rather than a data migration.
The references between records are extracted as edges when a record is written,
and they are derived too, and rebuildable too.

Only the payload is authoritative. Everything else is a cache with a rebuild
button, which is what makes an index change a Tuesday afternoon rather than a
project.

## Recognising the same thing again

Every mechanism that has to recognise *the same thing again* — a conditional
write, a replicated record arriving twice, an import, a converter — needs one
answer per type, written down rather than inferred from whichever field looked
unique.

So each type declares exactly one identity class:

| Class | Identity is | Typical of |
|---|---|---|
| **Canonical** | a canonical url | definitions: code systems, value sets |
| **Identifier** | designated `{system, value}` pairs, in trust order | things with real-world identity: a person, an organisation, a device |
| **Internal** | the store-assigned id, and nothing else | records with no business identity: an observation, a by-product |

<div class="takeaway" markdown>
Identity excludes every version axis, and there are four of them. The same
logical artefact expressed under two different versions of a standard is the
**same record** — conversion never changes identity, and the engine checks that
after every conversion rather than trusting it.
</div>

## The handling label is what the engine enforces

`operational`, and the others beside it, are not labels for humans to read.
They are what the engine enforces: whether history is kept, whether a write may
replace rather than append, how long the record is retained, whether it leaves
in an export, what an audit entry has to say about it.

The alternative is the arrangement most systems have, where those properties
live in the code that happens to write each type. It works while one team
writes everything. It stops the first time two do, and the way you find out is
an auditor asking why one kind of record has history and its neighbour does
not.

## Audit is append-only against everyone

Including the vendor. There is no interface that edits an audit entry, because
an audit trail somebody can edit is a document rather than evidence.

<div class="further" markdown>
The five reliances, the physical layout that makes the payload-and-derivation
split visible, and the shape-governance rules are in
[Records you can rely on](README.md).
</div>
