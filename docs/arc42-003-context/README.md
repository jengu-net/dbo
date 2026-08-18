# Context and scope

DBO is a storage engine consumed by an application platform. It is not a
user-facing product, and nothing in it knows what the application above it is
for.

| Neighbour | Relationship |
|---|---|
| **The host platform** (cloud and edge assemblies) | Primary consumer — over the FHIR REST surface in production, and through the in-JVM embedded container in development and test |
| **Tenants** (healthcare providers, insurers, zone tenants) | Each is a provisioned store: a dedicated database, an OSGi service set, an optional blob bucket |
| **Zone tenants** | Publish canonical content — terminology, profiles — streamed to dependent tenants as declared, read-only copies |
| **The Kubernetes operator** | Provisions databases, buckets and credentials without any DBO code ever seeing a credential |
| **Configuration repositories** | Source of truth for configuration — dependency declarations, catalogue content — flowing one direction into the store |
| **External FHIR clients** | The standard FHIR API including topic-based Subscriptions, per the generated CapabilityStatement |
| **Monitoring and tracing** | Receives OpenTelemetry spans carrying process and step codes; the process map is queryable |

Out of scope: any end-user interface, the host platform's own identity plane
for its staff and administrators, and clinical logic — which belongs in the
application's modules and process definitions, not in a store.
