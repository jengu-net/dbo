# DBO documentation

Structured per [arc42](https://arc42.org/).

- [arc42-001-introduction](arc42-001-introduction/README.md) — goals, quality
  goals, [founding requirements](arc42-001-introduction/founding-requirements.md)
- [arc42-002-constraints](arc42-002-constraints/README.md)
- [arc42-003-context](arc42-003-context/README.md)
- [arc42-004-solution-strategy](arc42-004-solution-strategy/README.md)
- [arc42-005-building-blocks](arc42-005-building-blocks/README.md) — layering (§1)
- [arc42-006-runtime](arc42-006-runtime/README.md) — [REQ catalogue](arc42-006-runtime/req-catalogue.md)
- [arc42-007-deployment](arc42-007-deployment/README.md) — scaling/routing (§5),
  [tenant provisioning](arc42-007-deployment/tenant-provisioning.md) (§4)
- [arc42-008-crosscutting](arc42-008-crosscutting/) —
  [engine and faces](arc42-008-crosscutting/engine-and-faces.md) (§1),
  [object model](arc42-008-crosscutting/object-model.md) (§2–§3),
  [eventing & feeds](arc42-008-crosscutting/eventing-and-feeds.md) (§6, §10),
  [process catalogue](arc42-008-crosscutting/process-catalogue.md) (§8),
  [design rationale](arc42-008-crosscutting/design-rationale.md) (§9),
  [maintenance](arc42-008-crosscutting/maintenance.md) (§11),
  [identity rules](arc42-008-crosscutting/identity-rules.md) (§12),
  [tenant authority](arc42-008-crosscutting/tenant-authority.md) (§13),
  [personal-data isolation](arc42-008-crosscutting/personal-data-isolation.md) (§14),
  [tenant policies](arc42-008-crosscutting/tenant-policies.md) (§15),
  [human identity](arc42-008-crosscutting/human-identity.md) (§16),
  [zone overlay](arc42-008-crosscutting/zone-overlay.md) (§17)
- [arc42-009-architecture-decisions](arc42-009-architecture-decisions/README.md) —
  resolved questions & risks (§7)
- [evidence/](evidence/) — the measured usage grounding the specification
- [plans/implementation-status.md](plans/implementation-status.md) — **the living
  status page**: what is built, what proves it, what is only specified

**§-numbering note:** the spec grew as one document; its section numbers
(§1–§17) are preserved in the titles above, so cross-references like "(§7.4)"
remain stable across the split. Every subsection a cross-reference names is a
heading in its own right, so "(§7.4)" and "(§16.2)" are places you can jump to.
