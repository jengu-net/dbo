# Changelog

Notable changes per release. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/), with the pre-1.0 caveat that the
API is not yet frozen.

## [Unreleased]

The first public release. Everything below is the state at that point rather
than a list of changes from something earlier.

### Present

- **Engine** — object store over PostgreSQL: single-transaction writes,
  envelope indexing, history, transactional outbox, version chaining,
  upgrade-on-read through payload converters.
- **FHIR** — R4 and R5 personalities running concurrently over one engine,
  over one shared HAPI stack; tier-1 search; generated CapabilityStatement;
  terminology in a concept-per-row native form.
- **Tenancy** — a database per tenant, credential-blind provisioning, dynamic
  per-tenant service sets, erasure by drop, and a Kubernetes operator
  reconciling `TenantRegistration` resources.
- **Identity** — every tenant its own OIDC authority; SMART-shaped scopes;
  authorization-code with PKCE; RFC 8693 token exchange; delegation records;
  federation to a national broker with one ceremony serving every tenant in a
  zone.
- **Personal-data isolation** — identifying elements encrypted inside the
  payload, erasure by key destruction, no plaintext anywhere the engine
  reaches.
- **Policy** — append-only audit as ordinary records, declarative retention,
  posture declared in the capability statement.
- **Maintenance** — one sealed, attested archive serving backup, restore,
  export and import.
- **Operations** — one logging binding for the runtime, JSON by default at
  INFO, framework events included.

### Specified, not built

Durable work planes (WF), routing (SCAL) and operations (OPS). The process
catalogue and distributed work (PROC) are largely built; see
[the status page](docs/plans/implementation-status.md).
