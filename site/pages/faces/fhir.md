---
title: The FHIR face
eyebrow: Faces
standfirst: >-
  If you know FHIR and nothing about this store, this is the page. It is a real
  FHIR server — and the places it differs from the one you have used are all
  places where it is stricter or more honest, never looser.
template: essay.html
---

R4 and R5, each served through one implementation that reads instances against
StructureDefinitions rather than into generated classes. A version is a
definition package and a code, which is why versions released years apart are
the same code path.

A tenant declares which version it serves. Different tenants can differ; one
tenant gets one face.

## Resources are the model, including for the parts that usually are not

`Person`, `Practitioner`, `Organization` and `PractitionerRole` are the
identity and authorisation model rather than records that describe it. A
human's access derives from an active `PractitionerRole`, and revoking it is
ending a period.

Where FHIR has no resource — client applications, signing keys, role grants —
the store uses a regular versioned record in the tenant's own store rather than
an admin plane. There is no second vocabulary and no second surface to secure.

## Search is strict, and the CapabilityStatement is honest

An unsupported search parameter is a `400`. Not an empty result, and not a
quietly broader one — because a client that believes it filtered and did not is
the failure worth preventing.

The CapabilityStatement is generated from what is actually served: an operation
is declared because it is routable, from the same list the router dispatches
on. So a served-but-undeclared operation and a declared-but-unanswered one are
both inexpressible.

<div class="takeaway" markdown>
Search is tier 1 by design. The tiers are sized against
[an inventory of every search interaction a production healthcare platform
actually issues](../docs/evidence/search-usage-inventory.md) — roughly 206 call
sites — rather than against the specification's full surface.
</div>

## Terminology answers or refuses

A coded value is checked against the terminology the tenant holds, and the
answer follows the binding's declared strength. A required binding violated is
a refusal; weaker bindings are advice the caller is given rather than refused
for.

A code from a system nobody holds is reported as **unresolvable**, never as
invalid. One says this store's content is incomplete and the other says your
data is wrong, and they are fixed by different people.

## What you will not find in a Patient

Identifying elements are encrypted inside the payload, so what a read returns
depends on what the reader may see. That is not a projection applied on the way
out — it is what is stored.

`AuditEvent` is served from the tenant's own native audit records, rendered per
personality on read. You can contribute business events to it; the machinery
stamps the actor and the time itself, overriding whatever you claimed.

## Conformance, generated rather than asserted

Each version's page is produced by driving the real HTTP surface against a real
database, so a claim there is something the server was seen to do rather than
something it says about itself.

- [FHIR R4 conformance](../docs/conformance/r4.md)
- [FHIR R5 conformance](../docs/conformance/r5.md)

A rule marked out of scope is a boundary drawn on purpose, and the
CapabilityStatement declares the same boundary.

<div class="further" markdown>
The full treatment — every concept in the store as a FHIR client meets it,
including work as `Task`, export, and what has no FHIR expression at all — is
[The FHIR face](../docs/arc42-008-crosscutting/the-fhir-face/README.md).
</div>
