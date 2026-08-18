# DBO — a multi-tenant FHIR object store

DBO is a FHIR storage engine for platforms that host many healthcare tenants
and cannot accept one FHIR version, one shared database, or one trust root
for all of them.

- **Version-plural.** R4 and R5 personalities run concurrently over one
  engine, per tenant and per domain, and the engine holds no version
  knowledge at all. A domain written under R4 reads as R5 through converters
  rather than a migration.
- **Isolated by construction.** A tenant is a database. The management plane
  provisions it without ever seeing its credentials, and erasing a tenant is
  a `DROP DATABASE` rather than a delete sweep somebody has to trust.
- **Its own authority.** Each tenant issues its own tokens, and the store
  surface accepts only that tenant's. A cross-tenant token fails at signature
  verification, not at a permission check.
- **Postgres and nothing else.** No cache tier, no broker, no queue service.
  Durable work, subscriptions, change feeds and coordination all run on the
  database that already holds the data.
- **Embeddable.** The production bundles boot inside a host application's own
  JVM, so development and test run against the real engine rather than a
  substitute. Cold start is about five seconds.
- **Honest.** Search is strict — an unsupported parameter is a 400, never a
  quietly broader result set — and the CapabilityStatement is generated from
  what is actually implemented.

## Status

Implementation is underway and CI-green: the engine, feeds, R4 and R5
personalities, tier-1 search, subscriptions, terminology, the tenant
authority, personal-data isolation, tenant policies, maintenance and the
Kubernetes provisioning operator are built and proven by 267 behaviour-named
tests. Durable work planes, routing, the process catalogue and operations are
specified and not built.

The living status page is
[docs/plans/implementation-status.md](docs/plans/implementation-status.md).

## Documentation

The specification is an [arc42](https://arc42.org/) tree:

- [docs/README.md](docs/README.md) — the documentation index
- [docs/arc42-001-introduction](docs/arc42-001-introduction/README.md) — goals
  and the founding requirements
- [docs/arc42-009-architecture-decisions](docs/arc42-009-architecture-decisions/README.md)
  — the resolved questions, including the
  [adoption path from an existing FHIR server](docs/arc42-009-architecture-decisions/README.md)
- [docs/arc42-008-crosscutting/design-rationale.md](docs/arc42-008-crosscutting/design-rationale.md)
  — why the engine is shaped this way

## Modules

| Module | What it is |
|---|---|
| `dbo-core` | The zero-dependency object API — no FHIR, no framework |
| `dbo-postgres` | The engine: single-transaction writes, envelopes, history, the outbox |
| `dbo-fhir-common`, `dbo-fhir-r4`, `dbo-fhir-r5` | Personalities, each with a private HAPI stack |
| `dbo-rest` | The FHIR HTTP surface — JDK `HttpServer`, virtual threads, no framework |
| `dbo-auth` | The per-tenant OIDC authority, JDK crypto only |
| `dbo-pdi` | Personal-data isolation — identifying elements encrypted in the payload |
| `dbo-policy` | Audit and write discipline as tenant policy |
| `dbo-subscriptions` | Durable subscription delivery over the change feed |
| `dbo-sync` | Declared content dependencies streamed between tenants |
| `dbo-terminology` | Concept-per-row terminology and its operations |
| `dbo-maintenance` | Sealed archives: backup, restore, export, import |
| `dbo-tenant`, `dbo-tenant-k8s` | Tenant runtime wiring and the in-cluster provisioning seam |
| `dbo-operator` | The Kubernetes provisioning operator |
| `dbo-server` | The serving distribution |

## Licence

MIT. See [LICENSE](LICENSE).
