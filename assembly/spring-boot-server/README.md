# The serving runtime, inside a Spring Boot application

One dependency. The application starts, and it is a DBO node: tenants come
up, their surfaces answer **on the application's own port, through its own
servlet container**, guarded by an authority Spring Security knows about. A
bean that implements an extension point is an extension point. A service a
tenant registers is injectable. An application author never learns the word
OSGi.

This document is the plan it is being built to.

The host — one framework, one class space, the host's own logging, the Spring
Boot generation — is [`../spring-boot-core`](../spring-boot-core/README.md),
and those decisions are argued there rather than repeated here. The
performing half is [`../spring-boot-worker`](../spring-boot-worker). An
application holding both gets one framework.

## What is already here

- `build.gradle.kts` — the module, its dependency shape, and `bundleIndex`,
  which writes the ordered bundle set into `META-INF/dbo/bundles.index`. It
  reads `dboRuntimeModules` from the root build, which is the SAME list the
  serving distribution and the development console install from. Copying that
  list here would reintroduce exactly the drift the single list exists to
  prevent.
- The module is wired into `settings.gradle.kts` and publishes as
  `cloud.jengu.dbo:dbo-spring-boot-server`.

Run `./gradlew :assembly:spring-boot-server:bundleIndex` and read
`build/generated/dbo/META-INF/dbo/bundles.index` to see what a host installs.

## What the sample application says about this

`sample/` is an integrator's code, and it is the specification for this
module's public surface.

**Most of it does not need this module at all.** `Surface` is an HTTP client:
a token, a base URL, and the verbs an actor performs. It holds no store and
imports nothing from the runtime. An application that only reads and writes
records over a tenant's surface should keep writing `Surface`, and this
module should not tempt it into an embedding it does not need.

**`NoticingATenant` and `WatchingTheWork` are what only runs in-JVM.** A
`TenantLifecycleListener` is told a tenant reached a point; a
`TenantObserver` reads a tenant's stream as a named durable consumer. Both
are registered as services carrying properties — the point and the target for
one, the domain and the consumer name for the other — and the tenant
activator's whiteboard refuses a registration missing what it needs, by name,
at registration. That refusal has to survive the translation: a Spring bean
declaring no consumer name must fail at context refresh with the bean's name
in the message, not observe nothing quietly. A service that silently observes
nothing is indistinguishable from one that is working and has nothing to do,
which is the confusion the mechanism exists to remove.

**`AdmitStep` belongs to the worker assembly**, and its own README reads it.

## The two things this module is really for

Everything else here is plumbing. These two are the reason an application
embeds the runtime instead of talking to it over a socket.

### One port: the surfaces mount in Spring Web

`TenantRuntimeManager` owns exactly one `com.sun.net.httpserver.HttpServer`
and every surface in the runtime registers a context on it — the FHIR
endpoint, the tenant's OIDC authority, SCIM, the step surface, maintenance,
erasure, blob, configuration, the ops readouts, the identity hub. One seam,
`createContext(path, handler)`, and everything goes through it.

That is the whole bridge. `HttpServer` is an **abstract class**, not a
factory-sealed one: a subclass whose `createContext` records the handler and
whose `bind`, `start` and `setExecutor` are no-ops is a complete, legal
`HttpServer` that never binds a socket. Spring Web then dispatches to the
recorded handlers.

```
Spring DispatcherServlet
  └── DboSurfaceHandler          longest-prefix match, the rule HttpServer uses
        └── HttpHandler          the runtime's own, unmodified
              └── ServletHttpExchange extends HttpExchange
```

Three pieces:

- **`SpringHttpServer extends HttpServer`** — a path-to-handler registry.
  Lifecycle methods are no-ops because Spring owns the lifecycle and the
  threads.
- **`ServletHttpExchange extends HttpExchange`** — headers, method, URI,
  request and response streams, `sendResponseHeaders`, `getRemoteAddress`,
  `getPrincipal`. Every method of `HttpExchange` is abstract and every one has
  a servlet equivalent. Response streaming matters: `FhirHttpServer` writes
  headers on first write through its own `HeadersOnFirstWrite`, and the
  adapter must not buffer a bundle in memory to defeat that.
- **The dispatch point** — a `HandlerMapping` over the prefixes the runtime
  has registered, so Spring's own controllers, filters and error handling sit
  beside the surfaces rather than around them.

**The runtime takes the server off the whiteboard.** Rather than a Spring-
shaped special case inside `dbo-tenant`, the host registers its `HttpServer`
as an OSGi service and the tenant activator uses it when one is there and
binds a port when none is. That is the pattern the runtime already uses for
`TenantDatabaseProvisioner`, `StepService`, `Lane` and `TenantObserver` — the
services ARE the configuration — and it means the distribution's behaviour is
unchanged: no service, no change.

**Both halves of this are done.** `TenantRuntimeManager` takes a shared
`HttpServer` and, when given one, binds nothing, starts nothing, replaces no
executor and stops nothing on close; `port()` then answers the declared port,
because a server standing in for somebody else's web tier has no address of
its own. `Activator` tracks an `HttpServer` service behind
`dbo.tenant.http.shared` and waits for it the way it already waits for a
provisioner. Unset, the distribution is untouched, which the three container
tests say.

`AHostHoldsTheWebTierAndTheRuntimeMountsOnItTest` holds it: a recording
server, no database, and five assertions each confirmed red by mutation.

**What this buys beyond one port**: one TLS configuration, one access log,
one set of filters, one metrics binding, and `/t/{code}/fhir` reachable from
the application's own `MockMvc` tests. What it costs is stated under
*The trap* below, and it is not nothing.

### Spring Security knows the tenant's authority

Each tenant is its own OIDC authority: it mints tokens, publishes JWKS, and
`TenantAuthority.validate(token)` returns an `AuthContext` carrying the
client, the FHIR user, the acting client, the scopes, the purpose of use, the
organisations and the audience. Only bearer tokens verifying against *that
tenant's* keys pass — a cross-tenant token is indistinguishable from garbage,
which is the strongest anti-enumeration property available.

Two directions, and they are not equally safe.

**Outward, on by default: the tenant authenticates, Spring is told.** A
`DboTenantAuthenticationProvider` delegates to `TenantAuthority.validate`, so
the application's own controllers can be secured by the same tokens its
tenants issue. The `AuthContext` becomes an `Authentication` whose authorities
are the SMART scopes and the role grants, unchanged in spelling — `SCOPE_` is
Spring's convention and the scope string is FHIR's, so `@PreAuthorize` reads
against what the token actually says. `fhirUser`, `actClient`, `purposeOfUse`,
`organisations` and `audience` ride as typed details, not as a map.

Configured as a resource server per tenant, keyed by the path the request
came in on — `/t/{code}/**` selects the authority for that code.

**Inward, off by default and explicit to turn on: Spring authenticates, the
runtime is told.** A `RequestAuthenticator` backed by Spring Security's
`SecurityContext`, so an application whose own identity provider issues
tokens can have those accepted at a tenant's FHIR surface.

This one is a decision with a blast radius, and the configuration has to read
like one. `REQ-DBO-AUTH-DENY-BY-DEFAULT` is a promise; the distribution exits
78 rather than serve tenants without an authority. Letting an application's
own issuer grant access to a tenant's records makes that application's
identity provider a second authority over somebody else's data — legitimate
for a single-tenant deployment the application owns, and not something to
arrive at by a property defaulting to true. So: named per tenant, refused
unless the tenant's spec says it is allowed, and logged at startup as part of
the resolved posture.

**Whichever direction, the same context must be bound.** This is the part
that is easy to get wrong and impossible to notice. `AuthorityAuthenticator`
does not only decide yes or no — it binds four request-scoped values the
store reads afterwards:

| Bound | Read for |
|---|---|
| `Caller.set` / `setChain` | who the trail records, and on whose behalf |
| `Disclosure.set` | whether a read discloses identity, and under what purpose |
| `Reach.bind` | which organisations the answer may come from |
| `Audience.serving` | what a partner's credential may see of each type |

A `RequestAuthenticator` that authenticates correctly and binds none of these
produces a store that answers — with identity omitted, reach unbound and the
trail attributing every write to nobody. Nothing fails. That is why the
inward direction is one class with one job, tested against the outward one
for identical bindings on identical claims, rather than an interface an
application implements.

### The trap: thread reuse is not a detail

`com.sun.net.httpserver` in the distribution runs on
`newVirtualThreadPerTaskExecutor()` — a fresh thread per request that dies
afterwards. A `ThreadLocal` left set is collected with the thread. Spring Web
runs on a **pooled** thread. A `ThreadLocal` left set is read by the next
request on that thread.

Four values are bound per request and must not survive one:

| Bound | Read for |
|---|---|
| `Caller.set` / `setChain` | who the trail records, and on whose behalf |
| `Disclosure.set` | whether a read discloses identity, and under what purpose |
| `Reach.bind` | which organisations the answer may come from |
| `Audience.serving` | what a partner's credential may see of each type |

Every binding site in the tree was read. `AuthorityAuthenticator.check` binds
all four and has two callers: `FhirHttpServer`, which clears all four in a
`finally` whose comment already anticipates this — *a thread is reused, and a
purpose left behind would disclose the next request's person under the last
one's reason* — and `BlobHandler`, which cleared none. Everything else binds
only what it clears: `ScimHandler` binds `Caller` and `Disclosure` and clears
exactly those two, and `StepSurface` and `LaneVerbService` bind `Caller` and
clear it.

**So it was one door, and it is fixed.** `BlobHandler` clears all four, and
`ACredentialDoesNotOutliveItsRequestTest` is the test that would have caught
it: a real `HttpServer` on a pool of **one**, two requests, and a guard that
reports what it found already bound when the second arrived. It asserts the
two requests shared a thread before asserting anything else, because a pool
that handed the second a different thread would report "nothing was bound"
for a reason that has nothing to do with the door.

Nothing had ever gone wrong, and that is the whole point: the door was
correct because of the executor it happened to be wired to. A door whose
safety belongs to whoever wired the executor is safe by accident, and this
bridge is the change that takes the accident away.

**What is still owed before the bridge ships:**

1. `ServletHttpExchange` clears all four in its own `finally` regardless —
   belt and braces, because the handler set is not closed and the next one
   will be written by somebody who has not read this.
2. The same two-requests-one-thread test, over the servlet adapter rather
   than over `HttpServer`, so the property is asserted where it will actually
   be at risk.

## The steps

### 1. The module and its cargo — done

### 2. The host — in `spring-boot-core`

Read its [step 2](../spring-boot-core/README.md#2-boot-a-framework-nobody-can-see).
Nothing to build here.

The API packages this assembly adds to the computed list are the ones it
compiles against: `cloud.jengu.dbo.tenant`, `cloud.jengu.dbo.asking`,
`cloud.jengu.dbo.fhir.common`, `cloud.jengu.dbo.rest` and
`cloud.jengu.dbo.work`. Computed from manifests, not listed.

### 3. Configuration, as one properties class

`@ConfigurationProperties("dbo")`, a record tree, with the configuration
processor generating metadata so an IDE completes it.

The framework properties the activators read are the contract — read through
`BundleContext.getProperty`, and the full set is visible in
`core/dbo-server/src/main/dist/bin/dbo-server`, which maps environment
variables onto the same names. This module maps Spring properties onto them.

| Spring | Framework property |
|---|---|
| `dbo.tenants.directory` | `dbo.tenant.dir` — where declarations are kept, not a list of tenants |
| `dbo.http.host` / `.port` | `dbo.tenant.http.host` / `.port` (ignored when mounted in Spring Web) |
| `dbo.management.spec` | `dbo.tenant.management.spec` — the only tenant a property names |
| `dbo.auth.kek` | `dbo.tenant.auth.kek` |
| `dbo.auth.issuer-base` | `dbo.tenant.auth.issuer.base` |
| `dbo.auth.broker.*` | `dbo.tenant.auth.broker.*` |
| `dbo.admin.jdbc-url` / `.user` / `.password` | `dbo.tenant.admin.url` / `.user` / `.password` |
| `dbo.kubernetes.namespace` | `dbo.tenant.k8s.namespace` |
| `dbo.face.images` | `dbo.face.images` |
| `dbo.bring-up.together` / `.streams` | `dbo.tenant.bringup.together` / `dbo.tenant.streams.together` |
| `dbo.ops.token` | `dbo.tenant.ops.token` |
| `dbo.substrate.url` / `.user` / `.password` | `dbo.substrate.*` |

**The refusal travels with the mapping.** `bin/dbo-server` exits 78 rather
than serve tenants without an authority; an embedding that quietly served
them would be a second artifact with a different rule under the same name. So
the starter refuses to start the context unless `dbo.auth.kek` is set or
`dbo.auth.disabled` is explicitly `true`, with the same sentence.

**A property under `dbo.` this module does not recognise is refused, not
ignored.** A mistyped key that reached the framework as nothing is a
deployment that looks configured and is not.

*Proved by:* a table-driven test asserting every `-D` name in
`bin/dbo-server` has a Spring property that produces it. It fails when the
launcher grows a flag and this module does not.

### 4. The HTTP bridge

In this order, because each step's failure is legible only if the one before
it passed:

1. ~~The whiteboard seam in `dbo-tenant`~~ — done.
2. ~~The thread-reuse work under *The trap*~~ — done.
3. ~~`SpringHttpServer` and `ServletHttpExchange`~~ — done.
   `ASurfaceAnswersOnTheApplicationsPortTest` drives them with the servlet
   objects a container would supply and a handler of its own, with nothing of
   the runtime anywhere near it. Five claims, each confirmed red by mutation:
   a handler's status, headers and body reach the response and its request
   reaches the handler; the longest context at or before a path answers it;
   a path no surface is mounted at is left alone, so the application's own
   endpoints still answer on the port they share; a body of unknown length is
   not buffered to learn its size; and a second request on the same thread
   finds nothing the first request's credential bound.
4. ~~The dispatch point~~ — done. `DboSurfaceFilter` offers every request to
   `serve(...)` and carries on down the chain when it answers false, so an
   application's own endpoints and a tenant's surfaces share one port without
   either knowing about the other.

   **No path pattern, deliberately.** A tenant's paths appear and disappear
   as tenants come up and go away, so there is nothing to register against
   that stays true — and a tenant brought up minutes after startup would be
   unreachable. The cost of asking is one lookup in a sorted map; the cost of
   not asking is a deployment that serves whoever happened to be declared at
   boot. That one has a test of its own.

   **Before the application's security chain**, at
   `HIGHEST_PRECEDENCE + 50`. A tenant's doors are guarded by that tenant's
   own authority, and an application's security in front would refuse those
   callers before the tenant ever saw them — a second answer to who may read
   somebody's records, which is the one question this store does not share.
   The outward direction in step 5 adds an answer; this would replace one.

   Proved by `ARequestReachesASurfaceThroughTheApplicationsStackTest` and
   `AddingThisJarMakesTheApplicationANodeIT`: the serving set comes up inside
   a web application context, the surfaces have somewhere to mount, the
   filter is in the chain, the runtime is told to wait for a server rather
   than bind a port, and a deployment with no authority is refused in the
   same sentence the launcher refuses it.

5. A read of a REAL tenant's `/metadata` on the application's port. It needs
   a database, a spec and a bring-up, and it is step 11's — the one assertion
   that ties the whole chain together.

`dbo.http.mount: servlet | own-port | both`, defaulting to `servlet` when
Spring Web is on the classpath and `own-port` when it is not. `both` exists
for a migration and says in its own description that it is for one.

*Proved by:* a FHIR read over the application's own port, through its own
filter chain, asserted with `MockMvc` — and the same read over a real socket,
because a servlet container's own behaviour is not what `MockMvc` exercises.

### 5. The security bridge

Outward first — it is the one that is on by default, and the inward direction
is tested against it.

1. ~~`TenantAuthority` on the whiteboard~~ — done, see above.
2. `DboTenantAuthenticationProvider` over `TenantAuthority.validate`, with
   `AuthContext` to `Authentication` and scopes to authorities.
3. Per-tenant selection by request path.
4. The inward `RequestAuthenticator`, with the four bindings, refused unless
   the tenant's spec allows it.

**`TenantAuthority` now reaches the application.** It used to be held by the
runtime and handed to each surface handler and to nothing else:
`TenantRuntime` did not carry it and `tenantUp` did not register it.
Registering it per tenant is the same act the runtime already performs for
`Asking`, for the reason stated there — *a vocabulary reachable only by
whoever can already reach the engine is a vocabulary for nobody.* Done, with
`ATenantsAuthorityIsOnWhatItPublishesIT` on the shared runtime: a tenant's
runtime carries the same authority the deployment holds, and that authority
verifies its own tenant's token and refuses another tenant's.

The registration is guarded on the authority being present. A tenant declared
without one publishes nothing, which is what a host asking about a tenant
nobody can sign in to should get — rather than something that admits
everybody.

*Proved by:* a controller of the application's own, annotated
`@PreAuthorize`, admitting a token the tenant minted and refusing one another
tenant minted.

### 6. Lifecycle

`SmartLifecycle`, phase below the web server's, so tenants are serving before
the application answers its first request — an application that answers a
health check while its store is coming up gets traffic it cannot serve.

**A tenant that will not come up is not the same failure as a framework that
will not boot.** The framework failing is a refusal: the context does not
refresh. A single tenant failing is not, because a node serving six tenants
should not be taken down by the seventh — except the management tenant, where
the runtime already decides: a deployment whose management tenant will not
come up serves nothing, and the activator throws. Carry the distinction
through rather than flattening it.

### 7. One tenant in configuration, the rest through a bean

**The management tenant is the only one Spring configuration declares**, and
the runtime already says why: it is *declared by deployment configuration
rather than by a file in the watched directory, so the scan loop that
retracts tenants cannot retract the thing recording retractions.* It is also
the one failure with nowhere to be recorded — a deployment whose management
tenant will not come up serves nothing — so it belongs in the thing that
refuses to start, which is the Spring context.

So: `dbo.management.spec`, a resource location, materialised to a path and
handed to `manages(...)`. `TenantSpec.parse` is the validator; a spec that
does not parse is refused at context refresh, naming the resource. Nothing
else about tenants is a property.

**Every other tenant is declared through `DboDeclarations`**, a bean:

| Method | Is |
|---|---|
| `declare(TenantSpec)` | this deployment is told to serve it |
| `withdraw(String code)` | told to stop. The tenant stops serving; its data is untouched |
| `declared()` | what it has been told, now |
| `apply()` | apply and reconcile on this thread, answering with the outcome and its cards, rather than waiting for the next beat |
| `erase(String code)` | the data goes. Separate, and it says so |

Four things make this the right shape rather than a convenience.

**It is not a record writer.** A `TenantDeclaration` is
`Handling.projectedConfig()` — owned by the lane that applies it, precisely
so that *nobody's tenant users may author what this deployment serves*. A
bean writing those records directly would be fighting the handling rule that
protects them. What the bean supplies is the **source** the applier reads,
and `ConfigSource` already names this exact seam: *a git repository, a
mounted ConfigMap, a directory, a lane from a cloud.* A Spring bean is a
fifth of the same kind.

**Declaring is recorded, because authoring is the API.** `ConfigApplication`
opens a run, writes the declarations into the management tenant as records,
and answers with an outcome carrying a card per refusal. So *what is this
deployment declared to serve*, *what did it say yesterday*, and *who declared
that, and when* are all ordinary questions against an ordinary store — which
is the whole reason declarations stopped being files nobody outside the node
could read. `declare()` returns that outcome. It does not throw on a refusal
and it does not return void: a refusal is a card naming the declaration and
the reason, and losing it is how a tenant silently never appears.

**Withdrawal and erasure are two acts, and the runtime keeps them apart.**
Erasure is *an operator act, and deliberately not reachable from the scan
path — nothing in reconciliation calls this, and a spec disappearing retracts
serving and touches no data. The two are separate steps because they carry
different authority, and one of them cannot be undone.* Two methods, and
`erase` refuses the management tenant for the runtime's own reason: it holds
the record of every erasure.

**Where the declarations actually live is the application's to decide**, and
that is two sub-steps rather than one.

*7a — over the directory, which works today.* The bean writes spec files into
`dbo.tenants.directory` and calls `apply()`. No change anywhere outside this
module: `DirectoryConfigSource` is already what the manager reads, the marker
is already content-digested, and withdrawal is already a file that stopped
being there. This ships first, and an application with one node and a volume
needs nothing more.

*7b — over a source the application supplies.* **The seam is done.**
`TenantRuntimeManager.declaredFrom(ConfigSource)` says where the declarations
are read from, and `Activator` tracks a `ConfigSource` service behind
`dbo.tenant.declarations.shared` — the same pattern as the `HttpServer` seam.
Unset, this node reads the watched directory exactly as it always has.

A directory is state on one node's disk, which is why this exists: an
application in a fleet wants its declarations where the rest of its
configuration is, read the same way by every replica. All that changed is
where a pass READS. What a declaration means, what applying it costs and what
a partial read implies stayed where they were, which is what keeps five
sources from becoming five designs.

The contract that had to survive the widening is the one `ConfigSource`
states: *a source that cannot be read throws. It never answers with an empty
set, because empty and unreachable are the same sentence to whoever has to
decide what is missing — and deciding that wrongly is how a bad read becomes
a withdrawal.* The runtime already guarded this for the directory, in
`anUnreadableSourceRetractsNothing` and
`aDirectoryThatCannotBeReadIsNotADirectoryDeclaringNothing`. The seam extends
the guard to any source:
`ADeploymentReadsItsDeclarationsFromWhereItWasToldTest` asserts a supplied
source is read instead of the directory, and that one which throws stops the
pass rather than being read as a deployment declaring nobody. A Spring bean
whose backing store blips and answers `List.of()` would retract every tenant
on the node, so the starter's own implementation is written against that
assertion rather than around it.

**What the bean still owes:** `declare` / `withdraw` / `apply` over a
`ConfigSource` the starter registers, and the test that declares a tenant
through it, serves a read, withdraws it, and finds its data still there.

### 8. Services out, beans in

Both mechanisms are the host's — [core steps 4 and
5](../spring-boot-core/README.md#4-beans-in). This module declares what goes
through them.

Out, per tenant, keyed by `tenant=<code>`: `ObjectStore`, `FhirStoreFacade`,
`ChangeFeed`, `Asking`, `Lanes`, `PolicyObjectStore` where the tenant has
policy, and `TenantAuthority` once step 5 registers it. The lookup bean is
`DboTenants` — `store(code)`, `asking(code)`, `feed(code)`, `serving()`,
`states()` and `troubles()`, the last two because *serving, coming up and
failed are different facts*, and a list that silently omitted a tenant that
failed would answer "which tenants are fine" while looking like it answered
"which tenants exist".

`DboTenants` reads and `DboDeclarations` (step 7) declares. Two beans,
because being told to serve a tenant and asking one a question are not the
same right, and the runtime already puts them behind different scopes.

In:

| Bean implements | Needs |
|---|---|
| `TenantLifecycleListener` | `dbo.tenant.point`, optional `dbo.tenant.target` |
| `TenantObserver` | `dbo.tenant.domain`, `dbo.tenant.consumer`, optional `dbo.tenant.target` |

From an annotation beside the class — `@DboTenantListener(point = SERVING)`,
`@DboObserver(domain = WORK, consumer = "admissions-view")` — whose
attributes are the property names one to one, so the mapping is legible
rather than translated.

### 9. Events out

Tenant lifecycle and changes arrive through step 8: the starter registers one
listener and one observer of its own, publishing `TenantReachedPoint` and
`TenantChanged`. The observer is **off by default** — an observer is a named
durable consumer, and one that exists because a property defaulted to true is
a consumer nobody chose. The lifecycle one is on: it is a callback, it is
cheap, and a tenant coming up is something an application wants to know.

`DboContainerFault` comes from the host.

### 10. Actuator — conditional, host-owned mechanism

Health reporting the framework's state and the tenants serving, by code. Down
when the framework is not `ACTIVE`; degraded, with the codes, when a
configured tenant is not serving. Telemetry through Micrometer where there is
a registry — a second destination for numbers the seam already carries, not a
second measurement.

### 11. What proves the whole thing

A toolset can be built, proven and unreachable. This module is exactly that
shape: its own tests can pass while nothing an application writes reaches it.

So the proof is an application. `src/test` holds a Spring Boot application
that declares the sample's extension classes as beans, points at a tenant
from the guide's world, and:

1. answers a FHIR read **on the application's own port**, through its own
   filter chain;
2. refuses the same read carrying another tenant's token;
3. admits it to a controller of the application's own under `@PreAuthorize`;
4. has its `TenantLifecycleListener` bean called;
5. has its `TenantObserver` bean handed a change;
6. injects `Questions` for that tenant and asks it something;
7. declares a **second** tenant through `DboDeclarations`, serves a read
   against it, withdraws it, and finds its data still there;
8. logs, from inside a bundle, into an appender the test installed;
9. serves two requests on one pooled thread and leaks nothing between them.

Nine assertions, one context, one Postgres. Anything this module can do that
is not on that list is not yet proved to be reachable.

The bring-up rule applies: a test whose claim IS that a tenant comes up takes
a world of its own, and this one's claim is that an application reaches it —
so it is sized for that question and its reason is recorded with the rest.

## Deliberately not done

- **No Spring `DataSource` sharing.** The store manages its own connections,
  per tenant, with its own pool and its own transaction discipline. Handing
  it the application's pool puts the application's transaction manager in the
  path of a single-transaction write the store's promises rest on.
- **No Spring Data repository over the store.** The store's API is
  deliberately small and the question surface is `Asking`. A repository over
  it would be the engine with a longer name.
- **No tenant list in `application.yaml`.** Only the management tenant is a
  property. A deployment that declared its tenants in configuration would
  provision databases as a side effect of a config refresh, would have no
  record of who declared what and when, and would answer *what is this
  deployment declared to serve* only to somebody with a shell on the node.
- **No reimplementation of a surface against the servlet API.** The bridge
  adapts the exchange; it does not re-route. A second implementation of the
  FHIR surface drifts from the first, and the first is `dbo-rest`'s to change.
- **No OSGi `HttpService` or HTTP Whiteboard implementation.** Nothing in the
  runtime speaks that API — every surface is a `com.sun.net.httpserver`
  handler — so providing it would mean rewriting the surfaces to reach a
  standard nothing here asks for. The seam that exists is `HttpServer`, and
  registering one on the whiteboard is the OSGi-shaped way to fill it.

## The commands

```
./gradlew :assembly:spring-boot-server:bundleIndex
./gradlew :assembly:spring-boot-server:test
./verify
```
