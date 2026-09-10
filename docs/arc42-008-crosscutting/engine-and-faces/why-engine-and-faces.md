---
title: The engine has no domain in it
eyebrow: Why DBO
standfirst: >-
  It presents as a FHIR server well enough that the distinction is easy to
  miss. Missing it is expensive: the domain ends up in the engine, and the
  next one needs a fork rather than a face.
why: 1
template: essay.html
---

A store for regulated data can be *presented* as a FHIR server by configuring
a face. Ids, `_history`, `$expand`, the searches and the operations a client
expects — all of it, and it is a real FHIR server rather than a gateway in
front of something else.

But FHIR is not what it holds. The engine's concepts are regulatory, and not
one of them mentions medicine.

--8<-- "assets/diagrams/engine-and-faces.svg"

<p class="diagram-caption">Run your eye along the lower row. That the engine has
no domain in it is something you can check from the picture rather than something
the picture asserts.</p>

## Why those concepts hold

They are not a neutral abstraction someone drew to be tidy. They are what
European regulation asks of *any* system holding personal data, in almost this
order: know what you hold, know who may change it, prove what happened,
separate identity, erase on request, let people leave with their data.

A domain standard answers none of those. It says what a record looks like.
The two are different jobs, and building the second on top of the first is
what leaves every application re-answering the first.

<div class="takeaway" markdown>
Mechanism in the engine, policy in the face. *A birth date reduces to its year*
is the engine's business. *A birth date is spelled `Patient.birthDate`* is the
face's. The test for where something belongs is whether the concept carries
domain meaning.
</div>

## The claim is structural, not aspirational

It would be easy to say this and have FHIR quietly everywhere. The module
layout is the claim made checkable:

- `dbo-core` — the engine's concepts. **No FHIR**, and no dependencies either.
- `dbo-fhir-common` — what every FHIR face shares. A module of its own,
  deliberately not part of core, because the store is not only for FHIR.
- `dbo-fhir-r4`, `dbo-fhir-r5` — what is genuinely a version's own: its
  terminology, and its subscription semantics.

R4 and R5 already run in parallel over one engine and interpret it
differently — R4 subscriptions use criteria strings, R5 uses topics, over the
same outbox. If FHIR were the model, that would need two stores.

## One implementation, versions released years apart

The FHIR face reads an instance against its StructureDefinitions rather than
into generated classes, and a search parameter is an expression and a type in
those definitions rather than a method on a model.

So a version is a pair: a definition package, and a code. Reading, validating,
framing and serving are the same code for every version.

R6 is the proof. No released HL7 core has a generated Java model for it, and it
is served through the same implementation as the other two.

The definitions travel with the face — pinned by version, verified by digest at
build time, embedded in the bundle. Bringing a tenant up fetches nothing from
anywhere and needs no writable cache. A face that had to reach the internet to
start would be a store whose availability depended on somebody else's.

## What is not true yet

A tenant gets **one** face.

The founding requirement asks for more than that: different tenants *and
different domains within a tenant* on different versions, and the same property
keeping the engine open to sibling models FHIR does not cover. Two faces of one
tenant at once is what the configuration model does not yet allow.

The engine is the right shape for it and the wiring is not there. That is a
gap, stated as one, because a founding requirement is exactly the thing worth
being precise about missing.

<div class="further" markdown>
The mechanics — what the engine requires from a face, how a version is chosen
at bring-up, and the three groups an obligation falls into — are in
[The engine and its faces (§1)](README.md).
What is built and what is only specified is the
[implementation status page](https://github.com/jengu-net/dbo/blob/main/docs/plans/implementation-status.md).
</div>
