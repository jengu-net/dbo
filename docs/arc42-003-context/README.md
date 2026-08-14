# Context and scope

DBO is a storage engine consumed by the jengu platform; it is not a
user-facing product.

| Neighbour | Relationship |
|---|---|
| **jengu cloud / edge assemblies** | Primary consumers — via the FHIR REST surface in production, via the in-JVM embedded container in dev/test |
| **Tenants** (healthcare providers, insurers, zone tenants) | Each is a provisioned store: dedicated database, OSGi service set, optional blob bucket |
| **Zone tenants** | Publish canonical content (terminology, profiles) streamed to dependent tenants as declared, read-only copies |
| **Kubernetes operator** | Provisions databases/buckets and secrets credential-blind; DBO code never sees credentials |
| **git config repos** | Source of truth for configuration (dependency declarations, catalogue content) — flows one direction into the store |
| **External FHIR clients** | Standard FHIR API incl. topic-based Subscriptions, per the generated CapabilityStatement |
| **Monitoring/tracing stack** | Receives OpenTelemetry spans carrying process/step codes; process map queryable |

Out of scope: end-user UI, identity provider (jengu's auth plane), clinical
logic (lives in jengu modules / process definitions).
