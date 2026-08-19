# DBO — a multi-tenant object store for regulated data, with a FHIR face

A FHIR server stores FHIR. A healthcare platform needs more than that, and
ends up building the rest by hand — once per application, above a store that
cannot help. DBO is the store that helps.

**It is not a FHIR store, and that is what makes it a good one.** Its concepts
are regulatory rather than clinical — object, identity, envelope, declared
handling, history, custody, tenancy, erasure — and not one of them mentions
medicine. FHIR is a **face** over those concepts: a mapping from one domain's
standards onto them. Configure the FHIR face and you have a FHIR server, ids
and `_history` and `$expand` and all. Configure a different one and the same
engine serves a domain that has never heard of a Patient.

That is also why `dbo-core` has no FHIR in it and `dbo-fhir-common` is a module
of its own: the boundary between them is the claim, made structural. Everything
FHIR knows how to do lives on the far side of it, and R4 and R5 are two faces
sharing a family — not two versions of the store.

- **Isolated by construction.** A tenant is a database, not a filter over a
  shared one. The management plane provisions it without ever seeing its
  credentials, and erasing a tenant is a `DROP DATABASE` rather than a delete
  sweep somebody has to trust.
- **Owned by the tenant, not the operator.** The tenant is its own OIDC
  authority, so even its users' access belongs to it — the store accepts
  nobody else's tokens. Archives are sealed under the owner's key, and under
  personal-data isolation the platform holds no key that opens a person. Not a
  policy the operator promises to honour — a set of things the operator cannot
  do.
- **Process, not just CRUD.** A transactional outbox is the change feed, one
  cursor primitive serves both pagination and synchronization, and durable
  work runs on the database that already holds the data. No broker, no cache
  tier, no second source of truth about what happened.
- **Jurisdiction is configuration.** A zone is a tenant whose declarations are
  records: which identifier systems establish a person, which brokers may
  authenticate one, which terminology is canonical. One identity ceremony
  serves every tenant in the zone.
- **Identifying data under enforced control.** Identifying elements are
  encrypted inside the payload with per-person keys, in the same atomic
  write, so history, feeds, archives and replication carry ciphertext by
  construction. Erasure destroys a key; no earlier archive can undo it.
- **Every type declares what it is.** Append-only, versioned, auditable,
  exportable, retained for how long — declared per type and enforced by the
  engine, not left to the habits of the code that writes it. Audit is
  append-only against everyone, the vendor included.
- **One API, and it is FHIR.** `Person`, `Practitioner`, `Organization` and
  `PractitionerRole` *are* the identity and authorization model: access derives
  from an active `PractitionerRole`, and revoking it is ending a period on an
  ordinary record. Where FHIR has no resource — client applications, signing
  keys, role grants, audit entries — DBO uses a regular versioned record in the
  tenant's own store, not an admin plane or a settings blob. No second
  vocabulary to learn and no second surface to secure.
- **Archives that leave whole.** One sealed archive is backup, restore,
  migration and export: attested by both parties, encrypted under the owner's
  key so the operator cannot read it, and restore-tested by daily use.
- **Version-plural.** R4 and R5 faces run concurrently over one engine —
  different tenants on different versions — and the engine holds no version
  knowledge at all. A domain written under R4 reads as R5 through converters
  rather than a migration. Two faces of one tenant at once, and faces beyond
  the FHIR family, are what
  [founding requirement R6](docs/arc42-001-introduction/founding-requirements.md)
  asks for and the configuration model does not yet allow.
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
| `dbo-fhir-stack` | The HL7/HAPI validation engine, embedded once and exported |
| `dbo-fhir-common` | What every FHIR face shares — a module of its own, not part of core, because the store is not only for FHIR |
| `dbo-fhir-r4`, `dbo-fhir-r5` | Two faces of that family: version meaning and version profiles, over the shared stack |
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

Some modules publish as fat bundles carrying their dependencies inside the
jar; [THIRD-PARTY.md](THIRD-PARTY.md) says whose code that is and under what
terms.
