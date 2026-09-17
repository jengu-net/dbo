---
title: Terminology
eyebrow: Guide
standfirst: >-
  Code systems and value sets are records like any other — written, read,
  searched and versioned — and the codes a write is validated against are
  those records, not a table somebody loaded.
template: essay.html
---

Chapter six ended on a refusal you could not fully explain. The store said a
code was not in a value set and named the value set by url and version. This
chapter is where those come from, and why you did not have to install anything
for it to happen.

The short version: **terminology is records**. A code system is a resource you
write, read, search and version like a patient. There is no terminology table,
no lookup service to deploy, and no second copy to keep in step.

## Writing one

A tenant that declares `CodeSystem` and `ValueSet` can hold them. In this
world that is `rl` — why it is the one holding them is the subject of the
zones chapter; here it is simply a tenant with the types declared.

```bash
--8<-- "docs/guide/examples/check.sh:zone-publishes"
```

```
201
201
```

Two ordinary writes. Both types are declared with `identity: canonical`, which
chapter four covered: they are identified by their `url`, so writing one twice
replaces it rather than making a second.

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

## The standard's own terminology is no different

The value set that refused `purple` in chapter six is not a special case:

```bash
--8<-- "docs/guide/examples/check.sh:core-terminology"
```

```json
{"resourceType":"Parameters","parameter":[
  {"name":"name","valueString":"http://hl7.org/fhir/administrative-gender"},
  {"name":"display","valueString":"Female"}]}
```

That code system is in the tenant, as records, and the validator read it there.
Which is also why the insurer validates against R4's copy of that value set and
the hospital against R5's, with no flag passed by any caller: they hold
different records.

The store's own vocabularies are records too — the codes for handling, for
audit events, for processes, steps and runs. Everything the store says about
itself, it says in the shape it asks you to use.

<div class="further" markdown>
This chapter is the mechanism: what a code system is, and what it answers.
*Whose* codes they are, how a jurisdiction publishes them and how they reach
the tenants that agreed to take them, is the zones chapter.
</div>

## What you would otherwise have written

A terminology table, and the import job that fills it, and the question of
which release of which code system it currently holds.

Then the second copy, because the validator needs one too, and the drift
between them that nobody notices until a code validates in one place and not
the other.

And a lookup service in front of both, with its own availability, so that a
write can fail because terminology is down rather than because the code was
wrong.
