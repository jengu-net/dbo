# Third-party software

DBO is MIT-licensed. Some of its modules are **fat bundles**: they carry their
dependencies inside the published jar, as whole nested jars under `lib/`,
because an OSGi bundle that keeps a heavy stack private is the only way two
FHIR versions coexist in one JVM (§7.3).

That means a jar published from this repository can contain code this
repository did not write. This page says whose, and under what terms. It is
not a formality — Apache-2.0 §4 asks a redistributor to pass the licence and
the attribution along, and most of these artifacts ship no licence text inside
themselves for it to travel in.

## Embedded in published bundles

| Software | Version | Licence | Rides inside |
|---|---|---|---|
| [HAPI FHIR](https://hapifhir.io) — structures, validation, caching | 8.10.1 | Apache-2.0 | `dbo-fhir-stack` |
| [HAPI FHIR](https://hapifhir.io) — validation resources | 8.10.1 | Apache-2.0 | `dbo-fhir-r4`, `dbo-fhir-r5` |
| [DBOS Transact](https://github.com/dbos-inc) | 1.0.0 | MIT | `dbo-subscriptions` |
| [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client) | 7.3.1 | Apache-2.0 | `dbo-tenant-k8s` |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | 7.1.0 | Apache-2.0 | `dbo-tenant`, `dbo-tenant-k8s` |
| [SLF4J](https://www.slf4j.org) (simple binding) | 2.0.18 | MIT | several bundles |
| [HL7 FHIR R6 core definitions](https://hl7.org/fhir) (`hl7.fhir.r6.core`) | 6.0.0-ballot5 | CC0-1.0 | `dbo-fhir-element` |
| [HL7 Terminology](https://terminology.hl7.org) (`hl7.terminology.r5`) | 7.3.0 | CC0-1.0 | `dbo-fhir-element` |
| [HL7 FHIR Tools extensions](https://hl7.org/fhir/tools) (`hl7.fhir.uv.tools.r5`) | 1.1.2 | CC0-1.0 | `dbo-fhir-element` |

Those last three are **specification content** rather than software: the
StructureDefinitions, ValueSets, CodeSystems and SearchParameters a FHIR
version is defined by, published by HL7 as packages. They ride inside
`dbo-fhir-element` because a store must not fetch its definitions at bring-up
(REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE), and they are pinned by version
and verified by digest at build time so a published bundle says exactly which
ballot it carries.

## Required at runtime, not embedded

| Software | Version | Licence | Where |
|---|---|---|---|
| [PostgreSQL JDBC Driver](https://jdbc.postgresql.org) | 42.7.x | BSD-2-Clause | the serving distribution and the operator |
| [Apache Felix](https://felix.apache.org) | 7.x | Apache-2.0 | the serving distribution's launcher |

## The engine itself carries none of this

`dbo-core` has no dependencies at all, and `dbo-postgres` has only the JDBC
driver. The heavy stacks are confined to the personalities and to the modules
that genuinely need them, which is the same property that lets an R4 tenant
and an R5 tenant share a JVM without either one's HAPI seeing the other's.

## Apache License 2.0

The full text is at <https://www.apache.org/licenses/LICENSE-2.0>. A copy
travels with any distribution built from this repository that embeds
Apache-licensed code, alongside this page.
