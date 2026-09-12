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

## Face images — an operator-held artefact, beside secrets

A tenant coming up on a face reads the whole of what that face publishes and
expands it: about half a minute whose answer is identical for every tenant on
that face. Since the definitions are a schema of their own, the answer can be
cut once and handed over as bytes, and a tenant loads it instead.

- **Where they live is the operator's**, named by `dbo.face.images` and mounted
  the way a secret is. A deployment that names no directory behaves exactly as
  it did before there were images. The store cuts them and accepts them; it
  never decides where they are kept or for how long.
- **They are cut on demand, not on a schedule.** The first tenant that wants a
  face it has no image of cuts one; a node serving one face never cuts the
  others, and an edge node that wants none takes none. One cutting at a time
  is kept to one by a lock on the directory, so tenants coming up together
  wait seconds rather than each reading the whole face.
- **An image is checked before it is loaded, and refused by name.** It carries
  the release's packages, the face, the shape its rows were expanded into and
  the fingerprint of the SQL that reads them; any disagreement means the tenant
  comes up the way tenants came up before there were images. An image whose
  release has moved on is cut again rather than quietly ignored forever.
- **There is nothing to hide in one.** A face is a public specification plus
  what a zone declares, and carries no person — which is why it travels as
  plain bytes where a tenant's archive is sealed to the owner's key.

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

