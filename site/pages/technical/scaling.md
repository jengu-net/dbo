---
title: The shapes it runs in
eyebrow: Technical
standfirst: >-
  The same implementation inside a host's own process, on one node serving many
  tenants, or across a fleet. What changes between them is where the boundary
  is drawn, not what is behind it.
template: essay.html
---

Most stores have one deployment shape and a second, unhappy one for tests.
This one is meant to be the same store in a test suite, on an appliance in a
laboratory, and in a cluster — because the alternative is that a consumer
mocks it, and a mocked store is one whose real behaviour is discovered late.

--8<-- "assets/diagrams/the-shapes-it-runs-in.svg"

<p class="diagram-caption">Compare the rows rather than the columns. What is
behind the boundary is the same in all three.</p>

## Inside a host process

A host application boots the whole store inside its own JVM, sharing a small
container runtime and its API. The engine is plain Java with no application
framework anywhere in it, which is what makes that possible at all.

Two things follow, and they matter more than they sound.

**A consumer's tests run against the real store.** Not a mock, not a stub, not
a cut-down in-memory variant with different semantics. So the difference
between *works in the test* and *works in production* is deployment topology
rather than a different implementation.

**Cold start is fast enough to be used that way.** Schema setup detects that
the schema is already current instead of replaying a changelog to find out.
Measured cold start for the serving distribution is about five seconds from
boot to a first response on a current schema. A store that took thirty seconds
would be a store people mocked.

### Which is also how it runs on a small appliance

The same shape — one JVM, one Postgres beside it, no container and no
orchestrator — is what runs on hardware the size of a Raspberry Pi, on a shelf
in a laboratory or a clinic. It is not a cut-down edition. It is the ordinary
one, with one tenant and nothing around it.

That is deliberate, and it is also where the numbers come from: performance is
[measured on exactly that hardware](performance.md), because a figure taken on
an unnamed cloud instance cannot be repeated and a figure taken on the target
can.

An appliance is offline as a matter of course — a weekend, a network somebody
unplugged — and that is an ordinary state rather than a fault. It catches up by
replaying from its own position when it comes back, and nothing had to be
queued for it or retried at it.

## One node, many tenants

The ordinary deployment is one process on one port, with a database per tenant
behind it and tenants reachable at their own path.

The orchestrator's job is smaller than people expect. It runs instances, holds
secrets and enforces network policy. It does not route, it does not know which
tenant lives where, and it is not load-balancing across a tenant. There is no
broker and no cache tier to run beside it either, because change distribution
runs on the database that is already there.

## What actually scales, and what one more of it buys

--8<-- "assets/diagrams/what-scales-and-how.svg"

<p class="diagram-caption">Solid is built. Dashed is designed and unwritten,
which is the honest state of the routing layer today.</p>

**The data layer scales now, and it is the one that usually needs to.** A
tenant is a database, so the unit that moves is a whole tenant: more instances
means somewhere to spread them, and a tenant never has to be split in order to
be moved. Clustering, replicas and storage sizing are ordinary Postgres
decisions taken per instance rather than per application.

**The work layer scales now, and it scales the way it does because nobody is
pushed.** Runners ask for work, take it, and report back. Adding one adds
throughput and nothing else: no node has to be told it arrived, no queue has to
be held open for it, and a runner that dies is a claim that expires rather than
work that is lost. A runner is the store's work module packaged with the
durable-execution runtime it uses, so it is a thing you deploy more of, not a
thing you configure.

**The serving and routing layers are specified and not built.** The design is a
durable assignment of tenants to nodes, one writer per tenant, routing that a
caller never sees, and an entry hop chosen for locality. None of it has an
implementation today. It is written down in
[Deployment](../docs/arc42-007-deployment/README.md), and it is stated here as
a gap rather than a roadmap, because a reader planning capacity needs to know
that today a deployment is one node and its databases.

## Why performance was a requirement rather than a phase

Two of the decisions above only pay off if the store is fast enough that
nobody needs a tier in front of it, so speed was treated as a founding
requirement rather than as something to profile later.

That shows up as envelope indexing from the first schema rather than a
retrofit, write paths that do not take several round trips to complete, and
change distribution built on the database instead of on a broker beside it. A
cache tier would have been the usual answer to the same problem, and it would
have introduced a second copy of the truth, which is exactly what the rest of
the design spends its effort avoiding.

<div class="further" markdown>
How any number here was arrived at is
[how performance is measured](performance.md). What it needs from an
environment is [what it needs to run](environment.md). The routing design in
full is [Deployment](../docs/arc42-007-deployment/README.md), and operating it
end to end is
[Running it](../docs/arc42-008-crosscutting/running-it/README.md).
</div>
