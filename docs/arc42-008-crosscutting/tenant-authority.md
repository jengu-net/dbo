# Tenant authority (§13)

The tenant is a **self-containing, self-protecting data unit**. dbo does not
delegate the protection of a tenant to its deployment environment or to an
external identity provider: each tenant carries its own OIDC authority, and
that authority is maintained through regular records in the tenant's own
store.

## 13.1 The trust root is the tenant, not the store

The server whose operational pattern dbo otherwise borrows — identity artifacts
as resources, client_credentials for services — got one thing conceptually
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

## 13.6 What a credential's life is

An authority holds the credential, so it owns what can be done to one. The
surface was written for a credential being *created* and said nothing about one
being changed, forgotten or retired — which meant the answers were whatever the
code happened to do, and a consumer filled the gap by putting reset tickets in a
record store. This is the decision, taken from what an authority ought to do
rather than from what was missing.

**A factor is a kind, and the rules are per kind.** A local credential is not one
thing: it carries factors named by their RFC 8176 `amr` value — `pwd`, `pin`, and
whatever comes next. Asking "may this subject hold a local credential" produced
no clean answer because it is two questions.

- **`pwd` — only where the tenant is the identity provider for that subject.**
  Where sign-in federates, the credential this authority could offer is a second
  way in, weaker than the first and not disableable from the identity provider
  that owns the subject. A person's account is only as strong as its weakest
  door.
- **`pin` — may coexist with federation.** It authenticates at a bench that
  cannot reach a broker, which is the case federation cannot serve, and it is
  scoped to that. This is why the rule is per factor: a rule about "credentials"
  would have had to make the bench an exception.

The `pwd` rule is decided here and **not yet enforced**: refusing one needs a
per-subject signal — a binding to an external identity, rather than the tenant
having federation configured at all — and enforcing it on the coarser signal
would refuse the local credential every dev and bench tenant is provisioned
with. Stated so that the rule is the rule when the signal exists, rather than
being rediscovered then.

**Self-service change is a ceremony; recovery is not.** A signed-in subject
replacing their own `pwd` needs no ticket and no second channel: they prove
possession of the current secret and are already holding a token this authority
issued. That is the smallest useful thing and the only one with a clear answer.

Recovery — a subject who *cannot* sign in — needs a channel the authority does
not have. Acquiring one would put mail delivery inside the trust root, and
ticket machinery is what grows there next. A local credential is **declared, not
accumulated**: it comes from the configuration repository at provisioning, so
recovery is that provisioning running again, or an operator writing one through
the system-write surface. **Recovery is an operator action, deliberately.**

**The ceremony is split from the delivery, and only the delivery was the
problem.** An operator setting a first secret and handing it over is a shared
secret in a channel nobody controls, for every new person — a worse posture than
what it replaces, arrived at as a side effect. So the authority **mints and
redeems a one-time grant** and never sends anything: it learns no address, holds
no template, and retries no delivery. The consumer delivers, because it already
owns the mail and already owns the address. Nothing about delivery enters the
trust root, and what enters is an object with a lifetime and a single use — the
same kind of thing as an authorization code, living beside one.

**Minting looks nothing up.** A mint that resolved the subject would answer
differently, or take differently long, for a login nobody holds. Whether the
subject exists, could hold a `pwd` at all, or was retired this morning is decided
at redemption — in front of the person rather than in front of the caller.

**A grant is burnt on presentation, not on success.** A grant spent only when it
worked is a grant somebody can keep trying: against a weak-secret rule, against a
race, against whatever made the first attempt fail. One use is one attempt.

**A grant is not a credential.** It authenticates nothing, authorises nothing but
its own redemption, and there is no path from it to a token. First-secret and
lost-secret are the same ceremony, differing in who asks rather than in what it
is — two ceremonies would be two things to keep enumeration-safe.

**Deactivating a subject retires its credentials — all factors, at once.** Not
deletes: history and audit need the record, and a login that vanishes cannot be
told from one that was never there. Sign-in already refuses anything but
`active`, so retirement is a state to *set*, and setting it is the operator act
that recovery uses in the other direction.

**No answer distinguishes a subject that exists from one that does not.** The
flow being retired elsewhere carried that property, and it is inherited here
rather than rediscovered: an unknown login and a wrong secret get the same
answer, and take the same time to give it. An authority is the only party that
knows whether a subject exists, which is exactly why it must not say — a
ceremony written naturally (resolve the subject, refuse if absent) is a
regression nothing fails on until somebody enumerates an account list.

**And credential state never leaves.** What crosses the authority's edge about a
factor is a verification, or a hash for a bench to verify offline — never a
secret, and never a store's record of one.
