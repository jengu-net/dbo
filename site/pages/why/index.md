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

**[What it actually is](engine-and-faces.md).** Not a FHIR store. A store whose
concepts are regulatory — object, identity, custody, declared handling,
history, tenancy, erasure — with FHIR as a *face* mapped onto them. That is why
R4 and R5 run side by side over one engine, and why a domain that has never
heard of a patient is a configuration rather than a fork.

**[Work](work.md).** A store that only answers questions is a database. This
one also holds what has to be done, who may do it, and how far they have got —
as ordinary records rather than as messages on a queue. That is what makes it
somewhere two organisations who do not trust each other can both work, instead
of a place one of them drops files.

**[Personal data](personal-data.md).** Identifying material is encrypted inside
the payload with a key belonging to the person, in the same write that stores
it. Erasure destroys the key. History stays immutable, archives stay valid, and
the operator running the system can back it up and restore it without ever
being able to read it.

**[Zones](zones.md).** Which identifier systems establish a person, and which
brokers may authenticate one, are facts about a country rather than about a
deployment. Hard-coding them is how a product becomes unexportable. Here a
jurisdiction is a tenant whose declarations are records, so entering a new
country is configuration and terminology rather than a release.

**[Staying in step](subscriptions.md).** Paging through results, subscribing to
changes, keeping a dependent copy current, and reconciling an appliance that
was offline all weekend look like four problems. They are one, and solving them
once means there is one place to look when something is behind.

## And three that decide what it is like to run

**[A tenant is a database](a-tenant-is-a-database.md).** Not a filter over a
shared one — which changes what a query with a bug in it can return, and what
"we have removed your data" is worth.

**[A type says what it is](a-type-declares-what-it-is.md).** Append-only,
versioned, retained for how long, identified by what: declared once per type and
enforced by the engine rather than by the habits of whatever code writes it.

**[One API, and it is FHIR](one-api.md).** No admin plane and no second
vocabulary. The organisation model *is* the authorisation model, so revoking
access is ending a period on an ordinary record.

## Who each part is for

<div class="cols" markdown>
<div class="col" markdown>
### If you are responsible for lawfulness
Start with [personal data](personal-data.md). It describes the mechanism that
turns access, portability and erasure from procedures somebody performs into
operations the system runs — and says plainly what the operator can and cannot
see.
</div>
<div class="col" markdown>
### If you have to run it
Start with [work](work.md) and then [staying in step](subscriptions.md).
Between them they cover what is in flight, what is stuck, what is behind, and
how each of those is observed — deliberately with no broker and no second
source of truth to reconcile during an incident.
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
