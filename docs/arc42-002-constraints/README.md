# Constraints

- **Java** (current LTS; Java 21+ language level, virtual threads assumed). (R1)
- **No heavyweight application framework** — no Spring Boot, no Micronaut in
  the engine; the runtime container is **OSGi** (Felix as reference
  implementation). Heavy third-party stacks ride as private packages inside
  embedding bundles. (R2)
- **PostgreSQL is the only supported data store**; DBOS is the background
  engine for durable tasks, streams and inter-instance communication — no
  external broker, no Redis-class shared-state service. (R4)
- **Kubernetes** is responsible only for running parallel instances and the
  security layer (network policy, secrets, the provisioning operator);
  tenant-aware routing happens at the application level. (R7)
- **FHIR and published standards over invention** at every external surface.
  Where an existing server solved a problem with a proprietary resource field
  or operation, DBO solves it with a standard mechanism or declares it out of
  scope — it does not reproduce another product's vocabulary.
- **Behaviour is promised in code before it is written**, declared once and
  cited from the test that proves it, with unstated ground named as a gap
  rather than left silent — [promise](promise.md).
- **The rules a green build cannot enforce are stated once and projected**,
  into installable skills and into the guidance read at the start of a
  session, rather than kept as prose in several places —
  [working rules](working-rules.md).
