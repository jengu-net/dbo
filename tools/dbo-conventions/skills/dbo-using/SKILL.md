---
name: dbo-using
description: Building an application on top of the DBO store, or reviewing one that is. Triggers when work would add persistence, a user or role model, an audit log, a job queue, encryption of personal data, an export or import path, terminology lookup, validation of a payload, or a change-notification mechanism — any of which the store may already provide, and providing it twice is two answers that drift.
---

# dbo-using

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Building an application on top of the DBO store, or reviewing one that is. Triggers when work would add persistence, a user or role model, an audit log, a job queue, encryption of personal data, an export or import path, terminology lookup, validation of a payload, or a change-notification mechanism — any of which the store may already provide, and providing it twice is two answers that drift.

## Reference

- **A tenant is a database.** Declare a spec; the runtime provisions and
  serves it at `/t/<code>/fhir`, with its own authority at `/t/<code>/oidc`.
  There is no cross-tenant surface, so there is no tenant predicate to add to
  a query and none to forget.
- **Authority comes from the tenant's own records.** An active role record is
  the grant; revoking is ending a period on it. Do not build a second user
  directory — the drift is discovered when somebody who left still has access.
- **Access is granted to a step, not to a person.** Work is processes, steps
  and runs; performing a step is what records why data was reached. A job
  table with a worker loop is this, rebuilt without the proof.
- **Nobody is pushed.** Participants pull work over a lane and report back, so
  a participant behind a firewall needs no inbound hole.
- **One feed primitive** serves paging, subscription, replication and
  catch-up. A consumer is a name and a position; change events are committed
  with the write. Do not add a notification table or a broker.
- **Records keep every version**, linked, so an edited history fails
  verification. Payloads carry a version and convert on read — not by
  migration script.
- **Identity is enforced at the write.** A type declares what identifies it;
  a second claimant is refused rather than merged.
- **Search is declared and strict.** Tier one only: `:exact`, `:missing`,
  `:not`, `:identifier`, `system|code`, one-level chains, `_lastUpdated`,
  `_summary=count`, `_elements`, `_include`, conditional create. An
  unrecognised parameter is refused, never answered more broadly.
- **Validation comes from records.** Structures, profiles, value sets and
  search parameters stream to the tenant over its face and are expanded into
  rows. Write a profile rather than field checks in application code.
- **Terminology is rows.** `$lookup`, `$validate-code`, `$expand`. A code from
  a system the tenant does not hold is unresolvable, which is not invalid.
- **Personal data is encrypted inside the payload** with the person's own key
  when a tenant sets `pdi`. History, feeds, archives and replication carry
  ciphertext by construction. Do not add field-level encryption above it.
- **Erasure destroys the key**, so it reaches copies nobody can recall, and is
  replayed on restore. Do not write a cascade delete.
- **The trail is records**, append-only against everyone. Contribute what
  happened; who and when are stamped from the credential. Do not write an
  audit table.
- **A jurisdiction is a tenant.** Brokers, identifier systems and terminology
  come from a zone over the ordinary chain, not from a config table or a
  release.
- **Configuration is applied as a run** that accounts for what it read,
  applied and skipped, per scope — so a set is exercised in one place before
  another.
- **Backup is export and restore is import**, one sealed archive, verifiable
  without trusting either party.
- **MUST check the requirement catalogue before relying on a capability**:
  `docs/arc42-006-runtime/req-catalogue.md` is generated and a promise reads
  PROVEN only when a test cites it. Subscription delivery, tier-2 search,
  blob storage, cross-node routing and a shared-schema tier are not built.

---

Where this is stated and argued: [`docs/using-dbo.md`](../../../../docs/using-dbo.md)
