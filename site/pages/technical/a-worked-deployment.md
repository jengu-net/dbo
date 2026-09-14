---
title: A deployment, drawn
eyebrow: Technical
standfirst: >-
  One node, two organisations and the operator's own tenant, with two
  participants outside it. Concrete enough to point at, so the boundaries can
  be checked rather than asserted.
template: essay.html
---

Isolation claims are easy to write and hard to check. This page takes the
smallest deployment that still shows all of them — a clinic, a laboratory, the
operator's own tenant, and two participants that are somewhere else entirely —
and says what each party can reach.

--8<-- "assets/diagrams/three-tenants-drawn.svg"

<p class="diagram-caption">Two kinds of thing sit outside, and the difference
is not cosmetic: an application asks the store, a participant is offered work.
Below them, put a finger on any column and follow it down.</p>

## The three tenants

**The clinic** and **the laboratory** are two organisations that need to
exchange work and do not otherwise trust each other. Each gets a database of
its own, a service set of its own inside the node, and its own authority: its
own token issuer, its own signing keys, and its own records of who works there.
A token minted for the clinic fails at the laboratory before any claim in it is
read, because the issuer and the keys are wrong. Confusion between them is not
filtered out, it is unrepresentable.

**The managing tenant** is the operator's own, and the thing worth noticing is
that it sits in the same row as the other two. It is a tenant, not a control
plane: what the deployment was told to serve lives there as ordinary records,
with the same history, the same audit trail and the same retention as anything
else. So "what is this deployment supposed to be serving" is a query, and the
operator's own actions are recorded under the same rules it operates for
everybody else.

A deployment does not have to have one. Without a managing tenant the node
reads its declarations directly from the source and serves exactly as before —
recording what was declared is not a condition of honouring it.

## The application on top

A store nobody builds on is a filing cabinet, so the ordinary case is an
application in front of a tenant: the clinic's own software, a portal, a
reporting tool somebody wrote in a fortnight.

It talks to its tenant over FHIR, and there is one surface to talk to. The
records that say who works at the clinic are the same records the store reads
as grants, so an application does not get a second administrative interface
with its own user table and its own idea of who may do what. That second
interface is younger than the one everybody reviewed and usually authorised
differently, which is why it is where the problems are.

What the application holds is a token from that tenant's own issuer. For a
person it is pseudonymous by construction — the subject is a record id, and
there is no name and no national identifier in it — so a captured token
identifies nobody. Showing a name means fetching it through an authorised read,
and that read is recorded, because displaying who somebody is is a disclosure
like any other.

The same application can also **embed** the store rather than call it: the
whole thing boots inside the host's own JVM behind the same API. That is how a
consumer's tests run against the real store instead of a mock, and it is the
same shape that runs on an appliance. What changes between calling it and
embedding it is the transport, not the rules — the tenant is still its own
authority, and the boundaries below are the same ones.

## What the operator holds, and what it does not

The operator provisions tenants, which means it arranges for each to have a
database and hands the node a connection to it. It never holds the tenant's
credentials: a tenant is its own authority from the moment it exists, and there
is no master issuer whose keys open everything, because there is no master
issuer.

There is also no cross-tenant surface. Nothing offers a read across tenants to
anybody, the operator included. A fleet-wide view — an operator console, a
report — holds one credential per tenant and asks each in turn. That is a walk
rather than a join, and it is slower on purpose: a convenient cross-tenant read
would be available to anything that ever got hold of it.

## The participants are outside, and are never called

The bench instrument sits in the laboratory's building behind a router with no
public address. The supplier's system belongs to another company on another
network. Neither can be reached from the node, and neither needs to be.

Each holds a lane and asks what work is available to it. What it may take is
the intersection of two declarations — what its enrolment covers, and what the
step admits — so neither side alone can widen it, and an entitlement nobody
stated is empty rather than unlimited. It takes a run, does the work, and
reports back on the same channel. Nothing is queued for it while it is asleep,
and nothing is retried at it.

An appliance that is offline for a weekend is therefore not an incident. It is
behind by a distance you can read.

## Where the person is

--8<-- "assets/diagrams/where-the-person-is.svg"

<p class="diagram-caption">Three parties, each entitled to something real, and
none of the three thereby entitled to the person.</p>

Inside each tenant's database there are two regions. A **vault** holds the
identifying material — names, national identifiers, contact details — each
piece sealed with a key belonging to that one person. Everything else refers to
a person by **pseudonym**.

That split is what makes the sentences above checkable rather than hopeful.

**A participant** receives what its claimed run named and nothing behind it.
The laboratory's instrument gets the sample and the decision it has to make. It
does not get the patient, because the patient is not in what it was handed.

**The operator** can provision, monitor, back up, restore and upgrade without
being able to read a person's data. Backups are sealed under the owner's key,
so the party holding them cannot open one. What does leave a tenant for the
operator is a closed vocabulary about work — which tenant, which step, what ran
it, how long, and how it ended in one word from a fixed list. A failure's own
words are not in that set; they stay on the run, in the tenant whose work it
was. An open text field in a stream declared anonymous is how such a
declaration stops being true without anybody editing it.

**An auditor of the tenant** sees who acted, on what, when, and under which
run. The actor is stamped from the validated token rather than from what the
caller said about itself, and the entry is append-only against everyone, the
operator included.

Turning a pseudonym back into a person is a read of the vault by somebody
entitled to it, and that read is itself recorded — because learning who
somebody is is a disclosure. Erasing a person destroys their key, and every
record above keeps its rows: what happened stays provable, and who it happened
to is gone, including from the archive that left the building in March.

## Who holds what

| Party | Holds | Opens |
|---|---|---|
| A person at the clinic | a token from the clinic's own issuer, carrying a record id and no name | what their active role grants, in that tenant |
| The clinic's application | a token of its own, or a person's, depending on whose behalf it acts | the same surface a person gets, never a wider one |
| The bench instrument | an enrolment for the laboratory's lane | the runs of the steps it is entitled to claim |
| The supplier's system | an enrolment of its own | its own steps, in whichever tenant enrolled it |
| The operator | a connection per tenant, and a credential in the managing tenant | provisioning, and the closed measurement stream |
| Nobody | — | a read across tenants |

## What this deployment is not yet

One node. The routing layer that would spread these tenants over several is
[designed and unwritten](scaling.md), so a second node today is a second
deployment rather than a larger one. Everything above holds either way: the
boundaries are drawn at the tenant and at the person, not at the process.

<div class="further" markdown>
The shapes this can run in, and what scales, is
[the shapes it runs in](scaling.md). The isolation rules in full, including
what is declared to cross, are
[Data isolation](../docs/arc42-008-crosscutting/data-isolation/README.md). How
work reaches a participant is
[Processes and work](../docs/arc42-008-crosscutting/processes-and-work/README.md).
</div>
