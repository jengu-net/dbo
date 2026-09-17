---
title: The agreed vocabulary
eyebrow: Guide
standfirst: >-
  A jurisdiction publishes its codes once, and they reach exactly the tenants
  that declared they would take them — and exactly the types those tenants
  named.
template: essay.html
---

The terminology chapter in Core covered the mechanism: a code system is a
record, and it answers questions. This one is about whose codes they are.

`rl` — Rowling Land — is a tenant that exists to hold the rules everyone in
that jurisdiction shares. It publishes; it does not push. Nothing leaves it
because a tenant is nearby, and the zone does not know who reads it.

## A tenant takes what it declared

The hospital declares the zone as a dependency, naming the types it wants:

```json
"dependencies": [
  { "name": "fhir-r5", "face": true,
    "types": ["StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"] },
  { "name": "rl", "types": ["CodeSystem", "ValueSet"] }
]
```

Having declared it, the hospital answers the zone's codes as its own:

```bash
--8<-- "docs/guide/examples/snippets/zone-reaches-hospital.sh"
```

```json
{"resourceType":"Parameters","parameter":[
  {"name":"name","valueString":"urn:rl:wards"},
  {"name":"display","valueString":"Spell Damage"}]}
```

The insurer is in the same zone and declared only `CodeSystem` from it — not
`ValueSet`. So it has exactly that, and not the other:

```bash
--8<-- "docs/guide/examples/snippets/zone-partial-at-insurer.sh"
```

```json
{"resourceType":"Parameters","parameter":[
  {"name":"name","valueString":"urn:rl:wards"},
  {"name":"display","valueString":"Spell Damage"}]}
```
```
0 value sets
```

The code system arrived. The value set, sitting beside it in the same zone,
under the same dependency, did not — because nobody asked for it.

**That is the governance model in one contrast.** Two tenants, one zone, and
what each holds is decided by its own file. A type you did not name brings
nothing however much of it the zone holds, so a tenant's content is a
consequence of its declaration and never of proximity.

The direction matters as much. Because members pull rather than the zone
pushing, adding a tenant to a zone changes nothing about the zone — and a zone
cannot quietly place content in a tenant that did not ask for it.

## Propagation, stated plainly

A tenant's first sync from its zone runs some minutes after the tenant comes
up, not immediately. After that, a change in the zone reaches its members in
about a second.

So terminology here is eventually consistent by design, and you should treat it
as such: a code published in the zone this instant is not guaranteed to
validate in a member tenant this instant. In practice terminology changes on
the timescale of committees rather than of requests, which is why this is a
sensible trade — but it is a real property, and worth knowing before you build
a flow that publishes a code and immediately uses it.

## What you would otherwise have written

The distribution problem: a national code list updates, every deployment needs
it, so you write a sync — and now you own a sync, its retries, its ordering,
and its failure modes.

Then the question that sync cannot answer on its own, which is what a given
recipient is allowed to receive. Here that is the only question you answer, and
you answer it once, in the tenant's own file.
