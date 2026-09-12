---
title: Why DBO
eyebrow: Why DBO
standfirst: >-
  One argument, in the order it builds. It starts somewhere unusual for a data
  store — not with the data, but with the work that needs it — and everything
  after that is a consequence.
template: essay.html
---

## It was designed backwards from a regulation

European regulation does not ask the question a database answers. A database
answers *who may touch this*. The regulation asks what was read or changed, by
whom, and **for what** — and a store that answers only the first leaves every
organisation built on it to assemble the second by hand, out of logs, after the
fact.

So this is not a general-purpose store with compliance features bolted to it.
The questions came first and the shape followed.

## So it starts with work, not with data

Outside, nobody reads or changes a regulated record for no reason. The reason
is a step of some process: a sample is validated, a consignment is cleared, a
passport is published. Analysing, deciding and recording the decision are one
piece of work, not a read followed by an unrelated write.

A step is the unit everything attaches to — what it consumes, what it produces,
who may perform it. So access is granted to the step rather than to a person,
and performing it is what leaves the proof. There is no way to reach the data,
or to change it, without doing the work that needed it.

--8<-- "assets/diagrams/where-access-begins.svg"

<p class="diagram-caption">The chain the rest of the argument hangs on, and
the boundary all three of its links already sit inside.</p>

[Access granted to the work →](work.md)

That is also why the trail is worth anything: the reason is not reconstructed
afterwards, because it was the thing that authorised the access in the first
place.
The trail lives in the tenant's own store rather than in an aggregator somebody
else owns.

[Audit nobody else holds →](the-audit-trail.md)

## Who may perform a step is already written down

A tenant keeps records of who works there, in what role, in which part of the
organisation, from when until when. Those records *are* the grants — a person's
access derives from an active role, and revoking it is ending a period on an
ordinary record rather than deleting a row from a table nothing else can see.

There is no second directory to keep in step, and therefore no drift to
discover on the day somebody who left last year still has access. A claim on a
step is checked against two things at once: the credential the participant
holds, and what the step declares about who may perform it.

[Auth from your own records →](one-api.md)

## Work is long-running, and it happens elsewhere

Almost none of it happens where the store is. A sample is analysed on an
instrument. A consignment is inspected at a border. Some of those places sit
behind a router with no public address, some are offline for a weekend, some
belong to organisations that are not on speaking terms.

So the store never reaches out. Participants ask what is available to them,
take it, and report back — which is what lets a distributed system follow a
business process without anybody opening a hole towards anybody. Everything
downstream follows the same way, by position: a subscriber, a dependent tenant
and an appliance that has been off since Friday are observed identically.

[One feed for every consumer →](subscriptions.md)

## Ownership is a property, not a promise

The rest of the regulation is about whose data it is, and each part of that
answer is structural rather than procedural.

A tenant is [its own database](a-tenant-is-a-database.md), not a filter over a
shared one. Identifying material is [encrypted with the person's
key](personal-data.md), so the party running the deployment cannot read what it
hosts. A person can be [erased in a way that reaches
backups](erasure.md) nobody can recall. And a tenant can [leave with
everything it brought](leaving.md), by the same path its nightly backup takes.

## What holds it together

Five more essays are the machinery the above assumes. A tenant [comes up and
goes away as an operation](tenant-status.md). Its configuration is [one set
applied to every environment](applying-configuration.md). What each type
guarantees is [declared, and enforced by the engine rather than by the
code](a-type-declares-what-it-is.md). The standard a tenant speaks is [its own
declaration](engine-and-faces.md), not the engine's. And the rules of a country
are [a tenant like any other](zones.md), so a new jurisdiction is configuration
rather than a release.

<div class="takeaway" markdown>
The distinction the whole design turns on: a **policy** is a commitment
somebody can break, forget, or be compelled to set aside. A **property** is
something the system does not permit. This store is built out of the second
kind.
</div>
