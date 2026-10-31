---
title: A step for every tenant
eyebrow: Guide
standfirst: >-
  The deployment declares one step and one bean performs it for every tenant.
  Each run stays the tenant's own — asked for on its door, closed in its store
  through its rules — and a tenant that does not want it says so in one line.
template: essay.html
---

The Ministry runs the deployment Rowling Land lives on, and it checks every
organisation's directory entry against what somebody proposes it should say.
Hogwarts did not install that check, and neither did the clinic that joined
last week: the Ministry declared it once, one bean performs it, and nobody
redeployed anything when the clinic arrived. The scene is
[US-DBO-FLEET-STEP](../arc42-003-context/user-stories/us-dbo-fleet-step.md),
walked by
[`OneStepIsPerformedForEveryTenantIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/OneStepIsPerformedForEveryTenantIT.java).

## The deployment declares it

`mom` is the deployment's own tenant, and its record names the steps the
deployment performs for every tenant under `fleetSteps`:

```json
--8<-- "samples/sample-world/mom.json"
```

The check takes the organisation it is about by reference, a proposed entry as
an object, and a list of notes. `opens` names the slots whose payload the
performer reads, which is what a tenant's register of processing is made of,
and `substrate` names where the step's work waits. A step code belongs to
exactly one level: a tenant declaring `fleet.directory.check` as a step of its
own does not come up.

## One bean performs it

The bean lives in the clinic's application and is the same interface as any
tenant's step:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/CheckingTheDirectory.java:step"
```

It names no tenant. The tenant arrives as `work.tenant()`, because a tenant
joining the world is a file appearing in a directory and must not also be a
release of this application. `@FleetStep` carries what a run has to record and
the code cannot supply: who performed it, which behaviour that is, and whose
code it is.

## Asked for on the tenant's door

The worker application asks Hogwarts for a check of one of its organisations.
It performs none of it:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/AskingForADirectoryCheck.java:asking"
```

The organisation is named by what the application knows about it, an
identifier, because it holds no credential that could look up the record's id.
Hogwarts resolves that search against its own records when the run is created
and records the reference it matched, so what the run is over cannot change
afterwards. The run is about Hogwarts' data, so it belongs to Hogwarts: authored
on its surface, carried on its work stream and refused by its rules if it breaks
one.

## A tenant says no in one line

A clinic that does not want the check declines it in its own declaration:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/OneStepIsPerformedForEveryTenantIT.java:declines"
```

Its door then refuses to start one and says why, and a run of the check authored
inside the clinic is never offered. Hogwarts said nothing, which admits the
step.

## A tenant reads what is done to its data

Each tenant has a register: one row for every slot the deployment's steps open
of its data, with the step, the slot, the type, whether it may be declined and
what happens to work it has not authorised. The check opens the organisation
and only carries the proposal and the notes, so the register has one row. A
clinic reads it at its own door, `GET /t/<code>/register`, with a credential
carrying the `configuration` scope: the same scope it authorises a register
with, by writing the digests of the rows it read into its declaration as
`authorised`. The register then says whether what is done changed since, and
says nothing of the kind to a tenant that never authorised one.

`GET /t/<code>/register/incidents` is the account kept against it: work that ran
under a row the tenant never authorised, naming the step, the slot and since
when, and openings its trail shows that the register does not. Whoever operates
the deployment reads, at `/runtime/fleet` under the operator's token, which
tenants have rows standing and how many incidents each holds, and never which
records were opened.

## A notice every tenant acknowledges

A provider sometimes has something every tenant must take notice of, such as a
system update and its maintenance window, and needs to see which tenants have.
The notice is a record the provider writes once in its own tenant:

```http
POST /t/mom/fhir/Communication
{ "resourceType": "Communication", "status": "completed",
  "category": [{ "coding": [{ "system": "urn:dbo:notice", "code": "system-update" }] }],
  "payload": [{ "contentCodeableConcept": { "text": "Upgrade on 20 October, 22:00–23:00 UTC" } }] }
```

Each tenant takes the provider's notices, since a tenant may take content from
its provider:

```json
{ "dependencies": [{ "name": "mom", "types": ["Communication"] }] }
```

The acknowledgement is a fleet step that lasts and starts itself:

```json
{ "fleetSteps": [{ "code": "fleet.update.acknowledge",
                   "slots": { "notice": "Reference(Communication)" },
                   "required": true, "lasts": true,
                   "starts": { "change": "Communication?category=urn:dbo:notice|system-update",
                               "keep": "P365D", "where": "everywhere" } }] }
```

When the notice reaches a tenant, the tenant opens its own acknowledgement,
[work that lasts](processes-and-steps.md#work-that-lasts) keyed by the notice.
It does so on every tenant that takes the notices, including one that joins
afterwards. A tenant cannot decline it, because it is `required`. A step the
provider declares while its tenants are already serving reaches them as they
stand.

The tenant's admin acknowledges it at the tenant's own door:

```http
POST /t/hogwarts/run/<id>/accept   { "because": "read; window agreed" }
POST /t/hogwarts/run/<id>/end      { "status": "completed", "because": "prepared" }
```

Under the operator's token, the provider reads who has acknowledged, tenant by
tenant. For each it shows the status, and the acts said of it with who, when and
why. A tenant that has not acknowledged reads as `received`, and one that does
not take the notices as `not-opened`. The view reads the work's state, never the
tenants' records.

```http
GET /runtime/fleet/steps/fleet.update.acknowledge?notice=Communication/<id>
```

A retention such as `keep` is a duration in days, hours or less, so a year is
`P365D`.

## Work a tenant hands to the provider

Some of a tenant's work is better done by the provider's people: a repair on a
device the provider supplied, say. The tenant hands it over
([work that lasts](processes-and-steps.md#work-that-lasts)) under a register row
it authorised, and a person signed in at the provider takes it with the token
the provider issued them:

```http
POST /t/<tenant>/run/<id>/claim          Authorization: Bearer <the provider's token>
GET  /t/<tenant>/run/<id>/fhir/Basic/<incident>
```

The token reaches the steps whose work the tenant hands over, and nothing else:
the tenant's records surface refuses it, and its step door starts nothing for it
but the parts below. The person
holds the work as the role the provider vouched for in the token, with no
deadline, and the work's `Task.owner` names that role by absolute reference to
the provider's base, `…/t/mom/fhir/PractitionerRole/<id>`. Taking the work is an
act under it naming the person the same way. While they hold it the work's
context answers them and nobody else, and they say its own acts at its address
as the tenant's people do: `accept`, `reject`, `stage` and `end`, each with a
`because`, each recorded naming them. `let-go` hands it back to the provider for
another of its people. Handing work over and taking it back stay the tenant's.

The holder starts the parts the tenant named for the work in
[`holdersStart`](processes-and-steps.md#work-that-lasts), at the step door, with
`partOf` naming the work by its key:

```http
POST /t/<tenant>/step/<part>   { "inputs": { … }, "partOf": "<the work's key>" }
```

Each part is the tenant's run, recorded as asked for by the person by absolute
reference to the provider's base, and offered to the tenant's own participants as
any part is. A step the tenant did not name, work the person does not hold, and a
part whose row the tenant has not authorised answer as work that does not exist.

The holder brings colleagues of the provider into the work, each named by their
`Practitioner` at the provider:

```http
POST /t/<tenant>/run/<id>/join    { "person": "Practitioner/<id>" }
POST /t/<tenant>/run/<id>/leave   { "person": "Practitioner/<id>" }
```

A colleague joined reads the work's context with their own token from the
provider, and holds no claim: its acts and its parts stay the holder's. The work
lists them in `for.joined`, by absolute reference to the provider's base, and each
join is an act under the work naming them. The tenant takes any of them off with
`leave` and that reference; the provider takes them off only through the holder.
Work taken back, or over, admits none of them.

Work the tenant kept, work that is over and work the tenant took back answer the
person as work that does not exist. A tenant that withdraws the row stops them
at their next request, because the row is asked for on every one.

## What the store guarantees

- **A step code belongs to one level**, checked where it is declared, and the
  deployment's record of what it serves names the conflict until it is fixed.
- **Declaring the step prepares where its work waits** — a database the runtime
  owns, apart from every tenant's — before there is any work.
- **Every tenant's run is offered once**, to the one bean, under the tenant and
  run it came from, including a tenant that joined after the bean was deployed,
  and again exactly once when the deployment reads a tenant's work from the
  start.
- **The run closes in the tenant**, naming the bean as its executor, with its
  tally, through the tenant's own lane and rules.
- **A slot arrives in one of three shapes**: a referred record resolved, a given
  object with no id and no version, and a list in the order it was sent.
- **A tenant reads its register and its incidents at its own door**, and
  authorising what it read is one comparison from then on.

The [joins table](../arc42-003-context/user-stories/us-dbo-fleet-step.md#joins)
names the test behind each.

## What the store cannot do yet

- **A person of the provider writes nothing through handed work.** Handed work
  lasts, and work that lasts writes nothing of its own; the parts they start are
  performed by the tenant's own participants and people.
- **A fleet step writes nothing.** No tenant's declaration says what the
  deployment's step may write, so its result is a tally and no records.
- **A deployment cannot name its processor** in configuration, so none is
  enrolled.
- **A bean whose step nothing declares is noticed only inside the process.**
- **The provider reads acknowledgements one node at a time.** The view covers
  the tenants the node it asks serves, so a deployment of several nodes asks
  each one.
- **A site's acknowledgement stays on the site.** A tenant served at a place
  opens its own there as well as at its origin, and the provider reads the
  origin's.
