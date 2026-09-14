---
title: "A Country Is a Zone"
eyebrow: Pattern
standfirst: >-
  Which identifier systems establish that a person is who they say they are,
  and which brokers may authenticate one, are properties of a country.
  Building them into an application is how a product becomes unexportable.
pattern: 22
template: essay.html
---

**Intent — hold a jurisdiction's facts where they can be swapped, so the next
country is configuration rather than a rewrite.**

## You are

Building for one country first, as everybody does. Its national identifier is
the identifier. Its identity broker is the broker. Those facts are true
everywhere in the codebase because they were true everywhere in the room.

The second country arrives with a different identifier, two brokers instead of
one, and a rule about which kinds of subject may exist at all.

## The question

Where do a jurisdiction's facts live, so that adding one does not mean editing
everything?

## The forces

- These facts are genuinely national, not organisational: no single tenant
  gets to decide which identifier system proves identity in its country.
- They are also not universal, so they cannot go in the engine.
- Tenants within a country legitimately differ: a registry is stricter than a
  hospital, and should be allowed to be.

## Therefore

**Declare them as a zone, and let a tenant choose within its zone.**

--8<-- "assets/diagrams/pattern-a-country-is-a-zone.svg"

<p class="diagram-caption">Three tenants share one zone without being copies
of each other. None of them can widen the set.</p>

The narrowing rule is the one from the claim pattern, applied to jurisdiction:
the outer party declares the set, the inner one chooses within it and may
narrow, never widen. A hospital may accept everything the country allows. A
registry may accept one broker and one identifier system, and that is its
decision to make downward, not upward.

Because zone declarations are themselves records, they move on the same feed
as everything else — so a tenant holding a copy of its zone's declarations is
an ordinary consumer rather than a special case, and a zone that cannot be
served is said so at bring-up rather than discovered by a user failing to log
in.

## What each reader gets

- **A regulator** sees the national rules stated in one place, as records,
  rather than inferred from behaviour.
- **A security officer** reviews which brokers are trusted per country, not
  per deployment.
- **An administrator** brings up a tenant in a new country without a code
  change.
- **The business** enters a second market with configuration and a
  conversation, not a fork.

## Relations

- **Builds on** —
  [Two Parties Bound the Claim](pattern-two-parties-bound-the-claim.md);
  [Engine and Faces](pattern-engine-and-faces.md).
- **Written up in** — [Declared rules](../declared-rules/README.md). Proven by
  `REQ-DBO-ZONE-DECLARATIONS-AS-RECORDS`, `REQ-DBO-ZONE-BROKER-CHOICE`,
  `REQ-DBO-ZONE-SUBJECT-DOMAINS` and
  `REQ-DBO-ZONE-AN-UNSERVABLE-ZONE-IS-SAID-AT-BRING-UP`.
