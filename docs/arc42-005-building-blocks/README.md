# Building blocks — layering (§1)

What the code is divided into, why the divisions fall where they do, and which
divisions the build enforces. The behaviour behind each name is in
[the crosscutting concepts](../README.md); this page is the map, not the
territory.

## The shapes a deployment runs

Three, and the difference between them is not packaging taste.

**One OSGi container** (Felix) holds the serving runtime. Everything that
touches a tenant's data is a bundle in it, because a tenant arrives and leaves
at runtime and its services are registered and retracted with it — a
lifecycle a flat classpath cannot express. `core/dbo-server` is the shipped
distribution: no source of its own, just the assembly and its launcher.

**Plain jars beside it**, each its own process with its own credentials and no
bundle: `core/dbo-operator` (provisions tenants against Kubernetes),
`core/dbo-fleet` (reads a deployment from outside and acts on it), and
`core/dbo-verify` (checks a sealed archive on somebody's laptop with no
network and no account). They are separate deliberately — the operator is
less privileged than the tenant, the fleet reader holds no store at all, and
the verifier must run where nothing else of ours does.

**A console that is not shipped.** `karaf/` assembles a development shell;
`karaf/commands` reads runs and the step catalogue from inside a running
node. It binds tenant-plane services from the registry, which a shipped
console must not do, and it is kept out of the serving distribution for
exactly that reason. See [the console plan](https://github.com/jengu-net/dbo/blob/main/docs/plans/karaf-console.md).

## The layering, as the build enforces it

Dependencies point one way. `dbo-core` names nothing else, and
`dbo-postgres` names only the JDBC driver — those two are load-bearing
constraints, not observations, and adding a library to either needs a reason
that survives being read aloud.

```
processes            dbo-operator     dbo-fleet      dbo-verify
outside the             │                │               │
container               ▼                ▼               ▼
                 ┌──────────────────────────────────────────────┐
composition      │ dbo-tenant  ·  dbo-tenant-k8s                │
                 │ bring-up, every HTTP door, the service sets   │
                 └──────────────────────────────────────────────┘
                        │            │             │
doors and        ┌──────▼─────┐ ┌────▼──────┐ ┌────▼─────────────┐
guards           │ dbo-auth   │ │ dbo-pdi   │ │ dbo-policy       │
                 │ dbo-scim   │ │ the §14   │ │ audit and write  │
                 │ authority  │ │ membrane  │ │ discipline       │
                 └────────────┘ └───────────┘ └──────────────────┘
                        │            │             │
work and         ┌──────▼────────────▼─────────────▼─────────────┐
participation    │ dbo-work · dbo-runner · dbo-stream · dbo-sync │
                 │ dbo-subscriptions                             │
                 └───────────────────────────────────────────────┘
                        │
faces            ┌──────▼────────────────────────────────────────┐
                 │ dbo-fhir-r4 · dbo-fhir-r5   (thin)            │
                 │ dbo-fhir-element  (shared FHIR implementation)│
                 │ dbo-fhir-common · dbo-fhir-stack             │
                 └───────────────────────────────────────────────┘
                        │
engine           ┌──────▼────────────────────────────────────────┐
                 │ dbo-core   (no FHIR, no framework, no deps)   │
                 │ dbo-postgres (JDBC only) · dbo-terminology    │
                 └───────────────────────────────────────────────┘

leaves installed everywhere: promise · dbo-promises · dbo-telemetry
                             dbo-telemetry-otlp · dbo-logging
```

- **`dbo-core`** — the version-agnostic object engine: envelope model,
  identifiers, references, the outbox, the search-criteria SPI, the
  `ObjectStore` contract. Plain Java, zero framework, zero FHIR.
- **`dbo-postgres`** — that contract over JDBC on virtual threads, with
  Liquibase behind advisory session locks. The JDBC driver is its only
  dependency.
- **`dbo-terminology`** — concept-per-row terminology and its operations. It
  names no other module at all.
- **`dbo-fhir-stack`** — the HL7 core and HAPI, embedded once and exported, so
  a container pays for the engine once rather than once per version. It has
  **no source**: it is a repackaging, and the one bundle whose imports are a
  hand-written closed list rather than computed.
- **`dbo-fhir-element`** — the shared FHIR implementation every version is
  served through, and the element-model face in its own right. At ~7,000 lines
  it is the largest module, and both version personalities delegate to it.
- **`dbo-fhir-r4`, `dbo-fhir-r5`** — the version personalities, each binding
  the shared implementation to one release's model classes.
- **`dbo-rest`** — the FHIR HTTP surface, plus the contracts the surfaces
  behind it implement (`RequestAuthenticator`, `AuditSurface`, `WorkSurface`,
  `AuditProjection`).
- **`dbo-auth`** — the per-tenant OIDC authority, JDK crypto only.
  **`dbo-scim`** — staff provisioning served inside the membrane.
- **`dbo-pdi`** — personal-data isolation: identifying elements encrypted
  inside the payload, and the vault that holds the keys.
- **`dbo-policy`** — audit and write discipline as tenant policy.
- **`dbo-work`** — runs as records, step declarations, the claim.
  **`dbo-runner`** — the participation lane and the embeddable step runner.
  **`dbo-stream`** — the lane's third carrier, over the store's own substrate.
  **`dbo-sync`** — declared content dependencies and the replication lane
  between two appliances of one tenant.
- **`dbo-subscriptions`** — durable subscription delivery over the change feed.
- **`dbo-maintenance`** — sealed archives: backup, restore, export, import.
- **`dbo-tenant`** — the composition root. It brings a tenant up, mounts every
  door, and registers the service set; `dbo-tenant-k8s` adds the in-cluster
  provisioning seam.
- **`promise` / `dbo-promises`** — requirements as code: the promise framework
  and this store's catalogue of them, which the REQ tables are generated from.
  See [promise](../arc42-002-constraints/promise.md).
- **`dbo-telemetry`** and **`dbo-telemetry-otlp`** — the measurement seam and
  its one exporter, both installed everywhere and idle without an endpoint,
  because a code path first run in production is the last place to first run
  it.
- **`dbo-logging`** — the single slf4j binding for the runtime, with SPI-Fly
  mediating the ServiceLoader lookup.

Not in any deployment: `core/harness` and `core/conformance` (the test
suites), `core/dbo-test-model` (a face for tests that must not be FHIR), and
`bench/` (throughput measurement, its comparison harness a separate Gradle
build of its own).

## Level 2 — a tenant's store, as bring-up assembles it

One chain per tenant, built once at bring-up and shared by everything wired
for that tenant. Reading it outward-in tells you the order the guards run.

```
FhirStoreFacade                 what a face offers the world
  └─ PolicyObjectStore          audit and write discipline — OUTERMOST, so
       │                        audit sees interactions after authorisation
       │                        and never content
       └─ PdiObjectStore        the §14 membrane — only when the tenant
            │                   declares personal-data isolation
            └─ PgObjectStore    the storage engine
```

The ordering is a decision, not an accident, and the comment at the wiring
says so: audit must not see payloads the membrane would have hidden. Two
stores stand outside this chain — the authority keeps its own, so minting a
token does not write an audit entry, and each zone keeps one for its
declarations.

Layers are added by wrapping, never by inheritance, and each decorator
forwards what it has no opinion about. `ObjectStore` deliberately declares no
default methods: a default that a wrapper could inherit would let it discard
an argument silently, and the failure would be toward permissive.

## Where the rules about all this are written

- **Imports are computed.** Every bundle with source lets bnd derive
  `Import-Package` from bytecode; the hand-written part is a filter. The one
  exception is `dbo-fhir-stack`, which has no source and keeps a closed list
  that a test walks every embedded class to check.
- **A new module another module imports must join the bundle set in the same
  commit**, in all three places fed from one list. bnd resolves it on the
  classpath and the container dies on first use otherwise.
- **Green builds prove little here.** `EmbeddedContainerIT`, `TenantOsgiIT`
  and `ServerDistIT` are the ratchets, and both in-JVM containers must install
  what the distribution installs.

These are stated as rules, with the failures behind them, in
[working rules](../arc42-002-constraints/working-rules.md) and in the
repository's `CLAUDE.md`.
