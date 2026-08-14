# DBO — jengu FHIR object store

DBO is the working name for jengu's own multi-tenant FHIR storage engine.

## Why

The jengu platform currently runs on Medplum as its FHIR store. Medplum has served
well — auth, projects, FHIR API, admin tooling out of the box — but it is pinned to
**FHIR R4** with no roadmap to R5 or R6. Meanwhile:

- Estonia's official national base FHIR version is already **R5**.
- jengu's core strength — **device integration** — is significantly upgraded in the
  R5 and R6 device/observation model.

This repository specifies (and will eventually implement) an "ideal" FHIR storage for
jengu's actual needs: multi-tenant with hard isolation, FHIR-version-plural
(R4/R5/R6 and beyond), PostgreSQL-backed, top-notch performance, and light enough to
boot inside the application JVM in development and test.

## Status

**Specification phase.** No production code on `main` yet.

- [docs/requirements.md](docs/requirements.md) — the main requirements
- [docs/concepts.md](docs/concepts.md) — conceptual solution sketch
- [docs/legacy-concept-inventory.md](docs/legacy-concept-inventory.md) — what the
  previous db-objects codebase got right (and wrong)

## History

An earlier incarnation of this idea ("db-objects", 2024–2025) lives on the
[`legacy`](../../tree/legacy) branch, preserved verbatim. Its durable concepts —
the payload/envelope split, identifier model, transactional outbox, version-driven
leader election — are carried into the new specification; its implementation is not.
