# Implementation status

What is built, what it is proven by, and what is specified but not yet
built. The specification itself lives in the arc42 tree ([index](../README.md));
the requirement codes below are defined in the
[REQ catalogue](../arc42-006-runtime/req-catalogue.md).

**940 behaviour-named tests across 185 classes, 143 of them integration tests
against a real Postgres, a real Felix container, the shipped distribution
booted as a separate JVM, and — for the provisioning operator — a real
Kubernetes API server. CI is green on every commit; nothing
publishes and no image ships unless the whole suite passes on exactly that
commit.**

## Built

### CORE — the object engine

`dbo-core` is the zero-dependency API: `ObjectStore`, `PutRequest`,
`StoredObject`, UUIDv7 identifiers, the sealed `IdentityRef`, and the
`Handling` classification every registered type must declare. `dbo-postgres`
implements it — single-transaction writes, database-enforced no-merge on
identity claims, and the `state`/`history`/`dbos` schema split. Payloads
carry a version and pass through a `PayloadConverter` chain on read, so a
domain written under one FHIR version re-binds to another without a rewrite;
history is exempt by design. Every version links to the one before it, so a
history that was edited underneath the store fails verification.

*Complete*, including the declared truth form, the identity rules and
upgrade-on-read.

### CONT — container and embedding

Every module is a real OSGi bundle. One shared bundle embeds the HL7/HAPI
engine and exports it to both personalities, and subscriptions embeds a private
DBOS, both as nested jars behind a `Bundle-ClassPath`; everything DBO-owned is
still the only thing a personality exports beyond its own validation resources. The production bundles
boot in an in-JVM Felix and serve a live FHIR flow over HTTP — that is the
test, not a packaging assertion. The serving distribution uses the standard
Felix launcher with no launcher code of its own; measured cold start is about
five seconds from boot to a first 200 on a current schema.

*Complete.*

### TEN — tenancy and isolation

`TenantDatabaseProvisioner` is a mandatory OSGi service that returns a
`DataSource` and never credentials — the property the whole isolation story
rests on. The default implementation gives each tenant its own database;
`dbo-tenant-k8s` gives the in-cluster edition, reading operator-written
Secrets. Tenant spec files become live service sets keyed by a `tenant=`
registry property, all on one shared port at `/t/<code>/fhir`. Retracting a
tenant stops serving it; erasing one is an explicit deprovision that drops
the database.

*Registry-scoped access, dynamic services, credential-blind provisioning, the
dedicated-database tier and erasure are done. Dedicated instances, the shared
tier and quotas are specified, not built.*

### AUTH — tenant authority and surface protection

`dbo-auth` is JDK crypto only: JWS RS256, JWK/JWKS, PBKDF2 secret hashing and
a SMART system-scope grammar. Identity artifacts — `ClientApplication`,
`SigningKey`, `RoleGrant` — are regular records in the tenant's own store,
which is what makes key rotation and authority portability ordinary
operations rather than special cases. Each tenant is its own OIDC authority
at `/t/<code>/oidc`; the store surface accepts only that tenant's own tokens,
so a cross-tenant token dies at signature verification. Human access derives
from the organisational model: an active `PractitionerRole` yields role codes
yield grants yield `user/*` scopes, and revocation is ending a period on a
clinical record. Authorization-code with PKCE, RFC 8693 token exchange for
processes acting in a human's name, and delegation records for workflows that
outlive a token. Tokens are pseudonymous — a practitioner reference, no name,
no national code. A deployment-level identity hub federates to a national
OIDC provider so one ceremony serves every tenant in the zone.

*Complete for the tenant authority, role model, federation and on-behalf-of.*

### PDI — personal-data isolation

`dbo-pdi` encrypts identifying elements inside the payload with per-person
keys, in the same atomic engine write. History, envelopes, feeds, archives
and sync therefore carry ciphertext by construction rather than by
remembering to. The vault holds wrapped keys, an HMAC identifier index, the
restriction flag and a shred ledger — never plaintext. Crypto-shredding
destroys the key; a pre-shred archive cannot resurrect the person, because
restore merges the ledger and replays it. Opt-in per tenant.

*Complete.*

### POL — tenant policies

Audit entries are regular records in the tenant's own `audit` domain with
their own outbox, so a subscription can watch access. The trail is open
upward and closed downward: applications contribute what happened, the
machinery stamps who and when from the token and its clock, and a posted
event claiming another agent and a false timestamp lands carrying the token's
client and the real time. `AuditEntry` is append-only against everyone,
including the vendor. Retention is declarative and its sweep is itself
audited, without retaining the data it removed.

*Complete. IHE BALP alignment of the AuditEvent projection is not done.*

### ZONE — jurisdiction overlay

A zone is a tenant whose declarations are records. Brokers and identifier
domains come from configuration and terminology, never from code; secrets
stay in broker custody. One hub per zone, multi-upstream, and sessions record
which broker performed each ceremony and accumulate across tenants with
different acceptance policies.

*Complete.*

### VER — version plurality

`dbo-fhir-common` holds what both personalities share and `dbo-fhir-stack` the
HAPI engine they both import; `dbo-fhir-r4` and `dbo-fhir-r5` coexist in one
JVM over one database, with zero differences in the engine between them. Search parameters
are extracted from HAPI's own definitions rather than hand-listed.

A tenant holds its version as records rather than as a context loaded into its
node: a face root reads the carried packages once and its subscribers take the
definitions from it through the ordinary chain. Every structure a tenant holds
is taken apart on arrival into element rows in the tenant's own database —
cardinality, types, fixed and pattern values, bindings, each located by a
jsonpath with choice keys and slice members already resolved — so a checker
reads rows instead of an object graph. An element nothing can locate is held
saying so rather than dropped.

*Complete except an R6 personality — there is no ballot to build against.
Nothing reads the expanded rows yet: the database-side checker they exist for
is the next front, and a structure that arrives without a snapshot is counted
rather than expanded until the face snapshots it on arrival.*

### SRCH — search

Tier 1 is the measured production shape: modifiers (`:exact`, `:missing`,
`:not`, `:identifier`), the `system|code` form, one-level chains,
`_lastUpdated`, `_summary=count`, `_elements`, `_include`, and
identity-keyed conditional create. Search is strict — an unrecognised
parameter is a 400, not a silently broader result — and the capability
statement says only what is true.

*Tier 1 complete. Tiers 2 and 3 are deliberately unclaimed.*

### FEED — feeds and pagination

One primitive serves both pagination and synchronization: named consumers
with ack, reset and lag, keyset paging, and gap-free reads behind a commit
fence. Delivery order is transaction-major, because a sequence alone cannot
fence a commit that lands late. A long transaction in another database on the
same instance no longer delays a quiet tenant's feed.

*Complete except the lean-wire option, which needs a wire to be lean over.*

### EVT — eventing and subscriptions

`dbo-subscriptions` is personality-agnostic; DBOS delivers. Matching is by
search, exactly-once comes from composing the feed position with the workflow
id, and the dead-letter queue is data like everything else. Topic-based
subscriptions run in the same engine: R5-native from stored
`SubscriptionTopic` records, and backported to R4 through configured topics.

*Complete.*

### TERM — terminology

`dbo-terminology` stores a concept per row rather than a resource per code
system — the truth-form inversion. Forty thousand concepts load in about half
a second; `$lookup`, `$validate-code` and `$expand` with is-a expansion work
over the native form. A shell code system says `content=not-present` instead
of pretending.

*Complete.*

### SYNC — canonical content dependencies

`dbo-sync` streams declared content over the feed: provenance-tagged copies
that keep the source id, conflict-driven shadowing with live fallback,
conversion at apply time so a zone's R4 content lands in an R5 leaf, and
chains that hop store by store through each one's own outbox.

*Complete at the stream-mechanics level. Terminology-grain hooks are
specified, not built.*

### MNT — maintenance

One sealed archive, two elements: portable NDJSON (re-importing into the same
tenant is a no-op; importing into a fresh one is a restore) and byte-faithful
dumps that preserve versions, history and consumer positions. The owner's key
seals it, so the operator cannot read it. The snapshot is repeatable-read
with the outbox fence recorded in the manifest, which makes the incremental
form just the feed. A restored consumer stands at the head of the restored
feed, not where it stood when the backup was taken. Archives are attested by
both parties and an altered one fails authentication rather than ending
quietly.

*Complete. The blob element waits on blob storage.*

### PROC — distributed work, and work that leaves sealed

Runs are records, a step declares itself and its input slots, and a
participant holds a *lane* — in-process, over HTTP, or over the store's own
durable substrate, the same verbs on all three so a runner cannot tell which
it holds. Work is authored on the tenant's surface: a `Task` posted there
that names a declared step becomes a run, refused by name where its rules
are not met, and reads back as the same `Task` as it advances. A router holds
the claim for the edge behind it, names that edge as the recipient, and waits.

One runner fleet can carry every tenant's work and read almost none of it.
A participant offers two public keys at enrolment (X25519 to be sealed to,
Ed25519 to sign with); its inputs leave as a readable manifest plus payloads
sealed under a per-payload key wrapped to it, in the carrier form, so a shred
reaches a copy in flight with no special case; the shared plane is checked by
reading every row of the substrate and holds the manifest, no content and no
token. The trail tells carrying from reading — a travel entry on the task per
hop, an access entry on the document per opening, the store's own read to
seal recorded as nothing — and the entries are chained from the task with the
result as the last link: a participant signs its openings, a suppressed link
is exposed by the next, a mismatched head is refused by name, and a pruned
predecessor reads unchained. A departed routee is kept with its last
attestation. A partner is a tenant that manages others, declared at creation;
it follows their journeys as an audience and reads no document.

The replication lane between two appliances of one tenant has both of its
bounds: patient data by work, arriving with a task and leaving with it, and
declarations by type, filed under their source and never revoked. What a run
produced travels with it. A deployment points the telemetry seam at its
collector by configuration and the node's numbers arrive as OTLP, with no
protocol library; the seam finds its exporter inside the container. User
stories are constants beside the promises, their joins projected rather than
written.

Overturning a closure is reached through the lane and by its own half of an
entitlement: a credential that performs a step does not thereby overturn its
closures, one that speaks for the whole tenant supervises nothing unless
somebody wrote it down, and a supervisor takes no work. The step's declared
actions are enforced on the lane as well as at authoring, which they were not.

A deployment is read from outside every container: one process holds the
deployment's token for the node questions and one credential per tenant for
the tenant questions, fans out over the doors each already serves, and labels
every answer with the node it came from — as a command that reads once, or a
service that reads afresh on every ask and holds nothing between them, imaged
beside the operator. A node serves its installed catalogue
as an inventory beside its tenant states, and the reader's union of those
inventories is the network map — by step and version, descriptive, never a
declaration. A tenant's fleet door answers its runs as envelopes. A node that
does not answer and a tenant the reader holds no credential for are in the
reading as such rather than missing from it. The same process acts: it
overturns a closure through the tenant's own lane, with a supervisory
credential granted separately from the one it reads with and a service surface
that is not mounted unless a deployment named a second token for it.

*Built and proven, the whole of the sealed-work design included. What remains
PLANNED in this area: the process catalogue held in the store, the domain-code
filter, and one-parent-never-across-a-boundary.*

### Packaging

The spec-authoring transitives are gone from the personalities. The HL7 core
stack carried a UML renderer, a package-cache database, an XSLT engine and a
git client as transitives of `org.hl7.fhir.*` — machinery for authoring the
specification rather than for validating against it. Excluding them:

| | before | after |
|---|---|---|
| `dbo-fhir-r4` | 137.9 MB | **101.1 MB** |
| `dbo-fhir-r5` | 155.7 MB | **118.9 MB** |

36.8 MB from each, and the same 36.8 MB again from every additional copy a
per-tenant framework would cache.

Then the engine itself stopped being per-personality. Of the ~76 jars each one
embedded, 74 were byte-identical between R4 and R5, so one bundle
(`dbo-fhir-stack`) embeds the HL7/HAPI engine and exports it, and a personality
keeps its own code and the validation resources of its own version:

| | before | after |
|---|---|---|
| `dbo-fhir-stack` | — | **95.7 MB** |
| `dbo-fhir-r4` | 101.1 MB | **5.4 MB** |
| `dbo-fhir-r5` | 118.9 MB | **23.2 MB** |

220.0 MB to 124.3 MB, and the shared 95.7 MB is now cached once per framework
instead of once per personality per framework. A third personality costs its
validation resources rather than another engine.

Slimming the engine per version was not the alternative it looked like: the HL7
validator converts every input up to R5, validates there and maps back, so
`r4`/`r4b`/`dstu3`/`dstu2` are its input adapters rather than spare parts. One
indivisible version-agnostic engine is what makes it a good thing to own once.

`icu4j` (14.5 MB) stays: internationalised string handling is plausibly on a
validation path, and removing it on the strength of a name would be a guess.

The proof is that the suite still exercises what those jars could have been
hiding behind — R4 validation, the R4→R5 converter, CodeSystem ingestion, and
the production bundles booting in a real Felix container. A green compile
proves nothing here, because a `Class.forName` behind an authoring entry point
resolves fine until something calls it.

### The serving surface

`dbo-rest` is a JDK `HttpServer` on virtual threads with no framework and no
new dependencies: CRUD with ETag/If-Match/If-None-Exist, absolute paging
links, `OperationOutcome` for every error, `_history`, the terminology
operations, and a generated `/metadata`. `dbo-operator` reconciles
`TenantRegistration` custom resources with a scoped provisioner role that is
never superuser, and its deletion policies distinguish "stop serving" from
"erase".

## Specified, not built

Fifteen promises read `PLANNED`, and they fall into four groups. **SCAL**
(routing: durable assignment, single-writer tenants, transparent routing,
two-hop locality, no shared-state broker) has no implementation at all. **WF**
is down to one — platform-coordinated hops — because no tenant-to-tenant hop
exists to prove anything about; the two plane promises are proven by the
sealed-work proofs. **OPS** keeps blob storage and migration-as-deployment;
the telemetry exporter and the fleet read are built. **TEN** keeps the shared
tier and quotas, **AUTH** the private surface, **FEED** the lean wire option,
and **PROC** the three named above. What a process, a step and a run are, and how work reaches
whoever performs it, is in
[`processes-and-work.md`](../arc42-008-crosscutting/processes-and-work/README.md); how
those concepts are rendered for a reader of a standard is in
[`the-fhir-face.md`](../arc42-008-crosscutting/the-fhir-face/README.md).

## Known next fronts

Unordered, and each needs its own design pass before it starts.

- **Enable personal-data isolation for existing tenants.** The flag is
  per-tenant and takes effect on write, so tenants that hold pre-isolation
  plaintext need a decision — recreate or accept — before they hold real
  patient data.
- **Live national-broker registration.** The federation flow is proven
  against a stub; the real broker is an environment configuration and a
  registration process.
- **Zone content over real chains.** The zone's declarations are chain-ready
  records; today a deployment seeds them locally rather than streaming them
  from an upstream.
- **IHE BALP alignment.** Profile the AuditEvent projection against Basic
  Audit Log Patterns once there is a consumer to validate against.
- **Durable retention sweep.** The periodic sweep is idempotent and correct
  in process; promoting it to a scheduled durable workflow makes it survive a
  restart mid-sweep.
- **Placement.** When zones multiply databases past one server's comfort, a
  `TenantRegistration` grows a placement target. The custom resource is
  already the seam for it.

Later horizons: the routing layer, an R6 personality when there is a ballot,
a shared-schema tenancy tier, blob storage, and the three parked questions
that carry their own triggers — the storage grain a face declares, the rest
of cold start, and tier-2 search.
