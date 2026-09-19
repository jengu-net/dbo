---
title: "Rules the code cannot skip"
headline: "A type declares it; the engine enforces it"
eyebrow: Why DBO
standfirst: >-
  Two organisations that compete will use a shared store only if they can rely
  on what it holds. That reliance is five properties, each of which can fail on
  its own while the others look fine.
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

That the answers are declared once per type rather than left to each writer is
[A Type Declares What It Is](../patterns/pattern-a-type-declares-what-it-is.md).
What the five reliances actually are, and what this store declares to get
them, is the rest of this page.

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

One label is not the tenant's to choose. Audit is append-only against everyone,
the vendor included — there is no interface that edits an entry, because an
audit trail somebody can edit is a document rather than evidence. That is
[The Trail Is Records](../patterns/pattern-the-trail-is-records.md).

## One type, one owner

The label also names who the type belongs to, and the refusal quotes that owner
back at whoever tried: *published by the configuration lane, and only that lane
may write it — an edit made here would be silently overwritten by the next
sync, or silently kept.* That sentence is the rule. It is worth reading twice,
because it is only true of a type whose records all come from one place.

Real things often have more than one writer. A bench analyser is written down
in the configuration repository by an operator; a box dials in and is enrolled
by the platform once a human approves it; a driver finds a third on the network
and records that it is there. It is tempting to read that as one type with
three writers and a rule too strict for two of them.

It is not. Those are three different statements about the world, and they
differ in what makes them true rather than in who happened to type them. A
declaration says *this ought to be here*, and it belongs to the repository that
declares it — correcting it anywhere else is the edit the refusal describes. An
observation says *this is here*, and nobody authors it: it is true because
something looked, and it stops being true when the thing goes away. They have
different owners, different lifetimes, and different answers to what a
contradiction between them means.

So they are different types, and each says what it is. The declared one is the
repository's and is read-only here; the observed one is observed, and whatever
sees the world writes it. A declaration may seed an observed record, or be what
approves one, or sit beside it unmatched — which is a state worth being able to
represent, because a device that was declared and never appeared is exactly the
thing an operator wants to know about.

Collapsing them costs more than it looks. Relaxing the rule to fit the widest
writer removes the protection from the declared half and leaves the refusal's
sentence false for the records it no longer applies to. Attaching the owner to
each record instead of the type sounds tidier and is worse: what a caller may
write would then depend on which record it reached rather than on what the type
declared, so nothing could be known about a write before making it — and being
able to know that, from the declaration alone, is the whole of what the label
is for.

<div class="further" markdown>
The five reliances, the physical layout that makes the payload-and-derivation
split visible, and the shape-governance rules are in
[Records you can rely on](README.md).
</div>
