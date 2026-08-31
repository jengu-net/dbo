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
  [records you can rely on](arc42-008-crosscutting/records-you-can-rely-on.md)
  (§2–§3, §12 — truth, identity, shape, immutability, rebuild),
  [finding things](arc42-008-crosscutting/finding-things.md) (search and
  terminology — an honest answer or a refusal, never an approximation),
  [change, and who is listening](arc42-008-crosscutting/change-and-who-is-listening.md)
  (§6, §10 — one feed primitive behind paging, subscriptions, dependent copies
  and appliance sync),
  [processes and work](arc42-008-crosscutting/processes-and-work.md) (§8) and
  [work through a FHIR face](arc42-008-crosscutting/work-through-a-fhir-face.md)
  (§8, the same concepts as a reader of that standard sees them),
  [design rationale](arc42-008-crosscutting/design-rationale.md) (§9),
  [maintenance](arc42-008-crosscutting/maintenance.md) (§11),
  [who may act](arc42-008-crosscutting/who-may-act.md) (§13, §16 — the
  tenant as trust root, and how systems and people get in),
  [data isolation](arc42-008-crosscutting/data-isolation.md) (§14, tenant from
  tenant, person from everyone, and what is declared to cross),
  [tenant policies](arc42-008-crosscutting/tenant-policies.md) (§15),
  [zone overlay](arc42-008-crosscutting/zone-overlay.md) (§17),
  [promise](arc42-008-crosscutting/promise.md) (requirements as code — how this
  repository states and proves what it promises)
- [arc42-009-architecture-decisions](arc42-009-architecture-decisions/README.md) —
  resolved questions & risks (§7)
- [evidence/](evidence/) — the measured usage grounding the specification
- [plans/implementation-status.md](plans/implementation-status.md) — **the living
  status page**: what is built, what proves it, what is only specified
- [plans/karaf-console.md](plans/karaf-console.md) — proposal: a Karaf console for
  seeing inside the container (development and operator tooling; not production)

**§-numbering note:** the spec grew as one document; its section numbers
(§1–§17) are preserved in the titles above, so cross-references like "(§7.4)"
remain stable across the split. Every subsection a cross-reference names is a
heading in its own right, so "(§7.4)" and "(§16.2)" are places you can jump to.
