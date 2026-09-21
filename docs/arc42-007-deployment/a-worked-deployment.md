# A worked deployment

Isolation claims are easy to write and hard to check. This page takes a
deployment small enough to point at and concrete enough to run — the one the
guide uses — and says what each party can reach.

It is not a deployment invented for the argument. It is
`docs/guide/examples/compose.yaml`, mounted over `sample/world/`, and three
CI jobs bring it up on every change. Every sentence below is about a file in
this repository, so a claim that stops being true stops being true visibly.

--8<-- "docs/guide/examples/compose.yaml"

--8<-- "assets/diagrams/the-tenants-in-a-deployment.svg"

<p class="diagram-caption">Two separations, and they are not the same one. No
tenant reads another's records. Definitions and declarations do move, one way,
where the tenant below declared them.</p>

## Seven tenants, of three kinds

Everything in the node is a tenant. That is not a figure of speech — the zone,
the face roots and the operator's own records are tenants in the same sense the
hospital is, with their own database, their own history and their own audit
trail. What differs is what they hold and who depends on them.

### The three that serve people

`hogwarts` is a hospital, `st-jerome` a private clinic, `gringotts` an
insurer. Three organisations that need to exchange things and do not otherwise
trust each other. Each has a database of its own, a service set of its own
inside the node, and its own authority: its own token issuer, its own signing
keys, and its own records of who works there. A token minted for the hospital
fails at the insurer before any claim in it is read, because the issuer and the
keys are wrong. Confusion between them is not filtered out, it is
unrepresentable.

They are deliberately not three of the same thing. The hospital keys its
people by a national number and holds them behind the membrane; the clinic
keys nobody by one, which is the ordinary case for a practice that never asks;
the insurer is a release behind. What that arrangement costs is visible in
their spec files rather than described here.

### The four that supply them

**The zone**, `rl`, holds what a country decides rather than what an
organisation does: which terminology is canonical, which identifier systems
establish who somebody is. Those are ordinary records inside it, so they are
versioned and audited — you can ask what the rules were on the day something
happened — and entering a new country is a zone rather than a release. A
tenant inside a zone chooses among what the zone declares and may narrow it
further, never widen it.

**Two face roots**, `fhir-r4` and `fhir-r5`, each hold a standard's version as
records: the structures, the search parameters, the value sets and the code
systems. A version is something a tenant subscribes to rather than something
loaded into a node, which is why tenants on one version share a single copy of
it and why a tenant is served only once what it declared has arrived.

This world has two of them because it has an organisation on each — which is
the case the single-root story could only describe. The insurer is a release
behind and is not thereby a second deployment; it takes the zone through a
**projection** the runtime makes for it, because the zone speaks R5 and the
insurer R4.

**The managing tenant**, `mom`, is the operator's own, and the thing worth
noticing is that it sits in the same row as the others. It is a tenant, not a
control plane: what the deployment was told to serve lives there as ordinary
records, with the same history, the same audit trail and the same retention as
anything else. So "what is this deployment supposed to be serving" is a query,
and the operator's own actions are recorded under the same rules it operates
for everybody else.

It is mounted from outside the directory the others come from, and the compose
file says why: the loop that retracts undeclared tenants must not be able to
retract the thing recording retractions. A deployment does not have to have
one — without it the node reads its declarations directly from the source and
serves exactly as before, because recording what was declared is not a
condition of honouring it.

### The one direction anything crosses

The arrows in the figure are the only movement between tenants, and they are
worth being precise about, because a careless reading makes them a hole in the
wall.

What moves is definitions and declarations — never a person, never a record of
care. It moves only where the receiving tenant **declared that it takes it**,
in its own spec file, and a tenant declares only against its direct upstream,
so chains compose one hop at a time rather than granting anybody a view of the
whole. What lands is read-only and stamped with which tenant and which version
it came from. And it travels on the same feed machinery as everything else, so
there is no separate synchronisation subsystem to go stale.

The wall the figure claims is therefore about records: no tenant reads
another's, including the operator. The door is about definitions, one way, by
declaration.

## The application on top

A store nobody builds on is a filing cabinet, so the ordinary case is an
application in front of a tenant. In this world that application is
`sample/`, and the guide is its story.

It talks to its tenant over FHIR, and there is one surface to talk to. The
records that say who works at the hospital are the same records the store
reads as grants, so an application does not get a second administrative
interface with its own user table and its own idea of who may do what. That
second interface is younger than the one everybody reviewed and usually
authorised differently, which is why it is where the problems are.

What the application holds is a token from that tenant's own issuer. For a
person it is pseudonymous by construction — the subject is a record id, and
there is no name and no national identifier in it — so a captured token
identifies nobody. Showing a name means fetching it through an authorised
read, and that read is recorded, because displaying who somebody is is a
disclosure like any other.

The same application can also **embed** the store rather than call it: the
whole thing boots inside the host's own JVM behind the same API. What changes
between calling it and embedding it is the transport, not the rules.

## What the operator holds, and what it does not

The operator provisions tenants, which means it arranges for each to have a
database and hands the node a connection to it — the three `DBO_ADMIN_*`
values in the compose file, and nothing else. It never holds a tenant's
credentials: a tenant is its own authority from the moment it exists, and
there is no master issuer whose keys open everything, because there is no
master issuer.

There is also no cross-tenant surface. Nothing offers a read across tenants to
anybody, the operator included. A fleet-wide view — an operator console, a
report — holds one credential per tenant and asks each in turn. That is a walk
rather than a join, and it is slower on purpose: a convenient cross-tenant
read would be available to anything that ever got hold of it.

## The participants are outside, and are never called

`sample/participant/` is a laboratory: another organisation's process, with a
lane, a runner and a step declaration of its own, and no store on its compile
path at all. It could be behind a router with no public address and nothing
would change.

It holds a lane and asks what work is available to it. What it may take is the
intersection of two declarations — what its enrolment covers, and what the
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
piece sealed with a key belonging to that one person. Everything else refers
to a person by **pseudonym**. The hospital declares this and the clinic does
not, which is what makes it a decision rather than a property of the engine.

That split is what makes the sentences above checkable rather than hopeful.

**A participant** receives what its claimed run named and nothing behind it.
The laboratory gets the specimen and the decision it has to make. It does not
get the patient, because the patient is not in what it was handed.

**The operator** can provision, monitor, back up, restore and upgrade without
being able to read a person's data. Backups are sealed under the owner's key,
so the party holding them cannot open one. What does leave a tenant for the
operator is a closed vocabulary about work — which tenant, which step, what
ran it, how long, and how it ended in one word from a fixed list. A failure's
own words are not in that set; they stay on the run, in the tenant whose work
it was. An open text field in a stream declared anonymous is how such a
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
| A person at the hospital | a token from the hospital's own issuer, carrying a record id and no name | what their active role grants, in that tenant |
| The application | a token of its own, or a person's, depending on whose behalf it acts | the same surface a person gets, never a wider one |
| The laboratory | an enrolment for the step it declared | the runs of the steps it is entitled to claim |
| The insurer | a declared dependency on the zone, through a projection | read-only copies of what it declared it takes |
| The operator | a connection per tenant, and a credential in the managing tenant | provisioning, and the closed measurement stream |
| Nobody | — | another tenant's records |

## What this deployment is not yet

One node. The routing layer that would spread these tenants over several is
designed and unwritten, so a second node today is a second deployment rather
than a larger one. Everything above holds either way: the boundaries are drawn
at the tenant and at the person, not at the process.

Two settings in that compose file are development conveniences a deployment
must not copy, and each says so where it is made.
