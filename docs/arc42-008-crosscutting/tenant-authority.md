# Tenant authority (§13)

The tenant is a **self-containing, self-protecting data unit**. dbo does not
delegate the protection of a tenant to its deployment environment or to an
external identity provider: each tenant carries its own OIDC authority, and
that authority is maintained through regular records in the tenant's own
store.

## 13.1 The trust root is the tenant, not the store

Medplum — whose operational pattern dbo otherwise borrows (identity artifacts
as resources, client_credentials for services) — got one thing conceptually
wrong: it is a **server-level** authority. One issuer, one key set, all
projects beneath it. Every relying party that trusts the server transitively
trusts every project it hosts, and project separation degrades to
claim-checking inside a shared trust root.

dbo inverts this. In OIDC the issuer *is* its URL; dbo therefore gives every
tenant its own issuer URL:

```
https://<host>/t/<code>/oidc                    (issuer)
https://<host>/t/<code>/oidc/.well-known/openid-configuration
https://<host>/t/<code>/oidc/.well-known/jwks.json
https://<host>/t/<code>/oidc/token              (client_credentials)
```

A relying party — the platform, a module's process engine, later an external
integration — pins **exactly one tenant's authority**. A token minted by any
other tenant fails at signature verification: wrong issuer, wrong keys,
before any claim is read. Cross-tenant confusion is unrepresentable rather
than filtered. The blast radius of a leaked signing key is one tenant.

Precedent: Keycloak realms and Microsoft Entra tenants both scope the issuer
URL per tenant. The single-authority store is the outlier, not the norm.

## 13.2 Identity artifacts are regular records

Client applications, their grants, and the tenant's signing keys are records
in the tenant's own store — a dbo-native sibling model (the engine is
version-plural and model-plural by founding requirement; identity is a
sibling model, not a FHIR profile bolt-on):

- **ClientApplication** — client_id, hashed secret, granted scopes
  (SMART system grammar: `system/*.read|write`, `system/<Type>.read|write`),
  status. IDENTITY class: client_id.
- **SigningKey** — the tenant's JWK key pairs, kid-versioned for rotation;
  private material encrypted at rest.

Because they are regular records: identity changes flow through the outbox
(subscriptions can watch credential lifecycle), history keeps every version,
provenance applies, sync streams can distribute zone-level identity the way
they distribute terminology, and identity administration itself can be
process-governed. The maintenance export (§11) carries the authority with
the tenant: **restoring a tenant restores who may access it.**

## 13.3 What stays outside

- **Human identity.** dbo issues for services and tenant-scoped clients.
  Login, eeID brokering, sessions, and human-user management remain the
  platform's concern; a person-linkage can bridge later without dbo ever
  doing login UI.
- **The public API.** Per-tenant OIDC does not reopen the raw REST surface —
  the store surface stays private (`REQ-DBO-AUTH-PRIVATE-SURFACE`); the
  authority exists so that *authorized services* can reach the private
  surface with tenant-rooted trust.

## 13.4 Issuer identity vs portability

An issuer URL embeds a hostname, so a tenant that moves deployments would
change identity for its relying parties. dbo treats the issuer string as
**per-tenant configuration** (the same pattern as the REST surface's
baseUrl): the TenantRegistration carries it, dbo serves whatever issuer it
is told it is, and a tenant may present a custom domain. Keys travel in the
database; the name is deployment config.

## 13.5 Consequences for the serving surface

`/t/<code>/fhir` accepts bearer JWTs from `/t/<code>/oidc` and nothing else
— the tenant unit trusts itself. Validation is local (cached JWKS,
refresh-on-unknown-kid); a serving deployment with the authority disabled
and no explicit dev flag refuses to serve (`REQ-DBO-AUTH-DENY-BY-DEFAULT`).
