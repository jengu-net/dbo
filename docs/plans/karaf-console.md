# The Karaf console — a proposal

`kubectl` for the OSGi container: a way to see what is actually happening
inside a running DBO node. This is a plan, not built, and not part of the
shipped distribution.

## Scope

Development, demonstration and operator tooling. **Production is out of scope**:
no deployment runs Karaf, and nothing here proposes that one should.

It is also not a second runtime. The serving distribution stays the standard
Felix launcher over `bundle/` with no launcher code of its own, because what
runs in production must be exactly the bundle set CI tests. Karaf is a second
*consumer* of that same bundle set.

Production stays out of scope in this document, but it is worth knowing what
would have to be true if that ever changed — that is the first ceiling below,
and it is the one that decides how far the idea can go.

## Why the container is worth seeing into

This codebase's characteristic failure compiles, resolves, publishes and dies
on first use — through a hand-written `Import-Package`, a lazily-reached jar
outside a bundle, a binding that silences itself. Today the only witness is a
container test going red, which says *that* something is unwired, not *what*.
`bundle:tree-show`, `package:exports`, `service:list` and `bundle:diag` against
a live container are the instrument for exactly that class of bug. That alone
justifies the work.

## Four tiers, and what each one costs

**T0 — container introspection.** Bundles, wiring, exports, services,
resolution failures. No DBO code at all; it is what the shell already does.

**T1 — platform-plane introspection.** Tenants and their provisioner state,
feed positions, subscription health, live DBOS workflow instances. A small
command bundle binding platform-plane services read-only. No tenant content.

**T2 — demonstration and testing.** Connect to a tenant whose credentials are
known, drive the FHIR surface, seed a fixture, watch a feed, trigger a sync
hop. Needs a real token (below), not a service handle.

**T3 — administration.** Tenant lifecycle, key rotation, archive and restore,
retention, shredding. Real operations with real consequences.

**T4 — the process console.** Once the process catalogue (§8) exists, commands
*generated from* it rather than written per process: `dbo:process list | show |
steps | start`, completion driven by the live map, working for every process
any bundle declares. The catalogue is a projection and projections are
generated, so hand-writing a command per process would be the wrong shape.

## The loop, which is tier 0 and pays first

Built and proven. `./gradlew dev` publishes the bundle set to the local Maven
repository; Karaf's `bundle:watch` re-reads a changed bundle from there and
refreshes it in place, about a second behind the publish. `:karaf:console`
assembles the whole thing. [karaf/README.md](../../karaf/README.md) is the
working instruction; what follows is what the assembly had to settle, because
each item is a way the two containers differ.

- **The bundle set is one list.** It lives in the root build as
  `dboRuntimeModules`; the serving distribution and the console both generate
  from it. Two hand-maintained copies would drift, and the drift would appear
  as "works in the console, dies on first use in the distribution". The watch
  set is not a second list either: `dbo-console:watch`, the first command of its own,
  derives it from the bundles the container has installed and drops the ones
  whose embedded stack is too heavy to re-read on every publish. Measured
  weight rather than a name list, because the names would drift the same way —
  and because embedding a small private jar is no reason to stop watching a
  module that changes daily.
- **The framework floors differ, and it bites immediately.** Felix computes the
  system bundle's exports from the running JDK's modules and so exports
  `com.sun.net.httpserver` for free; Karaf names its extra packages explicitly
  and does not name that one. The FHIR surface is a JDK `HttpServer`, so the
  whole serving half failed to resolve until the console added it. This is the
  general hazard in one instance: the fat bundles hand-write `Import-Package`,
  and a different floor resolves them differently.
- **The console pins its own JDK.** Karaf's launcher finds a JVM through the
  system registration, which can easily be older than the toolchain the
  bundles are compiled with; the assembly writes `bin/setenv` from the build's
  own toolchain. It also sets a 2g heap, because the R5 validator loads the
  FHIR core package eagerly and on a default heap dies as HAPI-2330 with a null
  message.
- **Logging is Karaf's, not the distribution's**, for the reason in the floor
  section below — which means a fault in the real logging arrangement cannot
  show up here.

## Where the sky ends

Four distinct ceilings. The first is architectural and permanent.

**The operator is deliberately less privileged than the tenant.** The property
the whole isolation story rests on is that `TenantDatabaseProvisioner` returns
a `DataSource` and never credentials; archives are sealed with the owner's key
so the operator cannot read them; the vault never holds plaintext. A shell that
binds `FhirStoreFacade` from the registry contradicts all of it in one line.
Production is out of scope today, so this costs nothing now — but it is the
ceiling on the whole idea: a console can never be more privileged than the
operator is allowed to be, and this store has deliberately made the operator
less privileged than the tenant. Were Karaf ever to reach a deployment it would
be **platform-plane only, permanently** — not as a phase, but as the architecture
holding. Which means T2 and T3-over-services should be built as development and
demonstration affordances that cannot be switched on in a deployment, rather
than as a general capability that a later decision has to claw back.

**Karaf's value decays as the tool becomes administrative.** `kubectl` talks to
an API server that enforces RBAC; this shell lives inside the process, where
there is no equivalent of "the server said no". The more administrative the
console becomes, the more it wants to be *outside* the node talking to a guarded
surface — at which point it is a plain network CLI and being a Karaf command
stops paying. The sweet spot is T0, T1 and T4's development loop, precisely
where being inside the box is the point.

**Mutation without attribution.** The audit trail stamps who and when from the
token; it is open upward and closed downward by design. A command acting through
a platform-plane service carries no token, so its action is unattributable.
Mutating commands are therefore capped until they carry an identity — which is
the token-exchange route below, not a shell flag.

**One node looks like the deployment.** Karaf sees one JVM. Network-wide answers
depend on the dOSGi accumulation (§5) that the process map is specified to use.
Until that exists, every answer must be labelled with its node, or the command
must not exist — a `dbo:process list` that silently means "here" invites exactly
the wrong reading.

## The plane split

§7.4 already draws the line the console needs, so use it rather than inventing
a second one.

- **Platform-plane commands bind registry services directly.** Tenant lifecycle
  state, provisioner state, bundle wiring, the process map, DBOS instances.
  These have no tenant-token concept; an operator console is the correct consumer.
- **Tenant-plane commands go through the authenticated surface**, so
  `RequestAuthenticator` runs, scopes are checked, audit lands and personal-data
  isolation applies. The cost is an HTTP client in the shell; the alternative is
  a guard seam that looks real and is not.
- **`dbo:connect <tenant>` is the plane hop**, not a UI convenience. It is issued,
  checked and audited as one, which means the console exercises the hop-grant
  machinery instead of routing around it.

Tenants differ in kind — FHIR version, personal-data isolation opt-in, whether
the tenant is a zone — so the connected context offers only the commands that
kind supports. Karaf subshells and service-registered commands give this for free.

### The credential

Token exchange (RFC 8693) already exists for processes acting in a human's name.
The operator authenticates once and each connect exchanges for a tenant-scoped
on-behalf-of token, so audit attributes the action to the human. A shared
client-credentials identity would flatten every operator action into "the console
did it", which defeats the trail's purpose.

## Layout

The scanner that builds the process map is product behaviour on the enforcement
path — a hop grant can only be issued for a hop the declared process shape
contains, and that is checkable at install time. It belongs in `core/dbo-process`
with no console dependency. The console gets the read surface only.

```
karaf/
  feature/    the same bundle list, plus SPI-Fly, minus pax-logging
  commands/   dbo:* — talks to the service registry and the guarded surface, nothing else
  dist/       a minimal custom assembly, close to the Felix floor
```

Beside `core/`, not under it: a command bundle imports the shell API, and that
dependency must not be reachable from anything that ships.

## The floor, before any command is written

**One home for the bundle list.** It currently lives inline in the serving
distribution's build. If the feature repository copies it, the two drift, and the
drift appears as "works in Karaf, dies on first use in the dist" — the bug class
this repository is already worst at catching. Extract it to one place both
generate from. The in-JVM container tests must install what the distribution
installs, and the feature is now a third thing bound by that rule.

**A minimal assembly, eventually.** The console today is stock Karaf with its
own logging, which is what makes it cheap. Keeping the distribution's logging
arrangement instead — slf4j-api as a shared bundle with the provider as its
fragment — means stripping pax-logging, because two providers of `org.slf4j` in
one framework is a race rather than a posture. That is worth doing, since
logging posture is part of what the console exists to observe; it is not worth
blocking the loop on. It also needs the ServiceLoader mediator, and that is a
framework **extension**: it attaches to the system bundle at framework init, so
it belongs in the assembly's boot stage and cannot be a feature installed at
runtime.

## The logging rule

Karaf logs commands and their output. Nothing carrying identifying data is ever
written by this runtime — a search URL alone carries `identifier=system|value`,
and writing it undoes §14. A tenant-plane read printing decrypted content into
the shell log, or into a session history file, is the same defeat by another
route, and it arrives by default rather than by anyone choosing it. Command
logging is off or filtered in the assembly, and console output is held to the
same anonymity posture as the rest of the store.

## What exists

`dbo-tenant:list` and `dbo-tenant:capabilities` are T0/T1: the registry, and a
tenant's own `/metadata`.

`dbo-run:list` and `dbo-run:describe` (#75) are the first commands to read a
tenant's **records**, and they exist because there is nowhere else that answer
can come from: a run over `identity`, `audit` or a configuration domain renders
to nothing on purpose, so the surface that shows clinical work cannot show
operational work at all, and the store's REST is never public. They bind the
tenant-plane `ObjectStore` from the registry, which is exactly what the ceiling
above says a shipped console must not do — and they are allowed to only because
this bundle is not in the serving distribution. Whoever runs it already holds
the database credentials. A console that ships reads this through the
authenticated surface with a session behind it, not through the registry.

`dbo:context` and `dbo:login` (#76) are the session: a position, and an
identity when it acts. The position is where commands about a tenant take their
tenant from; the identity is assumed through the tenant's own authority, by the
same request an HTTP caller makes, so a refusal is the authority's. The secret
is prompted and masked and no token is ever printed. Reads need neither — the
operator could open psql, so requiring a session to look is friction with
nothing behind it.

They read and never act. Retrying, closing and reassigning are declared step
actions carrying their own provenance; a console that acts is an actor nobody
audited.

## Open

- Whether the irreversible ceremonies — erase, shred, archive seal — should be
  console commands at all. The console's convenience is directly against the
  deliberation they are meant to require.
- Whether child instances earn their keep. Two JVMs against one Postgres is a
  real multi-node test, but tenancy scaling is databases and the provisioning
  operator, not JVMs on one host.
