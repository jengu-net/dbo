# Serving tenants from a Spring Boot application

One dependency. The application starts and it is a DBO node: tenants come up,
their doors answer **on the application's own port, through its own servlet
container**, and a bean that implements an extension point is one. Nobody
learns the word OSGi.

```kotlin
implementation("cloud.jengu.dbo:dbo-spring-boot-server:0.1.0-SNAPSHOT")
```

```yaml
dbo:
  mount: servlet                            # the doors, on this port
  tenants:
    directory: ../sample-world/tenants      # what this deployment serves
  management-spec: ../sample-world/mom.json # where the store keeps its own history
```

No annotation to add and no bean to declare — this is autoconfiguration.
`samples/spring-boot-server-app` is the whole of it, running.

## The one rule

**Everything under `dbo` is the store's; everything else is an ordinary
Spring Boot application's.** There is no second server, no second port and no
second access log, and the application's own `WebSecurityConfiguration`,
filters and actuator are untouched.

**A tenant is declared in a world, never in `application.yaml`.** The one
exception is `management-spec`, and it is an exception for a reason: the loop
that retracts tenants nobody declares must not be able to retract the thing
that records retractions. A deployment that listed its tenants in
configuration would provision databases as a side effect of a config refresh,
keep no record of who declared what and when, and answer *what is this
deployment declared to serve* only to somebody with a shell on the node.

## What the application gets

One bean, injected anywhere:

```java
@Autowired DboTenants tenants;
```

| it answers | with |
|---|---|
| `serving()`, `isServing(code)` | the tenants up right now |
| `records(code)` | the FHIR facade — read, write, search |
| `asking(code)` | the question surface: records, work, the trail |
| `changes(code)` | the tenant's change feed |
| `store(code)` | the object store under the face |
| `authority(code)` | the tenant's own OIDC authority |

Every call asks the container as it is asked. **Nothing is cached, and that is
deliberate**: a handle to a tenant retracted a minute ago is worse than no
handle, because it works until it does not and then it works wrongly. Each
answer is an `Optional` for the same reason.

**This reads.** Declaring a tenant is a different act with a different right
behind it, and the runtime keeps the two apart.

## The two extension points

These are why an application embeds the runtime instead of talking to it over
a socket. Both are ordinary beans; the annotation carries what the runtime
selects on.

```java
@Component
@DboTenantListener(point = TenantPoint.SERVING)
class WhenATenantArrives implements TenantLifecycleListener { … }

@Component
@DboObserver(domain = TenantDomain.CONTENT, consumer = "my-projection")
class WhatChanged implements TenantObserver { … }
```

`target` narrows either to some tenants, as a filter over what a tenant
declares; empty means every tenant.

**A consumer name is required and kept.** An observer is a named durable
consumer, not a callback: one absent for an hour resumes where it left off
instead of missing the hour. Changing the name starts again from the beginning
of the stream, and two applications sharing one take each other's changes.

**A bean missing what the runtime selects on fails at context refresh**, with
the bean's name in the message. Not quietly observing nothing — a consumer
that silently reads nothing is indistinguishable from one that is working and
has nothing to do.

## Where the doors are

Every door a tenant opens is under its own prefix — `/t/{code}/fhir` for
records, `/t/{code}/step` for work, `/t/{code}/oidc` for its authority, and
more besides as a tenant declares them: `scim/v2`, `identity`, `blob`,
`erasure`, `replication`. The set is not closed here, because it is not this
module's to close: each surface is registered by the tenant runtime and this
bridge adapts whatever was registered.

| `dbo.mount` | |
|---|---|
| `servlet` (default) | on the application's port, inside its filter chain: one TLS configuration, one access log, and the doors reachable from the application's own tests |
| `own-port` | on a listener of the store's own, as the serving distribution does — for an application with no web tier |

**A tenant that comes up after startup is reachable**, because the filter
resolves per request rather than registering routes once.

## The property surface

| | |
|---|---|
| `dbo.mount` | `servlet` or `own-port` |
| `dbo.tenants.directory` | the world this deployment serves |
| `dbo.management-spec` | the tenant the store keeps its own history in |
| `dbo.admin.jdbc-url`, `.user`, `.password` | the server the store provisions tenant databases on |
| `dbo.auth.kek` | the key everything the store seals for itself derives from |
| `dbo.auth.issuer-base` | what a tenant's authority calls itself |
| `dbo.http.host`, `.port` | `own-port` only |
| `dbo.framework.*` | passed through to the container, for a property this class has no name for |

## The one thing worth knowing before you ship

**A pooled thread is not a fresh one.** The serving distribution runs each
request on a virtual thread that dies afterwards, so a `ThreadLocal` left set
is collected with it. Spring Web hands the thread to whoever is next. Four
values are bound per request and must not survive one — who is asking, what
may be disclosed and under what purpose, which organisations an answer may
come from, and which audience it is answered as — and `SpringHttpServer`
clears all four in a `finally` regardless of what the surface did.

That is belt and braces on purpose: the handler set is not closed, the next
surface will be written by somebody who has not read this, and the cost of
being wrong is one caller's organisational reach applied to another caller's
request.

**What is not proven here** is that property, over this bridge. The
distribution has a test that would catch it — a real server on a pool of one,
two requests, and a guard reporting what it found already bound — and the same
test over the servlet adapter does not exist yet.

## What this deliberately does not do

- **Share the application's `DataSource`.** The store manages its own
  connections per tenant, with its own pool and transaction discipline.
  Handing it the application's pool puts the application's transaction manager
  in the path of a single-transaction write the store's promises rest on.
- **Offer a Spring Data repository.** The store's API is small and the
  question surface is `Asking`. A repository over it is the engine with a
  longer name.
- **Re-route a surface against the servlet API.** The bridge adapts the
  exchange. A second implementation of the FHIR surface drifts from the first,
  and the first is `dbo-rest`'s to change.
- **Implement the OSGi HTTP Whiteboard.** Nothing in the runtime speaks that
  API — every surface is a `com.sun.net.httpserver` handler — so providing it
  would mean rewriting the surfaces to reach a standard nothing here asks for.

## Beside this

- [`core/dbo-embedded`](../../core/dbo-embedded/README.md) — the host: one
  framework, one class space, the application's own logging. It names no
  framework, which is why an application wanting none uses it directly.
- [`../spring-boot-worker`](../spring-boot-worker) — the performing half. An
  application holding both gets **one** framework.
- [`../spring-boot-test`](../spring-boot-test) — testing an application built
  on either.

## The commands

```
./gradlew :assembly:spring-boot-server:test
./gradlew :assembly:spring-boot-server:bundleIndex   # what a host installs
./gradlew :samples:spring-boot-server-app:run
```
