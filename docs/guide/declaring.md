---
title: Declaring, and what handling means
eyebrow: Guide
standfirst: >-
  A type is declared with what identifies one of it and how it is handled —
  and handling is the half that decides who may write it, which is not a
  comment on the file but a rule the engine applies.
template: essay.html
---

[Tenants](tenants.md) showed a tenant as one file, and [Records](records.md)
showed what `identity` does. This is the other half of a type declaration, the one that is
easy to read past:

```json
{ "name": "Patient", "identity": "identifier",
  "systems": ["urn:rl:nid"], "handling": "operational" }
```

`handling` is not documentation. It decides who is allowed to write this type
in this tenant, and the engine enforces it whatever credential is asking.

## Every record says how it is handled

You have seen this already without being told what it was. Every record comes
back carrying it:

```json
"meta": { "security": [ { "system": "urn:dbo:handling", "code": "operational" } ] }
```

`operational` means the tenant's own people and systems author it. That is the
handling a patient, an observation, an encounter has — the records the
organisation creates in the course of doing its work.

## Replicated means somebody else publishes it

The hospital declares `CodeSystem` too, but not as `operational`:

```json
{ "name": "CodeSystem", "identity": "canonical", "handling": "replicated" }
```

The terminology in [the agreed vocabulary](zone-terminology.md) arrived that
way, from the zone. So what happens if
the hospital writes one itself?

```bash
--8<-- "docs/guide/examples/check.sh:replicated-refused"
```

```json
{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"forbidden",
 "diagnostics":"CodeSystem: refused by the read-only-here rule — it is published
  by SOURCE_TENANT and only that lane may write it; an edit made here would be
  silently overwritten by the next sync, or silently kept"}]}
```

Read the reason rather than the refusal. Both outcomes it names are bad and
neither is detectable from the outside: an edit that survives until the next
sync and then vanishes, or one that stays and makes this tenant quietly
disagree with the zone about what a code means. A store that accepted the write
would be choosing which of those to inflict.

**This is the same declaration doing both jobs.** The hospital said where its
code systems come from, and that sentence is what makes them arrive *and* what
stops them being edited here. There is no second place where "read-only" was
configured.

## Choosing one

The two you will declare are the two above. A type is `operational` when this
tenant is where it is authored, and `replicated` when it is authored somewhere
else and this tenant holds a copy because it declared a dependency on it.

Getting it wrong is not subtle. Declare `operational` for something a zone
publishes and the sync has two authors for one record; declare `replicated` for
something your own users create and they cannot write it at all. The engine
tells you immediately in the second case, which is the safer of the two
mistakes to make.

## What you would otherwise have written

A `read_only` flag on a table, honoured by the four services that remembered
and not by the fifth.

Then a sync that overwrites local edits, and the support ticket that says *the
code I changed keeps reverting* — answered by explaining that the field is
managed upstream, which is a thing the system knew and never said.

And the opposite ticket a year later, from the tenant that has quietly been
running with a locally edited copy of a national code list, which nobody
noticed because the edit stuck.
