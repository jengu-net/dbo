---
title: Guide
eyebrow: Guide
standfirst: >-
  For somebody building a system on this store rather than beside it. Every
  capability, how an application reaches it, and what it would otherwise have
  had to write itself.
template: essay.html
---

This store is not a product somebody uses. It is a backbone: a specialised
system is built on top of it, and the store answers the questions that system
would otherwise have to answer for itself — who may act, what happened, whose
data this is, how it leaves.

So the useful question in every chapter is not *what is the API*. It is **what
would you otherwise write**, and why writing it anyway leaves you with two
answers to one question, drifting apart on their own schedule.

## What you would otherwise build

| You might be building | The store already has |
|---|---|
| a job table, a worker loop, retries | processes, steps and runs |
| a purpose or lawful-basis story assembled for an auditor | the step is the purpose, and the run records it |
| a per-customer database and its provisioning | tenants, each a database |
| an authentication flow, token issuing and validation | a per-tenant authority, federated to a broker |
| a user table, roles and a permission matrix | authorisation from the records you keep anyway |
| an audit log table and the code that writes it | the trail, as records, append-only against everyone |
| field-level encryption and a key table | identifying material sealed with the person's key |
| a "delete this person everywhere" script | erasure that reaches copies you cannot recall |
| an export job and an import job | one archive, both ways, sealed to the owner |
| a queue or a broker between services | lanes and feeds, on the database you already have |
| a change-notification table and pollers | one feed, with named consumers and durable positions |
| a terminology table and code lookups | terminology as records |
| a validator, or per-endpoint field checks | validation against declared definitions |
| a country or config table read at start-up | zones, and one declared set applied |

Where this guide and
[the requirement catalogue](../arc42-006-runtime/req-catalogue.md) disagree,
**the catalogue is right**. It is generated from the promises and their proofs.
This is prose.

## The shape in one paragraph

Start with **work**, because everything else hangs off it. Work is declared as
a **process** with **steps**, and a step is the unit everything attaches to:
what data it may touch, who may perform it, and why. Performing one is a
**run**, and the run is the reason the data was reached — which is what the
**trail** records.

Everything else is what work acts on or through. A **tenant** is a database,
and it holds the **records** of the **types it declared**, served over a
**face** — a standard it speaks, FHIR today. Who may perform a step comes from
the tenant's own records rather than from a user table beside them. Records
reach other tenants over **feeds**, and only where the receiving tenant
declared it. A **zone** is a tenant holding a jurisdiction's rules. And all of
it is records: the trail, the configuration, the definitions validated against,
and the work itself.

A store that only answers questions is a database, and there are good ones.
What makes this one worth its constraints is that last idea — that reaching the
data is an act with a declared purpose attached. [How it fits
together](how-it-fits.md) sets that out before the chapters start, and is worth
reading first: the order everything else comes in follows from it.

## The tenants in the examples

Six of them, the same six throughout, so a name in one chapter means the same
thing in the next.

| Tenant | Face | What it is |
|---|---|---|
| `mom` | R5 | the managing tenant — what this deployment was told to serve |
| `rl` | R5 | the zone — Rowling Land, whose terminology the others take |
| `fhir-r5` | R5 | a face root, holding the R5 definitions as records |
| `fhir-r4` | R4 | a face root, holding the R4 definitions as records |
| `hogwarts` | R5 | a hospital |
| `gringotts` | R4 | an insurer, deliberately a release behind |

The insurer is a version behind on purpose. A payer sitting on the older
release is what actually happens, and it makes conversion, identity across
versions, and a refusal that names its version things you can run rather than
things you are told.

A tenant is declared as one file. This is the hospital, complete:

```json
--8<-- "docs/guide/world/tenants/hogwarts.json"
```

It says which standard it speaks, which zone it is in, which face root it takes
its definitions from, and what it holds — for each type, what identifies one
and how it is handled. Nothing in that file is a hint. The engine enforces all
of it.

## Every example here is executed

The commands in these chapters are not typed into the page. They are injected
from a script that runs against the world, so a command that stops working
stops being published: a renamed region fails the site build, and a broken
command fails the run.

That script is `docs/guide/examples/check.sh`, and it is the same discipline
the quickstart uses. An example that only compiles is a snippet with extra
steps, so each one asserts something.

<div class="further" markdown>
Why the store is shaped this way is the *Why DBO* section, and the recurring
problems with the arrangements that solve them are *the patterns* — both are
collected into sections of their own on the site, which is why this paragraph
names them rather than linking. What is built, against what is only specified,
is the [requirement catalogue](../arc42-006-runtime/req-catalogue.md).
</div>
