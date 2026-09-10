# IHE profiles the store should serve

**Status** — analysis only; nothing started. The store names IHE once today,
in the BALP note on the status page, and serves no profiled surface.

**Issues** — none filed yet. Each tier-1 line below is a groomable slice on
its own; the tiers are ordered so the first issue can be opened without
deciding the rest.

**Concepts** — [the FHIR face](../arc42-008-crosscutting/the-fhir-face/README.md),
[finding things](../arc42-008-crosscutting/finding-things/README.md) (terminology),
[who may act](../arc42-008-crosscutting/who-may-act/README.md) (the tenant
authority), [declared rules](../arc42-008-crosscutting/declared-rules/README.md)
(audit), [data isolation](../arc42-008-crosscutting/data-isolation/README.md)
(why some profiles are refused), [running it](../arc42-008-crosscutting/running-it/README.md)
(blob storage), [where a neutral store earns its keep](../plans/neutral-exchange-domains.md).

## What this is

IHE profiles are the shapes an integrator writes against before they have
read anybody's documentation. DBO already serves most of what several of
them ask for — terminology operations, a pseudonymous append-only audit
trail, a SMART authority per tenant, strict search over the organisational
model — but serves it in its own vocabulary, so a client built for a
conformant server has to learn ours first. That is the leak the "no second
API" goal exists to close.

The filter for this document is narrow: **a profile belongs here only if the
store itself is the actor.** A profile whose actor is an application above
the store — an MPI, a consent engine, a device gateway — is named under *Not
doing* with the reason, so it is not proposed twice.

## Where it stands

Nothing built. The analysis below is the whole state. Next is the first
tier-1 slice, SVCM, because its consumer already exists.

## Sequence

| # | step | status |
|---|---|---|
| 1 | **SVCM** — profile `$lookup`, `$validate-code`, `$expand` against Sharing Valuesets, Codes and Maps; add `$translate` over stored `ConceptMap`; add the SVCM search parameters; run the profile's conformance set against a tenant | NEXT |
| 2 | **BALP** — profile the `AuditEvent` projection against Basic Audit Log Patterns: the REST create/read/update/delete/query patterns, the patient and authorisation slices where the entry has a subject | READY, needs 0 |
| 3 | **ATNA over FHIR** — with BALP in place, serve the audit feed (`ITI-20` FHIR option) and the audit query (`ITI-81`) from the existing trail and its outbox; node authentication stays with the deployment | BLOCKED by 2 |
| 4 | **IUA** — add token introspection and the authorization-server metadata document to the tenant authority; check the token claim shape against Internet User Authorization; run the conformance set | READY, needs 0 |
| 5 | **QEDm** — run Query for Existing Data for Mobile against tier-1 search as an audit of strictness; the failures name the tier-2 search items and are the consumer tier 2 has been waiting for | READY, needs 0 |
| 6 | **mCSD** — serve the directory (`Organization`, `Practitioner`, `PractitionerRole`, `HealthcareService`, `Location`, `Endpoint`) with the hierarchy queries; give the zone an `Endpoint` shape for cross-tenant discovery | LATER |
| 7 | **MHD, then DSUBm** — adopt the Document Recipient and Responder as the shape of the blob surface when blob storage opens; document subscriptions fall out of topic subscriptions | BLOCKED by blob storage (the OPS front; owner: this repository) |

Critical path: 1, then 2 and 4 in either order, then 3. Steps 5 to 7 wait for
the front each names.

## Decisions

- **The store is the actor, or the profile is not here.** PDQm, PIXm, PCF
  and the Devices domain all name real work; none of it is the store's. A
  store that served PDQm would undo the identifying-data boundary. Keeping
  the filter strict is what keeps this list short enough to finish.
- **SVCM first, not BALP.** Both have consumers today. SVCM is cheaper —
  three of four operations already exist over the native form — and it
  turns the zone-to-tenant terminology stream into a standard thing. BALP
  is the more important one and has been on the next-fronts list longest;
  it goes second only because its slice is larger.
- **Conformance sets are the tests.** Where a profile ships a conformance
  set, the promise is proven by running it against a tenant in the harness,
  beside the behaviour-named tests. A claim the profile's own tooling has
  not checked is not a claim.
- **Claim what is true, as with the CapabilityStatement.** A profile is
  declared through `rest.resource.profile` and an implementation guide
  reference only once the conformance set passes; the FHIR face document
  already says discovery-before-first-contact is real work rather than a
  list.
- **IUA aligns the authority; it does not replace it.** The tenant remains
  its own issuer. What changes is the introspection endpoint, the metadata
  document and the claim shape, so that a client written for any
  IUA-conformant server works against a tenant unchanged.

## Traps

- **`Patient` is a pseudonym.** Any profile whose conformance set reads a
  name or a national identifier off `Patient` will fail by design. Read the
  failure as the boundary holding, not as a gap.
- **Audit entries carry no identifying data.** BALP's patient slice names a
  subject; here the subject is a record reference and stays one. The profile
  allows that, and the conformance set has to be run in a mode that expects it.
- **Strict search refuses profile parameters it does not know.** Running a
  conformance set before adding a profile's parameters produces a wall of
  400s. That is the ratchet working; add the parameters through the
  personality's own extraction, never by hand.

## Not doing

- **PDQm and PIXm.** Demographics query and identifier cross-reference are
  the application's MPI. The store provides the identifier index it already
  has and nothing that reads back a person.
- **PCF.** Consent-as-access-decision is application policy. Isolation here
  is structural and rules are tenant declarations.
- **MHDS and the XDS family.** A document-sharing infrastructure is a
  product; the neutral-exchange study is where it would be evaluated, as a
  domain rather than a feature.
- **Devices domain (DEC, ACM, PIV, SDPi, MEM, PCIM).** The store's
  contribution is a `Device` projection of trackables and the search a
  consumer needs; the actors belong to the platform's device framework.
  ACM and SDPi are real-time and do not fit a store that is late by design.
- **mACM, NPFSm, sIPS, PMIR.** No actor the store plays.

## Verifying

Nothing to verify yet. Each step above lands with its conformance run in the
harness, and the command goes here with the step.
