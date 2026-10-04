# US-DBO-A-DEPLOYMENT-IS-EQUIPPED — what a deployment is given before it starts takes effect exactly as given

> Mart runs the deployment a group of clinics lives on. He does not write
> tenants and he does not write the application. What he decides comes
> before either: which disk the store keeps its face images on, the secret
> each clinic's authority will accept from him, the national broker the
> deployment's sign-in goes to, and the secrets of the brokers a zone
> names. He writes those down once, the process starts, and nobody asks him
> again.
>
> So what he needs from the store is narrow and strict. Each thing he gave
> has to take effect as he gave it. An image from last month's release must
> not load as though it were this one's. A secret he chose for one clinic
> must not open another. A zone that names two brokers must use the one
> each clinic contracted, and charge him for each ceremony once.

## The scene

The deployment serves one FHIR version to most clinics and keeps a
regional zone in a newer one. Before it starts, Mart gives it:

- **a directory for face images**, on a disk he provisions for it;
- **a bootstrap secret per clinic**, which already sits in his vault before
  the clinic exists;
- **the national broker** the deployment's identity hub federates to, with
  its client secret;
- **the secrets of the zone's brokers**, by broker code, because the zone
  declares which brokers exist but the credentials are his.

## A face is cut once and every tenant after the first comes up from it

The first tenant to want a face cuts it into the image directory. The next
one loads the image instead of reading the whole version through a chain
and expanding it again, and afterwards nobody can tell which route a tenant
took: the same rows, the same record of which of them came from the face,
and the stream left exactly where the image was cut, so a definition
published afterwards still arrives, once. A second face root on the same
face loads the image too, and publishes everything it loaded so that
tenants can take the face from it.

An image says what it was cut from. One from another release, another
face, another expander or another version of the SQL that reads it is
refused by name, and nothing is loaded. One whose manifest is missing,
because the job cutting it died, is refused as well. An image is brought
up from and never merged into: loading it over a database that already
holds rows is refused. Mart's warmup job cuts a face where he keeps them
and leaves nothing half-written beside it, and it refuses to cut a tenant
that is not a face root.

## The secret he chose opens his clinic, and only his clinic

The secret Mart put in custody for a clinic is the one its authority
accepts. A token minted with it opens the clinic's surface. The same
secret presented to a second clinic is refused, and the second clinic's
own secret is accepted there and issues a token of its own. A bootstrap
credential is custody for one tenant, never a key to the deployment.

## The zone's brokers are the ones each clinic contracted

The zone declares two national brokers, a government one and a private
one, and the person identifier system its members resolve people by. A
private clinic in the zone signs its clinician in through the private
broker, once, and finds them by the zone's own identifier system rather
than the one the deployment was configured with. The municipal hospital
accepts only the government broker, so signing in there runs that ceremony
too, on the same session, and the private one is not run again. From then
on the session carries both, and either clinic signs the clinician in with
no further ceremony.

## The deployment's own hub signs people in once for every clinic

Clinics outside the zone federate through the deployment's hub to the
national broker Mart configured. The first sign-in runs the national
ceremony once and names the clinician it resolved. A second clinic is
served from the same session at no further cost. A third clinic, where the
same person holds no role, refuses them, and the refusal costs no ceremony
either: authentication is shared, authorisation never is.

The sign-in leaves a record that the hub identifies this person. From then
on the clinic will not hold a password for them: the one they had before is
retired, and setting one again is refused at both doors, naming where they
sign in instead. Their PIN keeps working, because it serves the case
federation cannot. Someone the clinic is the identity provider for is not
affected.

## A zone in a newer version reaches a clinic in an older one

A clinic on the older version that declares the zone is served by a
projection nobody declared: the store converts the zone once, on that
face, and the clinic reads the converted copy while its own declaration
still names the zone. A clinic on the zone's own version reads the zone
directly. What the zone publishes arrives on both sides, and the
projection leaves an image in Mart's image directory carrying the face and
the converted zone, with no records in it.

When the zone publishes a profile built on a resource the older version
never had, the projection names it as something it could not carry. A
clinic declaring the zone after that does not come up, and its refusal
names the zone and the lost definition. A clinic on the zone's own version
is unaffected. A definition the projection was already given by its face
does not stop the zone's stream behind it.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-TEN-A-TENANT-COMES-UP-FROM-THE-FACE-IMAGE` | A face is cut once per release into an image of its definitions schema, and a tenant coming up on that face is brought up from the image rather than reading the whole of what the face publishes through a chain and expanding it again. An image is brought up FROM and never merged into: loading one over rows that are already there would double what a face holds or lose half of it, with nothing to say so. What the image carries is the face — the definitions, their history and everything derived from them — and not the tenant's own relationship to a feed, since a tenant that inherited another's cursors would stand at a position it never reached. | PROVEN |
| `REQ-DBO-VER-AN-IMAGE-FROM-ANOTHER-RELEASE-IS-REFUSED` | An image says what it was cut from — the release, the face, the shape its definition rows were taken apart into, and the fingerprint of the SQL that reads them — and one that disagrees with this release on any of those is refused by name, nothing loaded. Refused rather than repaired: the tenant comes up the way tenants came up before there were images. The check exists because a wrong image fails nowhere — the rows load, the tenant serves, and it answers from a specification or an expander that is not this one. | PROVEN |
| `REQ-DBO-VER-AN-IMAGE-IS-CUT-ONLY-WHEN-COMPLETE` | An image is cut in one consistent read and its manifest is written last, so a cut that died partway through produces something without a manifest rather than something that looks whole. An image with no manifest is refused, because there is nothing to check it against and its rows would load perfectly well. | PROVEN |
| `REQ-DBO-AUTH-BOOTSTRAP-SECRET-IS-CUSTODY` | A tenant's bootstrap credential is the secret its deployment already holds, named per tenant, and never one the store invented and kept to itself: custody is the operator's, so a deployment without one says what it decided rather than being locked out of its own authority. | PROVEN |
| `REQ-DBO-AUTH-FEDERATED-HUMANS` | Human authentication is federated to the configured identity broker; the authority resolves the verified national identifier to a Practitioner through the vault index and owns authorization only. Local credentials are an embedded/dev fallback, never the production path. | PROVEN |
| `REQ-DBO-ZONE-BROKER-CHOICE` | The broker set is jurisdictional, the choice organizational: the zone declares the available national brokers; a tenant selects its contracted one and may restrict what it accepts. | PROVEN |
| `REQ-DBO-ZONE-SUBJECT-DOMAINS` | Subject-resolution identifier systems come from the zone's declared domains — the official national terminology — never from dbo code. | PROVEN |
| `REQ-DBO-ZONE-SESSIONS-ACCUMULATE` | The per-zone hub's session records which broker performed each ceremony and accumulates ceremonies; cross-broker reuse is the default, tenant acceptance policy the restriction — the strictest tenant is satisfied without invalidating anyone else's session. | PROVEN |
| `REQ-DBO-AUTH-ONE-CEREMONY-MANY-TENANTS` | One national authentication serves every tenant authority in the deployment through the identity hub's session — the upstream broker is invoked once per session, not per tenant; authorization remains strictly per-tenant. | PROVEN |
| `REQ-DBO-AUTH-PASSWORD-ONLY-WHERE-WE-ARE-THE-IDP` | A password is held only where the tenant is the identity provider for that subject. Signing in through the hub records that the hub identifies this person, which is the signal the rule reads — federated login used to resolve somebody and leave no trace it had. From then on a password is refused at every door that could set one, naming where they sign in instead rather than answering no, and a password they already held is retired with a stamp saying when and why: one surviving federation would be a second way in that never reaches the identity provider. A PIN is untouched, before and after — it serves the case federation cannot, and taking it away would remove the fallback for the situation the rule was written around. | PROVEN |
| `REQ-DBO-ZONE-A-ZONE-IS-SERVED-TO-A-FACE-THROUGH-ONE-PROJECTION` | A zone's definitions and records reach the tenants of a face it was not written in through one projection per zone per face: a tenant that takes the zone and stands on the target face, so the conversion happens once rather than once per tenant. A zone serving a face it was written in has no projection, because there is nothing to convert and one would be a hop, a database and a second copy for nothing. Nobody declares them — they follow from a zone's version and the faces of the tenants that asked for it — and a tenant's declaration still names the zone, since which projection serves it follows from its own face and is not a tenant's to know. | PROVEN |
| `REQ-DBO-ZONE-WHAT-CONVERSION-CANNOT-CARRY-IS-REFUSED-BY-NAME` | A definition converted to another face is judged on that face rather than trusted because it converted. Converting downward loses what the older version cannot say, and a structure built on a resource that version never had comes out well-formed and standing on nothing — it loads, and nothing can be validated against it. So the projection, the one tenant holding both the converted definitions and the face they were converted into, names every definition whose base that face does not carry, and says which base it lost. Named rather than counted: a count says a zone is partly unservable and leaves somebody to find out which part. | PROVEN |
| `REQ-DBO-ZONE-AN-UNSERVABLE-ZONE-IS-SAID-AT-BRING-UP` | A tenant whose zone did not survive the trip to its face does not come up, and says which definition was lost and what it was built on. Refused rather than degraded: the tenant would otherwise serve the part of the zone that survived, which looks exactly like serving the zone. A zone is a set of rules somebody is relying on being applied, and most of one is not a smaller promise but a different one nobody agreed to. A tenant on the zone's own face is unaffected, because nothing was converted and nothing can have been lost. | PROVEN |
| `REQ-DBO-SYNC-LOCAL-SHADOWING` | A tenant's own object with the same base identity overrides the streamed copy — version-neutrally, across FHIR versions and business versions; removing the override falls back to the live upstream version. | PROVEN |

Coverage: {PROVEN=14} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **One upstream for the deployment's hub.** A tenant outside every zone
  federates to the single broker the deployment was configured with. A
  deployment serving clinics that contracted different brokers has to put
  them in a zone to give them a choice.
- **Broker secrets are keyed by broker code across the deployment.** Two
  zones naming a broker by the same code are given the same secret.
- **What a deployment was given is read when it is built.** A broker's
  secret or the hub's upstream changes only when the process is restarted
  with the new value.
- **What loading the specification costs is measured, not configured.** A
  deployment sizes its heap from a figure the store records in a process
  that holds nothing else. Nothing in what a deployment is given bounds it.

## Open decisions

- **Which face root a projection stands on.** A projection takes the first
  root of its face among the deployment's declarations. A deployment with
  several roots on one face cannot say which one it wants.
- **Whether a tenant refused for an unservable zone should keep trying.** It
  is retried on every scan, which is right once the zone is fixed where it is
  published and wasted until then.
