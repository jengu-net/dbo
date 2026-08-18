# Human identity and authorization (§16)

Humans join the §13 tenant authority without a parallel user database: **the
clinical organization model is the authorization model.** The records the
tenant already keeps — who works here, in what role, in which part of the
organization, since and until when — are read as grants, not mirrored into
a second system that drifts.

## 16.1 The org model is the auth model

- **Practitioner** is the human subject. Their national identifier lives in
  identifying elements — under §14 that is the vault's HMAC index, so
  subject resolution after authentication is an exact-match vault lookup:
  encrypted at rest, shredding-aware, no plaintext registry of people.
- **PractitionerRole is the grant**: this practitioner, at this
  organization, in these role codes, over this period. An access right is
  granted by creating one and revoked by ending its period — a versioned,
  audited, feed-visible clinical record. HR reality and access reality are
  one artifact.
- **Organization.partOf is the scope tree** — the attachment point for
  compartment rules ("own department only") in a later phase; grants carry
  their organization from day one so scoping needs no re-modelling.
- Two small sibling-model records complete the picture:
  **RoleGrant** (role code → SMART scope set — the tenant-administered
  mapping, itself a regular record: auditable, exported, feed-visible) and
  **LocalCredential** (dev/demo password linkage — production
  authentication is federated and stores no secrets for humans).

## 16.2 Federated authentication, local authorization

The authority's human flow is OIDC **authorization code + PKCE**:
`/t/<code>/oidc/authorize` sends the person to the configured upstream
identity broker (the Estonian eeID/TARA reality); the verified national
identifier comes back; the authority resolves the Practitioner via the
vault index, evaluates the ACTIVE PractitionerRoles through the tenant's
RoleGrants, and mints tokens. dbo authenticates nobody in production — it
federates authentication and OWNS authorization. LocalCredential is the
embedded/dev fallback, beside the local provisioner in spirit.

**The identity hub — one national authentication, many tenants.** National
authentication is billed per ceremony and clinicians work across
organizations, so a deployment runs ONE identity hub beside its tenant
authorities. A tenant's `/authorize` redirects to the hub; the hub either
holds a SESSION (a signed cookie carrying only the verified national
identifier and auth time — no names, short TTL, dead on pod restart) and
immediately returns an identity assertion, or it round-trips to the
upstream broker once and then asserts. A zone may declare SEVERAL national
brokers (Estonia: TARA for government and official municipal healthcare,
eeID for the private sector) — the broker set is jurisdictional, the
broker CHOICE is organizational (the tenant declares its broker; the
authentication contract is org-level). The session records which broker
performed the ceremony; a tenant may restrict accepted brokers or
assurance levels, and an unsatisfying session triggers the required
broker's ceremony, accumulating onto the same session — the strictest
tenant is satisfied without invalidating the rest. The zone overlay
(§17) carries the broker declarations as records. The assertion is a short-lived JWT
saying only WHO this is; the receiving tenant authority verifies it
against the hub's keys, resolves the Practitioner through its OWN store,
evaluates its OWN grants, and mints its OWN tokens. Authentication is
shared across the deployment; authorization never is — a doctor with
roles at two clinics authenticates once and works at both, while a tenant
where no active PractitionerRole exists answers access_denied to the same
valid identity. Identifier systems are ZONE configuration (the Estonian
zone takes the official system URIs from the national terminology — the
patient-identifier-domain ValueSet at TEHIK); dbo carries none of them in
code.

## 16.3 SMART shape, pseudonymous tokens

Human tokens speak SMART on FHIR: `fhirUser: Practitioner/<id>`,
`user/<Type>.read|write` scopes (the same grammar §13 gave services), the
same per-tenant issuer and keys. Tokens are **pseudonymous by
construction** — subject is the practitioner's record id; no name, no
national code. A captured token identifies no one; user interfaces fetch
display names through authorized reads. The §15 audit actor becomes the
practitioner pseudonym plus the acting client — exact accountability with
no personal data in the trail.

Embedded appliances (the platform's SMART-launch direction) and the future
open read surface use this same authority and grammar — one trust root per
tenant, every consumer shape.

## 16.4 Acting in the name of a human

Automated processes operate ON BEHALF OF practitioners, never as them and
never as anonymous system accounts:

- **Live delegation** is RFC 8693 token exchange: a service holding the
  user's token exchanges it for a delegated token — `sub` stays the
  practitioner, an `act` claim names the acting client, and the scopes
  attenuate (delegated ⊆ the user's ∩ the requested). The §15 audit actor
  becomes the chain: the client, on behalf of the practitioner pseudonym.
- **Durable delegation** is a record, because workflow steps outlive any
  token: when a human initiates a process, the engine writes a
  **Delegation** (practitioner pseudonym, acting client, process/workflow
  id, attenuated scope set, validity period or until-completion) while the
  user's token is live. Later steps exchange against the RECORD, not the
  expired token. As a regular record it is auditable, feed-visible,
  revocable by ending its period, exported with the tenant — and it is the
  token-side twin of FHIR Provenance's `agent.onBehalfOf`.

A delegated token can never exceed what the human could do, and every
mutation it performs is attributable to both the process and the person.

## 16.5 How a host application fits

The application in front of the store becomes an **OIDC relying party of the
tenant authorities**. It keeps whatever OAuth2-client machinery its framework
gives it — sessions, token storage, login flow — and loses only the
authorization-SERVER role it should never have held. Five practicalities
settle here:

- **The authority surface is published; the store surface is not.** The
  authorization-code flow requires browsers to reach
  `/t/<code>/oidc/*` — and that surface is BUILT for hostile networks:
  discovery, keys, a login redirect, a token endpoint, no clinical data.
  The ingress routes authority paths only (an auth hostname or a
  path-regexp on `/t/+/oidc/`); `/t/<code>/fhir` stays unrouted and
  network-scoped. REQ-DBO-AUTH-PRIVATE-SURFACE is about the store, and it
  stands.
- **Dynamic relying-party registrations.** Framework client registrations are
  usually boot-time property files, but the lookup behind them is normally an
  SPI. A host implements a tenant-code-keyed repository that builds
  registrations on demand from the tenant registry — issuer is the tenant's
  authority URL, credentials come from per-tenant custody. A tenant created
  at 14:00 is loginable at 14:00.
- **Who registers the relying party at each authority:** the operator, with
  the bootstrap-client custody pattern — a confidential `ClientApplication`
  with declared redirect URIs, whose secret lands in a platform-readable
  Secret. The client id travels in that custody too (`DBO_RP_CLIENT_ID` for
  the operator, `dbo.tenant.rp.client.id` for a local runtime, defaulting to
  `dbo-rp`): the serving side ensures the record from what custody says, so
  the Secret and the record cannot disagree about who the relying party is,
  and the store attaches no meaning to the name itself. Third-party
  applications (SMART appliances) get the standard instead: RFC 7591 dynamic
  client registration.
- **Provisioning is REST, same path everywhere.** The tenant-bootstrap
  M2M client writes RoleGrant defaults (sourced from the configuration
  repository) and dev-only LocalCredentials through the authority's
  `admin/role-grants` + `admin/credentials` endpoints — guarded by an
  explicit system-plane write scope (a human's `user/*.write` never
  reaches it). Practitioners and PractitionerRoles ride the ordinary
  FHIR surface with the same token. One provisioning path serves the
  embedded development container and the deployed dbo-server identically.
- **The RP builds its principal from an id_token.** The auth-code exchange
  returns an OIDC id_token beside the access token — audience is the
  CLIENT (the access token's stays the issuer), the RP's `nonce` echoes,
  and `fhirUser` + `roles` ride along, so a standard OIDC client never
  parses the access token at all. Refresh responses carry no id_token;
  the RP already holds its principal. Client authentication at the token
  endpoint accepts both `client_secret_basic` and `client_secret_post`.

Login resolves the org code to the tenant, redirects to that tenant's
`/authorize`, and wraps the returned tokens in the platform session; the
platform's TenantContext derives from token claims as it does today.
Platform administrators authenticate against the SYSTEM tenant's authority —
the host's own system database is a tenant like any other, §13 applied to
ourselves. A host adopting this needs no separate authorization server: the
per-tenant authorities are it, which is also what dissolves the hardest part
of adoption (§7.6 — the tenancy and identity triad).

## 16.6 What stays outside

Compartment/attribute rules beyond role→scopes (the Organization tree is
recorded and waiting); consent/veto participation in token decisions
(the consent track attaches to these seams); the platform's login UI and session
management (the host's, as the relying party); edge PIN auth
unification (the edge caches Practitioners already — grants join later).
