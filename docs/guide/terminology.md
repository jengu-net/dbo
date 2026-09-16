---
title: Terminology
eyebrow: Guide
standfirst: >-
  Code systems and value sets are records like any other. A zone publishes
  them, a tenant declares that it takes them, and the codes it validates
  against are the ones somebody deliberately gave it.
template: essay.html
---

Chapter six ended on a refusal you could not fully explain. The store said a
code was not in a value set and named the value set by url and version. This
chapter is where those come from, and why you did not have to install
anything for it to happen.

The short version: **terminology is records**. A code system is a resource you
write, read, search and version like a patient. There is no terminology table,
no lookup service to deploy, and no separate copy to keep in step.

## A zone publishes, because that is what a zone is for

`rl` — Rowling Land — is a tenant that exists to hold the rules everyone in
that jurisdiction shares. Give it a code system and a value set over it:

```bash
--8<-- "docs/guide/examples/check.sh:zone-publishes"
```

```
201
201
```

Two ordinary writes. The zone declares `CodeSystem` and `ValueSet` with
`identity: canonical`, which chapter four covered: these are identified by
their `url`, so writing one twice replaces it rather than making a second.

## It answers questions, not just stores documents

A code system that is only stored is a document. Ask it something:

```bash
--8<-- "docs/guide/examples/check.sh:zone-lookup"
```

```json
{"resourceType":"Parameters","parameter":[
  {"name":"name","valueString":"urn:rl:wards"},
  {"name":"display","valueString":"Dai Llewellyn Ward"}]}
```

And a value set composed over it expands to the concepts it includes:

```bash
--8<-- "docs/guide/examples/check.sh:zone-expand"
```

```
4 concepts
  creature Creature-Induced Injuries
  dai Dai Llewellyn Ward
  potion Potion and Plant Poisoning
  spell Spell Damage
```

The expansion was computed from the concepts as records. Nothing was
pre-rendered at write time, so a code added to the system is in the next
expansion without a rebuild step.

## A tenant takes what it declared, and nothing else

Here is the part that matters, and it is a single line in a file. The hospital
declares the zone as a dependency:

```json
"dependencies": [
  { "name": "fhir-r5", "face": true,
    "types": ["StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"] },
  { "name": "rl", "types": ["CodeSystem", "ValueSet"] }
]
```

Having declared it, the hospital answers the zone's codes as its own:

```bash
--8<-- "docs/guide/examples/check.sh:zone-reaches-hospital"
```

```json
{"resourceType":"Parameters","parameter":[
  {"name":"name","valueString":"urn:rl:wards"},
  {"name":"display","valueString":"Spell Damage"}]}
```

The insurer is in no zone and declared no such dependency. It gets nothing:

```bash
--8<-- "docs/guide/examples/check.sh:zone-not-at-insurer"
```

```
0 entries
```

**This is the whole governance model in one contrast.** Being near the zone
brings nothing. Being told to take `CodeSystem` and `ValueSet` from it brings
exactly those, and only those. A dependency on a type you did not name brings
nothing however much of it the zone holds — so a tenant's content is a
consequence of its declaration and never of what happened to be nearby.

The direction matters too. The zone does not push to its members and does not
know who reads it. Each tenant pulls what it declared, which is why adding a
tenant to a zone changes nothing about the zone.

## The standard's own terminology arrives the same way

The value set that refused `purple` in chapter six is not special:

```bash
--8<-- "docs/guide/examples/check.sh:core-terminology"
```

```json
{"resourceType":"Parameters","parameter":[
  {"name":"name","valueString":"http://hl7.org/fhir/administrative-gender"},
  {"name":"display","valueString":"Female"}]}
```

That came from the face root — `fhir-r5`, a tenant holding the R5 definitions
as records — by the same dependency mechanism as the zone's houses. One
mechanism, two sources: the standard's terminology and the jurisdiction's,
neither of them built in.

Which is why the insurer validates against R4's copy of that value set and the
hospital against R5's, with no flag passed by the caller. They read from
different face roots, and a face root is a tenant.

The store's own vocabularies are records too — the codes for handling, for
audit events, for processes, steps and runs. Everything the store says about
itself, it says in the same shape it asks you to use.

## Propagation, stated plainly

A tenant's first sync from its zone runs some minutes after the tenant comes
up, not immediately. After that, a change in the zone reaches its tenants in
about a second.

So terminology is eventually consistent by design, and you should treat it as
such: a code published in the zone this instant is not guaranteed to validate
in a member tenant this instant. In practice terminology changes on the
timescale of committees, not requests, which is why this is a sensible
trade — but it is a real property and worth knowing before you build a flow
that publishes a code and immediately uses it.

## What you would otherwise have written

A terminology table, and the import job that fills it, and the question of
which release of which code system it currently holds.

Then the second copy, because the validator needs one too, and the drift
between them that nobody notices until a code validates in one place and not
the other.

Then the distribution problem: a national code list updates, and every
deployment needs it, so you write a sync — and now you own a sync, its
retries, its ordering, and the question of what a tenant is allowed to
receive. Here that last question is the only one you answer, once, in the
tenant's own file.
