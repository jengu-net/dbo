# US-DBO-TENANT-OPENING — a tenant is stood up inside somebody else's JVM, and the people who will work in it get in

> Ines builds the platform a clinic group runs on. She is one of four
> engineers. She does not run a database team, she does not want to
> operate a second identity system, and she is not going to write a
> tenant boundary of her own — the last one she reviewed was a `WHERE
> tenant_id = ?` that somebody had forgotten in two places.
>
> This is her first hour with the store. It starts inside the JVM she
> already ships. A clinic becomes a tenant by dropping a declaration
> where the store is watching, and what it gets is a database of its own
> and an authority of its own, neither of which Ines had to provision by
> hand. Her identity provider fills the staff directory over the protocol
> it already speaks. And what a clinician may reach is a grant she can
> read back, rather than a code path she has to trust.

## The scene

Kevadkliinik is the first clinic to go live. Sügiskliinik follows a week
later, on the same running store, which is the part Ines is actually testing:
the second one must cost a declaration, not a deployment.

She has:

- **her own application JVM**, into which the store is embedded as a library;
- **an identity provider** that already holds every clinician's record and
  speaks SCIM;
- **no database administrator**, so provisioning has to be somebody else's
  problem in a way that does not put a credential in her code.

## A declaration is the whole of opening a clinic

The store is already running and serving nobody. Ines writes the clinic's
declaration — its code, its FHIR version, the types it holds, and the
namespace her identity provider stamps staff numbers in — and the tenant's
services appear on the next scan.

Nothing else happens. There is no migration to run, no schema to apply, no
restart. The declaration is the act.

What the clinic gets underneath is a database of its own rather than a slice
of a shared one, and the credential that made it never passed through any
code Ines wrote or the store reads: the provisioning seam hands back a
connection and a bootstrap secret it generated itself. In her deployment that
seam is a Kubernetes operator. In her tests it is a local provisioner. The
store cannot tell, which is the point of it being a seam.

## Nothing is reachable until the clinic's own authority says so

Ines checks the obvious thing first: an anonymous request to the clinic's
surface. It is refused rather than missing — a 401 and not a 404, because a
mounted and guarded surface and an absent one are different facts, and only
one of them means she has misconfigured something.

The credential she then mints comes from **the clinic's own issuer**. It is
validated where it is presented, with no call out to anybody, and its scope
is the whole of what it reaches: granted `Patient.read`, it reads patients
and is refused practitioners.

## A second clinic, and what a tenant boundary means

Sügiskliinik opens the same way, from the same running store. Ines then does
the test she came for and presents Kevadkliinik's token to Sügiskliinik.

It comes back **401, not 403**. That distinction is the whole design: the
second clinic's authority has never heard of that issuer, so the token is not
a valid credential being refused, it is noise. A tenant here is a store, not
a filter over a shared one, and the boundary is structural rather than a
predicate somebody has to remember to write.

## Her identity provider fills the staff directory

Ines points her identity provider at the clinic's SCIM endpoint with a
credential that carries the directory scope and nothing else.

A clinician arrives as a `User`, and what lands in the store is the **person**
— because a person is who somebody is, and a practitioner is a capacity they
act in, ensured and linked on create so that the grant machinery works with
nothing further. The enumeration the directory needs to list its users stays
behind that door: the same credential reaching for the clinical surface is
refused. Groups read and never write, because who works here is the identity
provider's to say and who is an admin here is not.

The store refuses one combination by name, and Ines meets it immediately: a
tenant declaring SCIM without personal-data isolation will not come up. A
directory that writes people into a tenant outside the membrane would be a
hole in §14 opened by configuration, so it is not expressible.

## What a clinician may do is declared

The clinician's reach is a role grant, stated as configuration and stored as a
record. Declaring it twice is the ordinary case rather than an error, because
configuration arrives from wherever the clinic keeps it and a bring-up that
refused a grant it already had would make every redeploy a migration.

The human it attaches to is an ordinary record in the clinic's own store,
carried by its backup and dropped when the tenant is.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-CONT-EMBEDDED-IN-JVM` | A host application can boot the full store inside its own JVM for dev/test; the only shared dependencies are Felix and the OSGi API. (R2) | PROVEN |
| `REQ-DBO-CONT-FRAMEWORK-FREE-CORE` | The core is plain Java; no Spring/Micronaut-class framework dependency anywhere in the engine. (R1, R2) | PROVEN |
| `REQ-DBO-CONT-PRIVATE-DEPENDENCIES` | Heavy third-party stacks (DBOS, HAPI) are embedded as private packages and served through DBO-owned whiteboard interfaces; their types never cross bundle boundaries. | PROVEN |
| `REQ-DBO-CONT-IMPORTS-ARE-COMPUTED-OR-CHECKED` | Every bundle with source of its own computes its imports from its bytecode; what is written by hand is policy — which JDK surfaces may be absent, and what a private stack reaches for that the container does not provide — never an inventory a new reference can drift from. The one bundle without source, the shared HL7 stack, keeps a closed hand-written list and is checked for it: every class it embeds is walked, and a framework-wired package it reaches for and neither carries nor imports fails the build rather than the first use. | PROVEN |
| `REQ-DBO-CONT-DYNAMIC-TENANT-SERVICES` | Tenants arrive, move and leave as OSGi service-registry dynamics — never a process restart. (R2, §4) | PROVEN |
| `REQ-DBO-CONT-FAST-COLD-START` | Store startup against an already-current schema is fast enough for embedded test use; schema setup detects currency instead of replaying changelogs. | PROVEN |
| `REQ-DBO-TEN-DEDICATED-DATABASE-TIER` | A tenant can run on a dedicated database; this tier is the design anchor. (R5) | PROVEN |
| `REQ-DBO-TEN-CREDENTIAL-BLIND-PROVISIONING` | Tenant databases and buckets are provisioned by an external operator; credentials exist only as platform secrets and are never readable by tenant-manager code. (R5, §4) | PROVEN |
| `REQ-DBO-TEN-STRUCTURAL-SCOPING` | No code path can read or write data without an explicit tenant context. (R3) | PROVEN |
| `REQ-DBO-TEN-READY-WHEN-ITS-CRITICAL-DEFINITIONS-ARRIVED` | A tenant on a face is served only once the version's structures, search parameters, value sets and code systems have arrived from it — the four a version is made of — and a face root declares all four or is refused. A chain that carried structures without the code systems their bindings name would serve a tenant that accepts any code at all. | PROVEN |
| `REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS` | Application code obtains a tenant's data services from the service registry and can use them without ever seeing credentials. (R5, §4) | PROVEN |
| `REQ-DBO-TEN-A-PARTNER-MANAGES-TENANTS` | A partner is a tenant that manages other tenants, declared when the managed tenant is created. The relation says which tenants the partner may read at all; within each, the partner is a declared audience saying what of each — runs and their journey, never documents, purposes only if the managed tenant opts in. What the partner is shown is assembled outside the store: a store instance is one tenant's store, and no cross-tenant query is grown to serve a support desk. | PROVEN |
| `REQ-DBO-AUTH-TENANT-SCOPED-ISSUER` | Every tenant is its own OIDC authority with its own issuer URL, discovery document, key set and token endpoint; relying parties trust exactly one tenant's authority, never the store's. A token from any other tenant fails signature verification before any claim is read. | PROVEN |
| `REQ-DBO-AUTH-PORTABLE-AUTHORITY` | The issuer string is per-tenant configuration and the key material lives in the tenant database — a tenant can move deployments or present a custom domain without re-keying. | PROVEN |
| `REQ-DBO-AUTH-IDENTITY-AS-RECORDS` | Client applications, grants and signing keys are regular records in the tenant's own store — versioned, provenance-stamped, visible to feeds, and carried by the maintenance export: restoring a tenant restores who may access it. | PROVEN |
| `REQ-DBO-AUTH-DENY-BY-DEFAULT` | A serving deployment without a working authority refuses to serve tenant endpoints; disabling auth is an explicit embedded/test flag, never a default. | PROVEN |
| `REQ-DBO-AUTH-BEARER-LOCAL-VALIDATION` | The serving surface accepts OAuth2 bearer JWTs validated locally against the tenant's own cached key set — no per-request dependency on any other service. | PROVEN |
| `REQ-DBO-AUTH-SMART-SHAPED-SCOPES` | Authorization vocabulary is the SMART system-scope grammar, so finer service permissions and the future read-only public capability need no new language. | PROVEN |
| `REQ-DBO-AUTH-ONE-CEREMONY-MANY-TENANTS` | One national authentication serves every tenant authority in the deployment through the identity hub's session — the upstream broker is invoked once per session, not per tenant; authorization remains strictly per-tenant. | PROVEN |
| `REQ-DBO-SCIM-DECLARED-PER-TENANT` | A tenant serves SCIM 2.0 only when its spec declares it (the block naming the externalId system); absent the block, the endpoints do not exist. | PROVEN |
| `REQ-DBO-SCIM-DIRECTORY-CREDENTIAL` | The SCIM client's scope admits the SCIM surface and nothing else; its token is refused by the FHIR surface and a store token is refused by SCIM. | PROVEN |
| `REQ-DBO-SCIM-USER-IS-THE-PERSON` | A SCIM User is the human: the externalId claimed and identifying data authored on the Person, with a linked Practitioner capacity ensured on create — the same linkage the authority walks at token time. | PROVEN |
| `REQ-DBO-SCIM-ENUMERATION-STAYS-INSIDE` | The by-system enumeration answering the user list is a vault method inside this server; no store API, face or FHIR search gains it, and an enumeration-shaped search stays refused at the front door. | PROVEN |
| `REQ-DBO-SCIM-EVERY-OP-IS-A-DISCLOSURE` | Every SCIM operation runs with the client as caller and an administrative purpose stated, so it lands in the trail as one recorded provisioning disclosure. | PROVEN |
| `REQ-DBO-SCIM-GROUPS-READ-ONLY` | Groups render from active role grants and refuse writes permanently — who works here is the identity provider's call; who is an admin here is not. | PROVEN |
| `REQ-DBO-AUTH-ORG-MODEL-IS-THE-AUTH-MODEL` | Human authorization derives from the tenant's own records — Practitioner is the subject, an active PractitionerRole is the grant, the Organization tree is the scope structure; there is no parallel user database to drift. | PROVEN |
| `REQ-DBO-AUTH-ROLE-GRANTS-AS-RECORDS` | The role-to-scope mapping is tenant-administered regular records — auditable, feed-visible, exported; changing who may do what is a recorded act. | PROVEN |
| `REQ-DBO-AUTH-CREDENTIAL-FACTORS-BY-KIND` | A local credential holds factors named by kind (RFC 8176 `amr`): a bench PIN, a password and a passkey are different kinds, setting one leaves the others alone, and a kind is never a field named after the first case. | PROVEN |
| `REQ-DBO-AUTH-PASSWORD-ONLY-WHERE-WE-ARE-THE-IDP` | A password is held only where the tenant is the identity provider for that subject. Signing in through the hub records that the hub identifies this person, which is the signal the rule reads — federated login used to resolve somebody and leave no trace it had. From then on a password is refused at every door that could set one, naming where they sign in instead rather than answering no, and a password they already held is retired with a stamp saying when and why: one surviving federation would be a second way in that never reaches the identity provider. A bench PIN is untouched, before and after — it serves the case federation cannot, and taking it away would remove the fallback for the situation the rule was written around. | PROVEN |
| `REQ-DBO-AUTH-FIRST-SECRET-BY-ONE-TIME-GRANT` | A subject sets their own first secret by redeeming a one-time, short-lived grant the authority mints and never delivers: the consumer owns the address and the mail, so no delivery enters the trust root. Minting resolves nothing, redemption burns the grant on presentation rather than on success, and a grant authenticates nothing and cannot be exchanged for a token. | PROVEN |
| `REQ-DBO-AUTH-SELF-SERVICE-CHANGE` | A signed-in subject can replace their own password by proving possession of the current one. No ticket, no second channel, and no other factor is touched. | PROVEN |
| `REQ-DBO-AUTH-RECOVERY-IS-AN-OPERATOR-ACT` | A subject who cannot sign in is recovered by provisioning or an operator write, never by a self-service ceremony: recovery needs a channel the authority does not have, and acquiring one would put delivery inside the trust root. | PROVEN |
| `REQ-DBO-AUTH-DEACTIVATION-RETIRES-CREDENTIALS` | Deactivating a subject retires its credentials — every factor, at once, and never by deletion: history and audit need the record, and a login that vanishes cannot be told from one that never existed. | PROVEN |

Coverage: {PROVEN=33} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **No shared tier.** Every tenant is a database. That is the right default
  and the only option: a clinic too small to justify one has nowhere cheaper
  to go, and the shared-schema tier is specified and not built.
- **No quotas.** Nothing bounds what one tenant can consume, so a busy clinic
  and a quiet one on the same node are not isolated from each other by
  anything but luck.
- **The raw surface is not private yet.** The design says public interaction
  reaches the store through process-based surfaces and that the store's own
  REST is never publicly routed. Today that is the consumer's job to enforce
  at their edge rather than the store's to guarantee.

## Open decisions

- **Whether a tenant that fails to come up should keep trying.** It is
  retried on every scan today, which is right for a transient database and
  wrong for a declaration that will never parse. Nothing distinguishes them.
- **What a partner may do to a tenant it manages**, beyond following its work.
  A partner is declared at creation and reads journeys; whether it may ever
  provision or retire one is unanswered.
