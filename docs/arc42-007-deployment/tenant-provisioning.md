# Tenant lifecycle — credential-blind provisioning (§4)

```
tenant manager ──(TenantRegistration CR)──▶ k8s operator
                                              │ creates DB + role,
                                              │ writes k8s Secret
                                              ▼
serving pod ◀──(secret mount / projected volume)
  │ dbo-tenant-provisioner bundle watches mounts,
  │ builds HikariCP pool, registers services:
  │   DataSource        (service.props: tenant=<id>)
  │   ObjectStore       (tenant=<id>, personality=r5)
  │   SubscriptionFeed  (tenant=<id>)
  ▼
application code: registry lookup by tenant id — uses the pool,
                  never sees credentials
```

- The tenant manager knows *that* a tenant exists and *where* it is served — never
  its credentials.
- Tenant SSO follows the same pattern: per-tenant IdP config delivered as secret,
  materialized as a per-tenant OSGi service.
- De-provisioning = retracting the service set + operator-driven teardown; the
  registry dynamics give in-flight callers a clean "tenant unavailable" instead of
  broken pools.

