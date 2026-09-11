# Tenant lifecycle — credential-blind provisioning

```
tenant manager ──(TenantRegistration CR)──▶ k8s operator
                                              │ creates DB + role,
                                              │ writes k8s Secret
                                              ▼
serving pod ◀──(secret mount / projected volume)
  │ dbo-tenant-provisioner bundle watches mounts,
  │ builds HikariCP pool, registers services:
  │   ObjectStore       (service.props: tenant=<code>, fhir.version=<face>)
  │   FhirStoreFacade   (tenant=<code>, fhir.version=<face>)
  │   ChangeFeed        (tenant=<code>, fhir.version=<face>)
  │   Lanes             (tenant=<code>, fhir.version=<face>)
  ▼
application code: registry lookup by tenant id — uses the pool,
                  never sees credentials
```

- The tenant manager knows *that* a tenant exists and *where* it is served — never
  its credentials.
- Tenant SSO follows the same pattern: per-tenant IdP config delivered as secret,
  materialized as a per-tenant OSGi service.
- The set is what a participant in the framework needs, and the replication lane
  is in it for the same reason the store is: the bundle that carries a second
  site's bytes runs *here*. Reaching the lane's HTTP door instead would mean a
  loopback hop and a tenant credential in configuration to re-enter a process it
  never left, and building its own would give one peer two sets of cursors.
- De-provisioning = retracting the service set + operator-driven teardown; the
  registry dynamics give in-flight callers a clean "tenant unavailable" instead of
  broken pools.

