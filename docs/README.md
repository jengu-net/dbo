# DBO documentation

Structured per [arc42](https://arc42.org/), mirroring the jengu-platform
docs conventions.

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
  [object model](arc42-008-crosscutting/object-model.md) (§2–§3),
  [eventing & feeds](arc42-008-crosscutting/eventing-and-feeds.md) (§6, §10),
  [process catalogue](arc42-008-crosscutting/process-catalogue.md) (§8),
  [Medplum lessons](arc42-008-crosscutting/medplum-lessons.md) (§9),
  [maintenance](arc42-008-crosscutting/maintenance.md) (§11),
  [identity rules](arc42-008-crosscutting/identity-rules.md) (§12)
- [arc42-009-architecture-decisions](arc42-009-architecture-decisions/README.md) —
  resolved questions & risks (§7)
- [evidence/](evidence/) — usage inventories grounding the spec
- [plans/](plans/) — [Medplum migration](plans/medplum-migration.md)

**§-numbering note:** the spec grew as one `concepts.md`; its section numbers
(§1–§11) are preserved in the titles above, so cross-references like "(§7.4)"
remain stable. §7.4 = the two-planes/hops decision in arc42-009.
