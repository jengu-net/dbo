---
title: Why DBO
eyebrow: Why DBO
standfirst: >-
  Storing clinical data is the easy half. The half that decides whether you may
  operate is the one about who read it, what happens when someone asks to be
  erased, and whether anybody can prove the history was not quietly edited.
template: essay.html
---

Most systems answer those questions above the store, in application code, once
per application. That works until there are three applications, two of them
written by somebody who has left, and an auditor asking all three the same
question expecting one answer.

DBO moves the answers into the store. Its concepts are regulatory rather than
clinical — object, identity, custody, declared handling, history, tenancy,
erasure — and not one of them mentions medicine. A *face* maps a standard onto
those concepts: configure the FHIR face and you have a FHIR server; configure a
different face and the same engine serves a domain that has never heard of a
patient.

<div class="takeaway" markdown>
The distinction the whole design turns on: a **policy** is a commitment
somebody can break, forget, or be compelled to set aside. A **property** is
something the system does not permit. This store is built out of the second
kind.
</div>

## Start here

**[What it actually is](engine-and-faces.md).** One line of a tenant's spec
says which standard it speaks. Underneath, the concepts are regulatory — object, identity, custody, declared handling,
history, tenancy, erasure — with FHIR as a *face* mapped onto them. That is why
R4 and R5 run side by side over one engine, and why a domain that has never
heard of a patient is a configuration rather than a fork.

**[Work](work.md).** A store that only answers questions is a database. This
one also holds what has to be done, who may do it, and how far they have got.
So access is granted to a step of a process rather than to somebody, and the
record of the work is the record of why the data was seen — which is what makes
it somewhere two organisations who do not trust each other can both work.

**[Personal data](personal-data.md).** Identifying material is encrypted inside
the payload with a key belonging to the person, in the same write that stores
it — so every copy the store makes of itself carries ciphertext because of
where the encryption happens, not because each path was written correctly. The
operator can back it up and restore it without ever being able to read it.

**[Zones](zones.md).** Which identifier systems establish a person, and which
brokers may authenticate one, are facts about a country rather than about a
deployment. Hard-coding them is how a product becomes unexportable. Here a
jurisdiction is a tenant whose declarations are records, so entering a new
country is configuration and terminology rather than a release.

**[Staying in step](subscriptions.md).** Paging through results, subscribing to
changes, keeping a dependent copy current, and reconciling an appliance that
was offline all weekend look like four problems. They are one, and solving them
once means there is one place to look when something is behind.

## And two about endings

**[Leaving](leaving.md).** One sealed archive the operator cannot read and
somebody else can verify — and it is the artefact the nightly backup already
produces, so the way out is the path with the most mileage on it rather than a
feature nobody has run.

**[Erasure](erasure.md).** Keeping every version immutably and erasing a person
on request point in opposite directions, and most systems quietly pick one.
Destroying the person's key rather than their rows resolves it, and reaches the
copies nobody can recall.

## And four that decide what it is like to run

**[A tenant is a database](a-tenant-is-a-database.md).** Not a filter over a
shared one — which changes what a query with a bug in it can return, and what
"we have removed your data" is worth.

**[A type says what it is](a-type-declares-what-it-is.md).** Append-only,
versioned, retained for how long, identified by what: declared once per type and
enforced by the engine rather than by the habits of whatever code writes it.

**[Who works here is who may act](one-api.md).** The organisation model *is*
the authorisation model, so revoking access is ending a period on an ordinary
record — and one surface to secure, because there is no second list of users.

**[The audit trail](the-audit-trail.md).** Who read this, on whose authority,
and when — kept in the tenant's own store, append-only against everyone
including the operator, still provable after the person in it has been erased,
and still answerable when the machines that held it are long gone.

## And two about the gap between declared and true

Every system has one. Most of them find out about it from a support ticket.

**[Applying configuration](applying-configuration.md).** Applying a declared
set is a run, not a start-up log line: it tallies what it read, applied and
skipped, one declaration nobody could apply is a card rather than a silence,
and an unreadable source never answers with an empty set.

**[What the node is doing](tenant-status.md).** Serving, coming up, or failed —
one state per tenant, from runtime state rather than from re-reading the
declarations, so comparing the two finds a disagreement instead of confirming
your own writes.

## Who each part is for

<div class="cols" markdown>
<div class="col" markdown>
### If you are responsible for lawfulness
Start with [personal data](personal-data.md), then [erasure](erasure.md), then
[the audit trail](the-audit-trail.md). Between them: the mechanism that turns
access, portability and erasure from procedures somebody performs into
operations the system runs, and the evidence that survives all three.
</div>
<div class="col" markdown>
### If you have to run it
Start with [what the node is doing](tenant-status.md) and
[applying configuration](applying-configuration.md) — between them, everything
about the gap between what you declared and what is true. Then
[staying in step](subscriptions.md) for what is behind and by how much.
Deliberately no broker and no second source of truth to reconcile at 3am.
</div>
<div class="col" markdown>
### If you are deciding on an architecture
Start with [what it actually is](engine-and-faces.md), then
[zones](zones.md) for the layering rule, then go straight to
[the specification](../docs/README.md). The reference tree is arc42, it says
what is built and what is only specified, and it does not flatter itself.
</div>
</div>

<div class="further" markdown>
These pages are a way in, not the source of truth. Every claim on them is
stated more precisely, and proven by a named test, in
[the specification](../docs/README.md) — and what is built versus what is only
written down is tracked in
[the implementation status page](https://github.com/jengu-net/dbo/blob/main/docs/plans/implementation-status.md).
</div>
