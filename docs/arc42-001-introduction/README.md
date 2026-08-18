# Introduction and goals

DBO is a multi-tenant FHIR storage engine: a store a healthcare application
platform runs its tenants on, embeddable in the application's own JVM for
development and test, and deployable as a serving distribution in production.

## The driving problem

A FHIR server stores FHIR. That is less than a healthcare platform needs, and
the gap is not one missing feature but a list of them — each one otherwise
reimplemented by hand, once per application, above a store that cannot help.

**Tenancy is a filter, not a boundary.** The available servers put every
tenant in one database, reachable through one master credential, by design.
Isolation is then something the application layer promises and the storage
layer cannot enforce. A regulator asking "can this data be reached from that
tenant's context" gets an answer about code review rather than about
structure. In DBO a tenant *is* a database, the management plane provisions it
without ever seeing its credentials, and erasing a tenant is a `DROP DATABASE`
rather than a delete sweep somebody has to trust.

**The operator runs the box; the owner owns it.** In a shared server the
platform holds the master credential, issues the tokens, and can read anything
it hosts — so a tenant's data is protected by the platform's good behaviour.
DBO inverts that. The management plane provisions a tenant's database without
ever seeing its credentials. The tenant is its own OIDC authority, so *user
access is the tenant's too*: it issues the tokens its people log in with, and
the store accepts nobody else's — a cross-tenant token dies at signature
verification rather than at a permission check. Archives are sealed under the
owner's key, so the operator runs backups it cannot read and a restore
structurally requires the owner's participation. Under personal-data isolation
the platform holds no key that opens a person. Audit is append-only against
everyone, the vendor included. None of that is a policy the operator promises
to honour; it is what the operator is unable to do.

**Storage is CRUD; work is process.** A FHIR server records the state a
process left behind. It has nothing to say about the process itself — what
step ran, what it was allowed to do, what it caused next. Applications
therefore bolt on a queue, a worker tier and a cache to get durable work and
change propagation, and the store stays a passive record of results. DBO
treats eventing as part of storage: a transactional outbox is the change
feed, one cursor primitive serves pagination and synchronization alike, and
durable work runs on the same database that holds the data — no broker, no
cache tier, no second source of truth about what has happened.

**Care happens inside a jurisdiction.** Which identifier systems establish a
person, which brokers may authenticate one, which national terminology is
canonical — these are properties of a country, not of a deployment, and
hard-coding them into an application is how a product becomes unexportable.
DBO makes jurisdiction a first-class layer: a zone is a tenant whose
declarations are records, its brokers and identifier domains are configuration
and terminology rather than code, and one identity ceremony serves every
tenant in the zone.

**Identifying data needs a boundary the store enforces.** In practice
platforms separate identity from clinical content by discipline — this module
never stores a name, that runtime never sees a national code — and discipline
is exactly what fails under maintenance. DBO encrypts identifying elements
inside the payload with per-person keys, in the same atomic write, so history,
feeds, archives and replication carry ciphertext by construction rather than
by remembering to. Erasure destroys a key, and no earlier archive can
resurrect the person.

**What a record *is* should be declared, not conventional.** Whether a type is
append-only, whether it accumulates history, whether it may travel in an
export, how long it may be kept, whether it is auditable — these are
properties of the data, and in most systems they live in the habits of the
code that writes it. DBO makes every registered type declare its handling, and
the engine refuses writes that contradict the declaration. Audit is
append-only against everyone, the vendor included, because that is what the
declaration says and not because no code path happens to delete it.

**There is no second API.** Configuration, identity, authentication and
authorization are usually a product's proprietary corner: an admin database, a
settings array on some resource, a vendor's own vocabulary for who a user is.
In DBO they are FHIR. `Person`, `Patient`, `Practitioner`, `Organization` and
`PractitionerRole` *are* the identity and authorization model — a human's
access derives from an active `PractitionerRole`, and revoking it is ending a
period on an ordinary clinical record, over the ordinary API, with the ordinary
audit trail. Where FHIR has no resource for something — a client application,
a signing key, a role grant, an audit entry — DBO uses a regular versioned
record in the tenant's own store rather than a side table: it rides the change
feed, it leaves in the archive, and it is read the same way as everything else.
The audit trail is served back as `AuditEvent`. One surface, one vocabulary,
one thing to secure.

The deliberate exception is the maintenance endpoint. Taking or restoring an
archive is not a FHIR interaction, and it carries the owner's key on the
request and never keeps it — an endpoint that held the key on the tenant's
behalf would quietly turn a structural property into a promise.

**A tenant's data must be able to leave whole.** Backup, restore, migration
between installations and lawful export are the same operation, and a store
that treats them as an operator's problem produces archives nobody has ever
restored. DBO makes one sealed archive the mechanism: attested by both
parties, encrypted under the owner's key so the operator cannot read it,
carrying either a portable representation or a byte-faithful one — and every
backup is implicitly restore-tested, because the import path is in daily use.

**And the version is a per-tenant choice.** The available servers are pinned
to FHIR R4. Several national base specifications are already R5, and the
device and observation model — the part that matters most to anyone
integrating clinical instruments — is substantially better in R5 and R6. A
store whose FHIR version is a property of the deployment is the wrong shape
for the next decade.

## Quality goals

1. **Total tenant isolation** — a dedicated database per tenant, a
   credential-blind management plane, and erasure by drop.
2. **The tenant owns the box** — its own authority issuing its own users'
   tokens, archives the operator cannot read, and no key held on the owner's
   behalf.
3. **Process as a storage concern** — durable work, subscriptions and change
   feeds on the same substrate as the data, with no broker and no cache tier.
4. **Declared handling** — every type states what kind of data it is, and the
   engine enforces it rather than trusting its callers.
5. **One API** — configuration, identity and authorization are standard FHIR
   resources on the same surface as clinical data; no admin plane, no second
   vocabulary.
6. **Jurisdiction as configuration** — zones, brokers and identifier systems
   are records and terminology, never code.
7. **Personal data under structural control** — identifying elements
   encrypted in place, erasure by key destruction, no plaintext anywhere the
   engine can reach.
8. **Portability** — one sealed, attested archive is backup, restore,
   migration and export.
9. **FHIR-version plurality** — R4, R5, R6 and siblings concurrently; version
   knowledge lives in replaceable personality bundles and the engine holds
   none of it.
10. **Performance as a first-class property** — envelope indexing from day
    one, single-writer tenancy, Postgres-native techniques rather than a cache
    tier bolted on later.
11. **Embeddability** — the whole store boots inside the host application's
    JVM, so development and test run against the real engine.
12. **Operational honesty** — strict search, generated CapabilityStatements
    that describe only what is true, and a structural audit of every boundary
    crossing.

The founding requirements (R1–R8, D1–D5) are in
[founding-requirements.md](founding-requirements.md); their distillation into
testable promises is the [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
