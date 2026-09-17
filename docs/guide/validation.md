---
title: Validation
eyebrow: Guide
standfirst: >-
  Every write is checked against the definitions the tenant holds as records,
  and each face checks against its own release. You can ask for the verdict
  before you commit to it.
template: essay.html
---

A record that is wrong in a way nobody noticed is worse than a write that
failed. It sits there looking like data, it is exported, it is reported on, and
the mistake is found by the person least able to do anything about it.

So every write here is validated before it lands. You did not switch that on
and you cannot forget to call it, which is the point: validation you have to
remember is validation somebody eventually does not.

## A code outside its binding is refused

The hospital declares `Patient`. FHIR declares that `Patient.gender` takes a
code from a particular value set. Write something else:

```bash
--8<-- "docs/guide/examples/check.sh:validate-binding"
```

```
422
error | ERROR Patient.gender: The value provided ('purple') was not found in the
value set 'AdministrativeGender' (http://hl7.org/fhir/ValueSet/administrative-gender|5.0.0),
and a code is required from this value set (error message = The code 'purple' is not in
the value set 'http://hl7.org/fhir/ValueSet/administrative-gender'
(answered from this tenant's terminology))
```

`422`, and nothing was written — the record does not exist at either version,
because a refused write is not a partial one.

Read what the outcome names. **The element**, so you know where to look, not
merely that the resource was bad. **The value set**, by url. **Its version**,
which is the part that matters in a minute. And **where the answer came from** —
*this tenant's terminology*, which is the subject of
[Terminology](terminology.md).

## Shape is checked, not only codes

The same 422 covers a value that is not of the declared type, and an element
the definition requires and you did not send:

```bash
--8<-- "docs/guide/examples/check.sh:validate-shape"
```

```
422
422
```

The first is a `birthDate` that is not a date. The second is an `Observation`
with no `status` — required by its definition, absent from the body, so the
write is refused with `minimum required = 1, but only found 0`.

None of this is a rule this store wrote. It is the definition the tenant holds
as records, read at write time.

## Each face validates against its own release

Here is the part that is hard to retrofit. The hospital speaks R5, the insurer
speaks R4. Send the identical mistake to both:

```bash
--8<-- "docs/guide/examples/check.sh:validate-versions"
```

```
administrative-gender|5.0.0
administrative-gender|4.0.1
```

Two tenants, one deployment, one request body, and each one validated against
the release it actually speaks — and said so.

That sentence is the whole argument for definitions being records rather than
a library version. A store that validates with a bundled copy of the
specification validates everything against whatever release it was built
against. Serving two organisations a release apart then means two deployments,
or a fork, or a per-request flag that somebody has to pass correctly every
time. Here the tenant declared its face and its face root, and the difference
is data.

## Asking before you commit

Sometimes you want the verdict without the write — a form to check, an import
to dry-run, a queue to triage before it lands:

```bash
--8<-- "docs/guide/examples/check.sh:validate-ahead"
```

```
error   | Observation.status: minimum required = 1, but only found 0 (from http://hl7.org/fhir/
warning | Constraint failed: dom-6: 'A resource should have narrative for robust management' (d
warning | Best Practice Recommendation: In general, all observations should have a subject
warning | Best Practice Recommendation: In general, all observations should have a performer
warning | Best Practice Recommendation: In general, all observations should have an effective[x
```

Note the status of that response: **200**, not 422. It is not a contradiction.
The question was *what is wrong with this*, and the store answered it — an
invalid resource is a successful validation. A 422 would mean the request to
validate failed, which is a different thing and would leave you unable to tell
the two apart.

Note also that it says more than the write path does. The write is refused by
the errors alone; the warnings here are advice you can take or ignore. Getting
them ahead of time is most of why you would ask.

**One gap to know about.** `$validate` works on every type the tenant serves,
but the capability statement does not currently list it, so you cannot discover
it the way you discover search parameters. Until that is fixed, this page is
where you find out it exists.

## An element the face does not define is refused

Being precise about the edge. Send a field that is not part of the definition
at all:

```bash
--8<-- "docs/guide/examples/check.sh:unknown-element"
```

```json
{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid",
 "diagnostics":"ERROR Patient: the element 'favouriteColour' is not defined here,
  and this store does not keep what it cannot read — FHIR carries what a
  resource does not define in 'extension'"}]}
```

`422`, and nothing was written. The refusal names the element, and it names the
sanctioned alternative rather than leaving you to find it.

This is the same stance as [Search](search.md)'s refusal of an unknown search
parameter, and for the same reason. A field the store cannot read is a field it
cannot validate and cannot search — so keeping it would hand the next reader a
document containing something the store never checked and cannot find. An
extension is the mechanism the standard provides for carrying what a resource
does not define, and an extension *is* part of the definition, so it is
validated like everything else.

In practice an unrecognised element is a typo, and this is the store catching
it at the only moment it is cheap to catch.

## When the verdict cannot be reached

Validation needs the definitions. If they cannot be reached, a write answers
`503` and asks you to retry — never `422`.

The distinction is deliberate and it matters more than it looks. `422` means
*this resource is wrong*, and a sending system that believes it will quarantine
the record, alert someone, or drop it. If an unreachable validator said `422`,
an outage would look exactly like a flood of bad data, and the damage would be
done by the systems correctly reacting to what they were told.

## What you would otherwise have written

Field checks at every endpoint that accepts a record, and the review that keeps
them in step with the schema. A code-list table, and the job that refreshes it,
and the argument about whether last Tuesday's copy is close enough.

Then the harder one: a second customer on a different release of the same
standard, and the discovery that your validation is a library version rather
than data — so serving both means two deployments of your system, or a fork,
or a flag threaded through every call site by hand.

And underneath all of it, the choice you would have to make yourself and
probably make wrongly once: whether a validator that cannot answer says *no* or
says *not now*.
