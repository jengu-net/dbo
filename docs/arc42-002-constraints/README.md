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
- **FHIR and published standards over invention** at every external surface;
  proprietary mechanics (Medplum-style `Project.link[]`, `$import`) are
  explicitly not reproduced.
