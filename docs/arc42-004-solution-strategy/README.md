# Solution strategy

The bets, each elaborated in its own section. Why the engine is shaped this
way at all — what a mature implementation of the same problem teaches, what an
earlier engine by the same authors got right, and what its post-mortem
forbids — is [design rationale](design-rationale.md).

1. **Version-agnostic core + personality bundles** — the engine knows objects
   (payload, envelope, identifiers, references); FHIR-version meaning lives in
   per-version OSGi bundles over one shared HAPI stack
   ([§1](../arc42-005-building-blocks/README.md), §7.3).
2. **Payload/envelope split** — opaque payload as truth, derived searchable
   projection, reindex as an operation
   ([§2–§3](../arc42-008-crosscutting/records-you-can-rely-on.md)).
3. **Single-writer tenancy** — durable tenant→pod assignment makes caches and
   subscription state local; Redis-class shared state is designed away
   ([§5](../arc42-007-deployment/README.md),
   [§9](design-rationale.md)).
4. **DBOS/Postgres as the only substrate** — durable work in two planes split
   by content; cross-boundary hops platform-coordinated and audited (§7.4).
5. **One feed primitive** — keyset cursors underneath pagination,
   subscriptions, content streams, edge sync and incremental export
   ([§6, §10](../arc42-008-crosscutting/change-and-who-is-listening.md)).
6. **Credential-blind provisioning** — operator + secrets; the management
   plane can never read tenant data or credentials
   ([§4](../arc42-007-deployment/tenant-provisioning.md)).
7. **Identity before storage** — a tenant's authority is part of the tenant,
   not of the application in front of it, so a store can be adopted without
   first adopting somebody else's identity model
   ([§13](../arc42-008-crosscutting/who-may-act.md)).
