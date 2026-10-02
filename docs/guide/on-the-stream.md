---
title: On the stream
eyebrow: Guide
standfirst: >-
  The worker beside the store holds its lane over the deployment's own
  database instead of HTTP. It is admitted by keys it made itself, handed work
  sealed to it, and leaves nothing readable on a plane every tenant's work
  crosses.
template: essay.html
---

Meristem's synchronisation service runs beside a deployment of this store, on
the same Postgres the deployment keeps its durable workflows in. Opening an HTTP
port into every practice's tenant is a second thing to secure, and the
substrate is already there. So the service connects to the substrate and to
nothing else, and its step code cannot tell the difference. The scene is
[US-DBO-ON-THE-STREAM](../arc42-003-context/user-stories/us-dbo-on-the-stream.md),
whose legs are proven by the tests its joins table names. The sample worker's
`substrate` profile holds the same lane, and `samples/check-separated.sh` starts
it by hand.

## The worker makes its keys

A lane over the substrate carries no token. What admits the worker is a
signature the tenant can check, and what reaches it is sealed to it, so it holds
two private keys and the tenant holds their public halves:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/MintingAnEnrolment.java:minting"
```

The private halves never leave the worker's side. The second file is the whole
of the enrolment, and it is safe to send.

## The tenant enrols the public halves

Issuing a credential is the tenant's to do. The clinic's application asks each
tenant to as it comes up, and for a worker on the substrate what it hands over
is the participant's name and the public halves of its keys
([the listener](a-tenant-opens.md#doing-something-as-a-tenant-opens)).
Ensuring rather than creating: a tenant reaches this point at every start, and a
client whose keys changed is brought up to date.

## The lane

The worker's `substrate` profile names the deployment's database and nothing
else; a lane with no base is carried by the substrate:

```yaml
--8<-- "samples/spring-boot-worker-app/src/main/resources/application-substrate.yaml"
```

The beans are unchanged. The quick start
[starts both sides](quick-start.md#beside-the-store-over-its-substrate).

## What the store guarantees

- **The same work comes to the same outcome, whichever carried it.** One step
  service on a lane over HTTP and one over the stream is handed the same
  document and closes with the same tally, and the hop over the stream is on
  the task's trail like any other.
- **Work goes out sealed to the participant, and its opening comes home
  signed**, landing on the document as a reading beside the travel entry on the
  task.
- **A runner is woken when work appears**, and still claims it through the
  ordinary path; if the news does not come, the poll does the work later.
- **A large payload travels beside the message**, as the same sealed bytes, and
  arrives byte for byte; no row of the substrate's own tables is ever
  payload-sized.
- **Nothing readable is left on the plane.** After a run whose document carried
  a marker and a person's identifier, every row of every table in the
  substrate, read as text, holds the manifest and none of the marker, the
  identifier, a bearer token or a client secret.

The [joins table](../arc42-003-context/user-stories/us-dbo-on-the-stream.md#joins)
names the test behind each.

## What the store cannot do yet

- **A door on the stream costs a durable-workflow instance per tenant.** A
  deployment of twenty tenants on a substrate ran its work about two and a half
  times slower than without one, and a deployment that wants the stream for a
  few tenants pays for it on every tenant served.
- **The last wake-up of a burst can go unsent**, so on a busy tenant the last
  run of a burst may wait for the poll. Nothing is lost; it costs latency.
