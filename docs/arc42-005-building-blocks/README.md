# Building blocks — layering (§1)

```
┌───────────────────────────────────────────────────────────────┐
│ OSGi container (Felix)                                        │
│                                                               │
│  ┌──────────────┐  ┌───────────────┐  ┌────────────────────┐  │
│  │ dbo-core     │  │ dbo-fhir-r4   │  │ dbo-fhir-r5 / r6…  │  │
│  │ engine (no   │  │ version       │  │ (parsing, profile  │  │
│  │ FHIR, no     │  │ personality   │  │ validation, search │  │
│  │ framework)   │  │ bundle        │  │ param extraction)  │  │
│  └──────┬───────┘  └───────┬───────┘  └─────────┬──────────┘  │
│         │                  └──────────┬─────────┘             │
│  ┌──────┴──────────┐  ┌───────────────┴──────┐               │
│  │ dbo-postgres    │  │ per-tenant service    │               │
│  │ (JDBC, virtual  │  │ sets (DataSource,     │               │
│  │ threads, DBOS)  │  │ storages, SSO client) │               │
│  └─────────────────┘  └──────────────────────┘                │
└───────────────────────────────────────────────────────────────┘
```

- **dbo-core** — the version-agnostic object engine: envelope model, identifiers,
  references, outbox, search criteria SPI. Plain Java, zero framework, zero FHIR.
- **Version personality bundles** (`dbo-fhir-r4`, `-r5`, `-r6`, and non-FHIR
  siblings) — everything that knows what the payload *means*: resource parsing,
  validation, SearchParameter → envelope extraction, Subscription topic evaluation.
  A tenant/domain binds to one personality; several personalities coexist in one
  container.
- **dbo-postgres** — JDBC on virtual threads (no reactive driver), DBOS-style SQL,
  Liquibase with advisory session locks.
- **Per-tenant service sets** — registered/retracted dynamically in the OSGi
  service registry as tenants arrive, move, or leave (see §4).

Embedded mode: a host application — the platform's own assemblies in
development and test, end-to-end harnesses — starts Felix in-JVM, installs the same bundles, and talks to DBO through its Java
API — the only shared dependencies are Felix and the OSGi API.

