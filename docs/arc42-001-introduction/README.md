# Introduction and goals

DBO is jengu's own multi-tenant FHIR storage engine.

**Driving problem.** The jengu platform runs on Medplum, which is pinned to
FHIR R4 with no R5/R6 roadmap — while Estonia's national base FHIR is already
R5 and jengu's core strength, device integration, is significantly upgraded in
the R5/R6 model. DBO specifies (and will implement) an "ideal" FHIR storage
for jengu's actual needs.

**Quality goals**

1. **Total tenant isolation** — dedicated database per tenant, credential-blind
   management plane, erasure-by-drop.
2. **FHIR-version plurality** — R4, R5, R6 and siblings concurrently; version
   knowledge lives in replaceable personality bundles.
3. **Top-notch performance** — envelope indexing from day one, single-writer
   tenancy, DBOS-class Postgres techniques.
4. **Embeddability** — the full store boots inside the application JVM for
   dev/test.
5. **Operational honesty** — strict search, generated CapabilityStatements,
   structural audit of every boundary crossing.

The founding requirements (R1–R8, D1–D5) are in
[founding-requirements.md](founding-requirements.md); their distillation into
testable promises is the [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
