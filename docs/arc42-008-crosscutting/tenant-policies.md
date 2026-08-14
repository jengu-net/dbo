# Tenant policies — audit and write discipline (§15)

A tenant declares its regulatory posture at configuration time, next to its
FHIR version: what is remembered about every action (**audit policy**) and
what may never be unwritten (**write discipline**). Both are engine-enforced
policies, not caller conventions.

## 15.1 Audit policy

Every interaction the policy covers produces an audit record: actor (the
§13 token's client and subject — authority and audit meet here), tenant,
interaction, type, target pseudonym, timestamp, outcome. Levels:

- `none` — no auditing (dev/demo tenants)
- `writes` — every mutation audited
- `full` — reads and searches audited too

Audit records are **regular records in the tenant's own store**:

- they ride the outbox, so subscriptions can watch access patterns;
- they are exported and restored by the maintenance machinery — the
  accountability record travels with the tenant;
- they are **pseudonymous by §14 construction**: an audit trail that named
  people would be a personal-data copy outside the vault. Re-identification
  of an audit line requires the vault, like everything else.

The patient-facing transparency view (who accessed my data — the platform's
portal direction) reads from this stream through the tenant's authority;
dbo provides the queryable source, the platform provides the presentation.

The audit stream is OPEN UPWARD and CLOSED DOWNWARD:

- **Custom events.** Applications contribute business-level events (a report
  released, a consent overridden, a login refused) through an audit-recorder
  surface — engine-level for process engines, `POST AuditEvent` (mapped, not
  stored raw) for FHIR-speaking services. The trust rule: callers contribute
  the WHAT (event code, targets, coded detail); the machinery asserts the
  WHO and WHEN from the validated token and its own clock, overriding
  anything the caller claims. Detail values follow the §14 discipline —
  targets by id, context as codes, never names.
- **Unconditional append-only.** Audit entries are exempt from the tenant's
  chosen write discipline: no update, no tombstone, under any policy.
  Retention's sweep is the only removal.
- **FHIR projection.** On a FHIR tenant the stream is served as read-mostly
  `AuditEvent` — the native records are the truth form, rendered per
  personality on read (the terminology pattern); IHE BALP alignment is the
  projection's follow-up. Non-FHIR tenants read the native stream.

## 15.2 Write discipline

History is immutable by construction (§2); write discipline governs the
STATE surface:

- `standard` — updates and tombstoning deletes as today
- `append-only` — tombstones rejected; correction happens the FHIR way
  (supersede, status `entered-in-error`); optional per-type update
  prohibition for artifacts that must never change in place (signed
  documents, issued reports)

Append-only and the §14 right to erasure coexist deliberately: shredding
never rewrites a record — the record remains, the person evaporates.
Medico-legal retention and GDPR stop being in tension.

## 15.3 Retention — the declarative removal timeframe

Storage limitation (GDPR Art. 5(1)(e)) is the third declared policy: not
whether data may be unwritten, but WHEN it must be. Retention is two-sided:

- `keepAtLeast` — the medico-legal floor: until it passes, the append-only
  discipline holds even against policy;
- `removeAfter` — the ceiling: past it, the engine MUST remove.

The two compose with §15.2 without conflict: append-only rejects CALLER
deletes; retention removal is POLICY execution — a record can be
undeletable for ten years and un-keepable after thirty, both declared,
both enforced.

Removal is the one sanctioned mutation of history: a scheduled sweeper
(durable workflow, checkpointed) removes expired versions from state and
history, and the removal is itself audited — what was removed, when, under
which declared rule — without retaining the data. Outbox rows are already
content-free; the payload disappears with history.

Restores replay policy, symmetric with the §14 shred ledger: before a
restored tenant serves, the machinery re-applies the shred ledger AND the
retention sweep, so an old archive cannot resurrect what the law required
gone. Archives themselves carry a `removeAfter` so the file layer obeys
the same declaration.

## 15.4 Declaration and enforcement

The tenant spec (and its TenantRegistration projection) carries:

```json
{
  "code": "...", "fhirVersion": "r4",
  "audit": { "level": "writes" },
  "writeDiscipline": { "default": "append-only", "perType": { "Task": "standard" } },
  "retention": { "perType": { "Encounter": { "keepAtLeast": "P10Y", "removeAfter": "P30Y" },
                              "AuditEntry": { "removeAfter": "P5Y" } } }
}
```

Validated at registration, enforced by the engine (a rejected tombstone is
a 4xx with an OperationOutcome naming the policy), declared in
`/metadata`. Policy changes are themselves auditable configuration events.

## 15.5 What stays outside

Anonymisation schedules (deriving statistics before removal) are platform
policy riding this machinery; the audit UI/transparency presentation is the
platform's; legal-hold semantics wait for a concrete requirement.
