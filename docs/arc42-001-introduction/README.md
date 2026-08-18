# Introduction and goals

DBO is a multi-tenant FHIR storage engine: a store a healthcare application
platform runs its tenants on, embeddable in the application's own JVM for
development and test, and deployable as a serving distribution in production.

**Driving problem.** The FHIR servers available to build on are pinned to
FHIR R4. That was a reasonable place to stop when R4 was where the standard
lived; it is not where it lives now. Several national base specifications are
already R5, and the device and observation model — the part that matters most
to anyone integrating clinical instruments — is substantially better in R5 and
R6 than in R4. A store whose version is a property of the deployment rather
than of the product is the wrong shape for the next decade. DBO is the store
that makes the FHIR version a per-tenant, per-domain choice.

**Quality goals**

1. **Total tenant isolation** — a dedicated database per tenant, a
   credential-blind management plane, and erasure that is a `DROP DATABASE`
   rather than a delete sweep.
2. **FHIR-version plurality** — R4, R5, R6 and siblings concurrently; version
   knowledge lives in replaceable personality bundles, and the engine holds
   none of it.
3. **Performance as a first-class property** — envelope indexing from day
   one, single-writer tenancy, Postgres-native techniques rather than a cache
   tier bolted on later.
4. **Embeddability** — the whole store boots inside the host application's
   JVM, so development and test run against the real engine rather than a
   substitute.
5. **Operational honesty** — strict search, generated CapabilityStatements
   that describe only what is true, and a structural audit of every boundary
   crossing.

The founding requirements (R1–R8, D1–D5) are in
[founding-requirements.md](founding-requirements.md); their distillation into
testable promises is the [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
