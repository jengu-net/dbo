# Solution strategy

The decisions the store is built on, as they stand. Each is stated here as
fact and elaborated by the concept it belongs to. How a decision was
arrived at is [an architecture decision record](../arc42-009-architecture-decisions/README.md),
and this page is rewritten whenever one of those changes.

1. **A version-agnostic core, with a face per version.** The engine knows
   objects: payload, envelope, identifiers, references. What a FHIR version
   means lives in its own bundle over one shared stack
   ([building blocks](../arc42-005-building-blocks/README.md)).
2. **Payload is truth, the envelope is derived.** The stored bytes are kept
   as they arrived; the searchable projection is computed from them, and
   reindexing is an ordinary operation
   ([records you can rely on](../arc42-008-crosscutting/records-you-can-rely-on/README.md)).
3. **One writer per tenant.** A durable tenant-to-pod assignment makes a
   tenant's caches and subscription state local to the pod that serves it,
   so no shared-state component sits beside the database
   ([deployment](../arc42-007-deployment/README.md)).
4. **Postgres is the only substrate.** Durable work runs in two planes split
   by what its checkpoints contain, and a hop between them is coordinated by
   the platform and audited on both sides
   ([running it](../arc42-008-crosscutting/running-it/README.md)).
5. **One feed primitive.** Keyset cursors serve paging, subscriptions,
   dependent copies, appliance sync and incremental export
   ([change, and who is listening](../arc42-008-crosscutting/change-and-who-is-listening/README.md)).
6. **Provisioning is credential-blind.** The management plane can never read
   a tenant's data or its credentials
   ([tenant provisioning](../arc42-007-deployment/tenant-provisioning.md)).
7. **Identity belongs to the tenant.** A tenant's authority is part of the
   tenant rather than of the application in front of it, so the store can be
   adopted without adopting somebody else's identity model
   ([who may act](../arc42-008-crosscutting/who-may-act/README.md)).
8. **Search is strict and tiered.** What is served is declared, and an
   unrecognised parameter is refused rather than answered more broadly
   ([finding things](../arc42-008-crosscutting/finding-things/README.md)).
