**Open. A worker configured in a serving application should take its work over
the deployment's own substrate, and today it takes it over HTTP — from the
application to itself, across the loopback, holding a credential its own
process issued. The lane that would carry it exists and is proven
indistinguishable; what does not exist is a way to say so in configuration.**

# A co-located worker takes the substrate, not its own port

## What this is

An application may hold both Spring Boot assemblies: it serves its tenants and
performs their work in one process, on one container. `DboWorker` then builds
its lanes from `dbo.worker.lanes[].base`, and that is always an `HttpLane`:

```java
Lane lane = HttpLane.to(declared.getBase().resolve("work"),
        bearer, declared.getTenant(), participant, identity);
```

So a worker beside the store asks its own port for work. Every cycle is an
HTTP round trip into the servlet container the same JVM is running, guarded by
an authority the same JVM is hosting, carrying a token the same JVM minted.

**The right carrier is the stream.** `StreamLane` in `core/dbo-stream` reaches
the deployment's substrate directly — the same database the serving half is
already connected to — and `ALaneOverTheStreamIsIndistinguishableIT` proves
the runner cannot tell which carrier brought a run. So this is a change of
wiring, not of anything a step service sees.

## Why it is not just tidiness

**A loopback lane is a second thing that can be down while the first is up.**
The application is serving and the worker cannot reach it, because its own
port is not accepting yet or its own authority has not issued yet. That is
observable now: the sample's co-located test logs the credential refused for
about ninety seconds of every cold start, once per cycle, while the tenant
finishes coming up. Over the substrate there is no port and no token, and that
window does not exist.

**And it is a credential that need not have been minted.** A co-located worker
holds a client id and secret for the tenant it is inside. Configuration that
has to carry a secret so a process can talk to itself is configuration an
operator has to rotate, store and explain.

## What is missing, precisely

Not the lane. `StreamLane.holding(substrate, tenant, participant, identity,
sealing, signing)` is built and used — by `dbo-stream`'s own activator, from
framework properties, which is the shape of *a host reaching a deployment's
substrate from outside*. There is no way for an application to say **this
lane is into the store I am already running**.

So the work is a carrier choice on a lane's configuration:

```yaml
dbo:
  worker:
    lanes:
      - tenant: hogwarts
        carrier: substrate      # rather than a base and a token
```

and the assembly resolving that against the runtime it already holds, instead
of a URI and a credential.

## What has to be decided

- **Whether `carrier: substrate` is legal when there is no store in the
  process.** It cannot be: a worker that is not co-located has no substrate to
  reach, and the refusal belongs at context refresh with the lane's tenant in
  the message, not at the first cycle.
- **Whether it should be the default when the server assembly is present.**
  Convenient, and it makes what a worker does depend on what else is on the
  classpath. The alternative is that a co-located worker says which carrier it
  wants and gets refused if it is wrong.
- **What the participant and identity are.** Over HTTP they come from the
  credential; over the substrate they are stated. `dbo.worker.identity` is
  already the executor's name and version, so this is probably nothing new —
  but it is the seam where an unstated default would put the wrong name on a
  run, which is the one field a run cannot be re-derived from.

## What this is not

**Not a case against an HTTP lane.** A worker of its own is the shape for
scaling — the performing side wants replicas where serving a read does not —
and for a party performing a step for a tenant it does not run. Those reach
the deployment from outside and a lane over HTTP is exactly right for them.
This item is about the one case where the store is already in the process.

**Not a reason to let a co-located worker read the store.** It takes work over
a lane and reports over the same one, whichever carries it. A worker that
reached the store directly when it happened to be nearby is one that could not
be moved, and moving it is the property the lane exists to preserve.

## What proves it

Today, that it does not:

```
./gradlew :samples:spring-boot-worker-app:test
```

and read the `oidc/token` calls in the report — a co-located application
authenticating against itself.
