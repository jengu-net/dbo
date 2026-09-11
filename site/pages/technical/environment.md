---
title: What it needs to run
eyebrow: Technical
standfirst: >-
  A current Java LTS and a PostgreSQL. The interesting part of this list is
  what is not on it, and each absence was chosen rather than deferred.
template: essay.html
---

## The list

**Java, current LTS** — language level 21 or later, with virtual threads
assumed rather than optional.

**PostgreSQL, and only PostgreSQL.** It is the sole supported data store, and
it is also the background engine for durable tasks, streams and communication
between instances.

**OSGi**, with Felix as the reference implementation. The serving distribution
runs the standard Felix launcher and has no launcher code of its own.

**Kubernetes, for less than you would expect.** It runs parallel instances and
provides the security layer — network policy, secrets, the provisioning
operator. Tenant-aware routing happens in the application, not in the cluster.

## What is deliberately absent

**No message broker.** The change feed is a transactional outbox in the same
database as the data, committed with the write. A broker would be another thing
to run, secure, back up and reason about during an incident, and the ordering
guarantee it would provide is already available where the data is.

**No cache tier, and no Redis-class shared state.** A tenant's serving pod is
its single writer, so local caching and local subscription state are correct by
construction rather than by an invalidation protocol.

**No heavyweight application framework.** No Spring Boot, no Micronaut in the
engine. Heavy third-party stacks ride as private packages inside embedding
bundles rather than as dependencies of the store.

<div class="takeaway" markdown>
Every one of those absences is a thing that does not have to be running for the
store to answer, does not have to be patched on somebody's schedule, and cannot
disagree with the database about what happened.
</div>

## Cryptography

JDK cryptography only. Everything the store seals for itself is symmetric,
under a key the sealer already holds — the container key, the per-person keys
derived from it, an owner's archive key.

There is exactly one exception, and it is narrow. A participant offers the
public halves of two keypairs when it enrols: X25519, which payload data keys
are wrapped to, and Ed25519, which it signs its trail links with. The private
halves never cross, so a copy of the enrolment records opens nothing and signs
nothing.

No other asymmetric material, and no other curves.

## Two operational numbers worth knowing

**Cold start is about five seconds**, from boot to a first `200` on a current
schema. Schema setup detects that the schema is already current rather than
replaying a changelog to find out. That number is what makes the store usable
as a test dependency: a store that took thirty seconds to start is one
consumers mock, and a mocked store is one whose real behaviour is discovered
late.

**The R5 validator needs heap.** It loads the FHIR core package eagerly, and on
a default heap it dies in a way that does not name its own cause. Test tasks
run at 2 GB; a serving deployment should size for it deliberately rather than
discover it.

## It boots inside a host, too

The production bundles run inside a host application's own JVM, so development
and test can run against the real engine rather than a substitute.

One caveat matters there: **a host that already provides slf4j keeps its own**,
and installs neither of the store's logging bundles. Two providers of
`org.slf4j` in one framework is not a posture, it is a race — and the loser is
not the one that fails, it is the one whose binding is not behind the winner.
Everything then resolves, components activate, work proceeds, and every line
written inside the framework goes to a facade with nothing behind it. That
state is not quiet, it is inaudible, and from outside the two look identical.

<div class="further" markdown>
The constraints and their reasoning are
[Constraints](../docs/arc42-002-constraints/README.md); the deployment shape,
embedding, backup and upgrades are
[Running it](../docs/arc42-008-crosscutting/running-it/README.md).
</div>
