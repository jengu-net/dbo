# Using DBO

For somebody building an application on this store, rather than on it.

Every section below is one capability: what it is, how an application reaches
it, and **what an application would otherwise have to write itself**. That
last line is the useful one. A capability the store already provides and an
application re-implements is not only duplicated work — it is a second answer
to a question the store answers structurally, and the two drift.

Read [docs/README.md](README.md) for the specification tree, and
[docs/plans/implementation-status.md](https://github.com/jengu-net/dbo/blob/main/docs/plans/implementation-status.md)
for what is built versus specified. **Where this document and the requirement
catalogue disagree, the catalogue is right**: it is generated from the
promises and their proofs, and this is prose.

---

## The shape in one paragraph

A **tenant** is a database. It holds **records** of the **types it declared**,
served over a **face** — a standard it speaks, FHIR today. Who may act comes
from the tenant's own records. What is done to records is **work**: processes
with steps, and access is granted to a step rather than to a person. Records
stream to other tenants over **feeds**. A **zone** is a tenant whose records
are a jurisdiction's rules. Everything is records, including the audit trail,
the configuration, and the definitions the store validates against.

---

## The capability map

| you might be building | the store already has | section |
|---|---|---|
| a per-customer database and its provisioning | tenants | [Tenancy](#tenancy) |
| an authentication flow, token issuing and validation | a **per-tenant OIDC authority**, federated to a broker | [Authentication and authorisation](#authentication-and-authorisation-who-may-act) |
| a user table, roles, and a permission matrix | **authorisation** from the tenant's own records | [Authentication and authorisation](#authentication-and-authorisation-who-may-act) |
| a user-provisioning endpoint for your identity provider | **SCIM 2.0** at `/t/<code>/scim/v2` | [Authentication and authorisation](#authentication-and-authorisation-who-may-act) |
| an audit log table and the code that writes it | **audit as records** — the trail | [The trail](#the-trail-audit-as-records) |
| a purpose or lawful-basis story you assemble for an auditor | the step *is* the purpose, and the trail records it | [Meeting the regulation](#meeting-the-regulation) |
| a GDPR checklist implemented once per application | a mechanism per obligation | [Meeting the regulation](#meeting-the-regulation) |
| field-level encryption and a key table | personal-data isolation | [Personal data](#personal-data-encryption-and-the-vault) |
| a "delete this person everywhere" script | erasure that reaches copies | [Erasure](#erasure-the-right-to-be-forgotten) |
| an export job and an import job | one archive, both ways | [Export, import, backup](#export-import-backup) |
| a job table, a worker loop, retries | **processes → steps → runs** | [Work](#work-processes-steps-and-runs) |
| a queue or broker between services | lanes and feeds | [Work](#work-processes-steps-and-runs), [Feeds](#feeds-change-events-paging-and-replication) |
| a change-notification table and pollers | the change feed with named consumers | [Feeds](#feeds-change-events-paging-and-replication) |
| a terminology table and code lookups | terminology as rows | [Terminology](#terminology-code-systems-and-value-sets) |
| a validator, or per-endpoint field checks | validation from declared definitions | [Validation](#validation) |
| a search endpoint per query shape | declared search | [Search](#search) |
| a country/config table read at startup | zones and applied configuration | [Jurisdiction](#jurisdiction-zones), [Configuration](#configuration) |
| a webhook or push-notification delivery service | the **feed** — subscription delivery does not run | [What is not there](#what-is-not-there) |
| file or document storage | **nothing yet** — blob storage is not built | [What is not there](#what-is-not-there) |
| a version-migration script for stored payloads | payload converters on read | [Records](#records-storage-versions-and-history) |
| a "who changed what when" screen | history, every version kept | [Records](#records-storage-versions-and-history) |

---

## Tenancy

**What it is.** A tenant is its own PostgreSQL database, not a tenant column.
It has its own records, its own authority, its own face, and its own key
material. A tenant is declared by a spec — `{"code":…, "face":…, "types":[…]}`
— and the runtime brings it up when the declaration appears.

**How to reach it.** Declare the tenant; the store provisions. Its FHIR
surface is `/t/<code>/fhir`, its authority `/t/<code>/oidc`. A spec declares
`types` (what the tenant holds), `pdi` (personal-data isolation), `zone`,
`dependencies`, `policies`, and `faceRoot`.

**What you would otherwise write.** Provisioning, connection routing, the
per-request tenant filter on every query, and the review that proves no query
forgot it. There is no cross-tenant surface to forget: separation is a
property of where the data is, not of a predicate somebody remembered.

→ [a tenant is a database](arc42-008-crosscutting/data-isolation/README.md)

## Records: storage, versions and history

**What it is.** An opaque payload with a derived, searchable envelope. Every
version is kept; each links to the one before it, so an edited history fails
verification. A type declares how it is identified — by a canonical url, by
designated identifier systems, or by store-assigned id only — and identity is
enforced at the write: a second record claiming a taken identifier is refused
rather than silently merged.

**How to reach it.** `ObjectStore`: `put`, `putIfAbsent`, `putConditional`,
`get`, `getByIdentifier`, `history`, `select`, `inventory`. Over HTTP it is
ordinary FHIR: create, read, update with `If-Match`, conditional create with
`If-None-Exist`, `_history`.

**What you would otherwise write.** Optimistic locking, a version table, an
audit of who changed what, dedupe-on-identifier, and a migration script every
time the payload shape moves — payloads carry a version and pass through a
converter chain on read instead.

→ [records you can rely on](arc42-008-crosscutting/records-you-can-rely-on/README.md)

## Authentication and authorisation (who may act)

**What it is.** The organisational records a tenant keeps anyway are the
grants. A person's access derives from an active role record; revoking it is
ending a period on that record. Each tenant is its own OIDC authority and
accepts only its own tokens, so a token from one tenant is unintelligible at
another. Tokens are pseudonymous — a reference, no name.

**How to reach it.** Authenticate at the tenant's authority; the token's
scopes come from the role records. Authorization-code with PKCE for people,
client credentials for systems, RFC 8693 token exchange for a process acting
in somebody's name, and delegation records for work that outlives a token. A
deployment-level hub federates to a national identity provider so one ceremony
serves every tenant in a zone.

**Authentication is shared; authorisation never is.** The store authenticates
nobody in production. What each tenant does with a verified identity is its
own: it resolves the person in **its own records** and mints its own token, and
a token from one tenant fails at another's signature check before a claim is
read. So the same verified person is granted at one tenant and refused at the
next — that is the design rather than a misconfiguration, and it is the
sentence to weigh before placing an identity provider.

**People are provisioned over SCIM 2.0** at `/t/<code>/scim/v2`. A `User` lands
as a Person claiming the external id, with a linked practitioner capacity;
`active=false` deactivates rather than deletes; replace honours the ETag and a
stale one is refused; a second `User` claiming one external id is a conflict
naming it. The directory stays the identity provider's and the records stay the
tenant's, with no synchronisation job between them.

**What you would otherwise write.** A user directory, a role table, the
synchronisation between it and the clinical records, a provisioning endpoint,
and the incident where somebody who left last year still had access because
only one of the two was updated.

→ [who may act](arc42-008-crosscutting/who-may-act/README.md)

## Work: processes, steps and runs

**What it is.** A **process** is named work with **steps**; a **step** is the
unit everything attaches to — what it consumes, what it produces, who may
perform it; a **run** is one attempt at one step. Access is granted to a step,
so performing it is what leaves the proof of why data was reached. Work is
authored on the tenant's own surface: a `Task` naming a declared step becomes
a run and reads back as that `Task` as it advances.

**Nobody is pushed.** The store never calls out. Participants ask what is
available to them, take it, and report back — so a participant behind a
firewall, or asleep for a weekend, does not need a hole opened towards it. A
participant holds a **lane**: in-process, over HTTP, or over the store's own
durable substrate, with the same verbs on all three.

**How to reach it.** Declare steps; post a `Task` that names one. `Runs`
offers `pipeline`, `sweep`, `item` and `pass` for the run shapes. Work that
leaves the tenant can be **sealed** to a participant's key, so a runner fleet
can carry work it cannot read.

**What you would otherwise write.** A job table, a worker loop, retry and
backoff, a dead-letter path, the "who is allowed to run this" check in each
worker, and the correlation that lets anybody reconstruct afterwards why a
record was read.

→ [processes and work](arc42-008-crosscutting/processes-and-work/README.md)

## Feeds: change events, paging and replication

**What it is.** One primitive serves paging and synchronisation: an ordered,
replayable sequence with a durable cursor. Every durable consumer is a name
and a position, so progress, lag and replay mean the same thing for a client
paging results, a subscriber, a dependent tenant and an appliance that has
been offline since Friday.

**How to reach it.** Read with a consumer name and acknowledge a cursor; reset
to replay. Change events originate as an outbox row committed in the same
transaction as the write, so there is no window where the write landed and the
notification did not.

**What you would otherwise write.** A notification table, a poller, the
at-least-once bookkeeping, and a separate mechanism for each of paging,
replication and catch-up.

→ [change, and who is listening](arc42-008-crosscutting/change-and-who-is-listening/README.md)

## Search

**What it is.** Declared search over the envelope, and **strict**: an
unrecognised parameter is a refusal, never a silently broader result, and the
capability statement says only what is true.

**What is supported today** — tier one, and deliberately nothing claimed
beyond it: modifiers `:exact`, `:missing`, `:not`, `:identifier`; the
`system|code` token form; one-level chains; `_lastUpdated`; `_summary=count`;
`_elements`; `_include`; and identity-keyed conditional create.

**What you would otherwise write.** An endpoint per query shape, and the
index-maintenance code behind each one. A tenant may author its own search
parameters; they are compiled when they arrive.

→ [finding things](arc42-008-crosscutting/finding-things/README.md)

## Validation

**What it is.** Structures, profiles, value sets and search parameters are
**records**, streamed to a tenant over its face, and expanded into rows when
they arrive. A tenant's own profiles are records like any other. What a type
must contain is therefore data the tenant holds, not a schema compiled into an
application.

**How to reach it.** Declare a type on the tenant; the face supplies the
definitions. Write a profile as a record to constrain further. A write is
validated against what the tenant holds, including terminology bindings
resolved against the tenant's own concepts.

**What you would otherwise write.** Per-endpoint field checks, a second copy
of the rules in application code, and the drift between them and the
specification when it moves.

→ [the engine and its faces](arc42-008-crosscutting/engine-and-faces/README.md)

## Terminology: code systems and value sets

**What it is.** A concept per row rather than a resource per code system, so a
large code system loads and answers quickly. A tenant holds its own; a shell
code system says `content=not-present` rather than pretending to be complete.

**How to reach it.** `$lookup`, `$validate-code` and `$expand`, including is-a
expansion. A code from a system the tenant does not hold is *unresolvable*,
which is a different answer from *invalid*.

**What you would otherwise write.** A code table, a loader, and the expansion
logic — plus the decision, made once per application, about what to do with a
code you cannot resolve.

## Personal data: encryption and the vault

**What it is.** Identifying material is encrypted **inside the payload**, with
a key belonging to that person, in the same atomic write. So history, feeds,
archives and replication carry ciphertext by construction rather than by each
path remembering to encrypt. The party operating the deployment can provision,
back up, restore and upgrade without being able to read a person's data.

**How to reach it.** Opt in per tenant with `"pdi": true`. Applications write
ordinary records; the encryption is where the write is, not where the caller
is. Identifying lookups resolve through the vault's index.

**What you would otherwise write.** Field-level encryption, a key table, the
rotation story, and an audit of every read path to prove none of them leaked
plaintext into a log, an export or a replica.

→ [data isolation](arc42-008-crosscutting/data-isolation/README.md)

## Erasure: the right to be forgotten

**What it is.** Erasing a person destroys the key rather than chasing rows, so
it reaches copies nobody can recall — including archives already written. The
erasure is recorded without personal data and replayed on every restore, so an
old backup cannot silently resurrect somebody.

**How to reach it.** An operation, not a project: it answers with a record of
what happened, which a deletion never could.

**What you would otherwise write.** A cascade delete, a list of every system
holding a copy, and the unanswerable question of what to do about last
month's backup tape.

## The trail: audit as records

**What it is.** Audit entries are ordinary records in the tenant's own audit
domain, with their own feed. The trail is open upward and closed downward:
applications contribute what happened, the machinery stamps who and when from
the token and its clock. It is append-only against everyone, the vendor
included. Retention is declarative, and the sweep that enforces it is itself
audited without retaining what it removed.

**How to reach it.** Write what happened; do not write who or when — those are
taken from the credential. Read it back like any other records, and subscribe
to it like any other feed.

**What you would otherwise write.** An audit table, the discipline of writing
to it on every path, the retention job, and the argument about whether an
administrator could have edited it.

## Jurisdiction: zones

**What it is.** A **zone** is a tenant whose records are a country's rules:
which identity brokers exist, which identifier systems people are resolved by,
which terminology is canonical. Tenants inside a zone inherit them over the
ordinary chain. Nothing in the code knows the name of a country.

**How to reach it.** Declare `zone` on the tenant. Zone content streams down;
a tenant may narrow what it accepts but never widen it.

**What you would otherwise write.** A configuration table per country, a
deployment per market, and a release whenever a jurisdiction changes a broker
or retires an identifier scheme.

→ [declared rules](arc42-008-crosscutting/declared-rules/README.md)

## Configuration

**What it is.** A declared set — value sets, profiles, search parameters,
which tenants a deployment serves — read from a source (a git repository, a
directory, a mounted ConfigMap) and applied to a **scope**. Applying is a run
with an account of what it read, applied and skipped, and a skip is held work
with a name rather than a line in a log.

**How to reach it.** Point a source at a scope and apply. What a scope last
agreed with is recorded on its own run, so two scopes reading the same
repository can stand at different revisions — which is how a set is exercised
in one place before it is applied in another.

**What you would otherwise write.** A bootstrap script per environment, and
the belief that it did what it said.

→ [running it](arc42-008-crosscutting/running-it/README.md)

## Export, import, backup

**What it is.** One sealed archive. Backup and export are one mechanism;
restore and import are another. Every backup is restorable by the everyday
import path, so the route out is the route with the most mileage on it. The
archive is sealed to the owner's key, and carries detached signatures so it
can be verified without trusting either party.

**How to reach it.** Export a tenant; import into a fresh one to restore. A
restored consumer stands at the head of the restored feed.

**What you would otherwise write.** An export job exercised only when somebody
leaves, written against a schema that has since moved.

## Meeting the regulation

**What it is.** The store's concepts are the ones European data-protection law
— the GDPR among them — asks of any system holding personal data — purpose, custody, declared handling,
history, tenancy, erasure. Each obligation has a **mechanism** rather than a
procedure somebody performs.

| what the regulation asks for | what answers it here |
|---|---|
| a purpose for each processing of personal data | access is granted to a **step**, and the step is the purpose. A run context reaches the documents its run named and nothing else; the tenant's general surface is still open to a credential the deployment holds, so this answers for what comes through a step and not yet for everything |
| records of processing | the **trail**: who read what, on whose authority, as append-only records in the tenant's own store |
| right of access | the person's records, read through the ordinary surface |
| data portability | a **store-independent export**, hash-verified, importable elsewhere |
| right to erasure | destroying the person's **key**, which reaches copies nobody can recall, replayed on every restore |
| restriction of processing | a flag in the vault, honoured by the machinery |
| protection by design and by default | properties rather than policies: the operator **cannot** read what it hosts |
| a processor that does not read the data | provisioning, backup, restore and upgrade all work without the key |
| jurisdictional variation | a **zone**: a country's rules are records, not a release |

**How to reach it.** Set `pdi` on the tenant; declare steps for the work that
touches personal data; let the trail be written by performing that work rather
than by calling an audit function.

**What you would otherwise write.** A consent or purpose table, an audit writer
on every path, an erasure script with a list of every system holding a copy, an
export job, and the argument about whether an administrator could have edited
the log.

**What this is not.** Compliance is a property of a deployment and of the
organisation running it — its contracts, its retention decisions, its staff.
The store does not make anybody compliant, and nothing here should be read as
saying it does. What it removes is the part that is otherwise per-application
code, and that would otherwise be somebody's good intention rather than a
property of the system.

## Running it

**What it is.** One PostgreSQL database per tenant and nothing else to
operate — no broker, no cache tier, no search cluster. The whole store boots
inside a host application's JVM, so a consumer's tests run against the real
store rather than a mock. A tenant's runtime state is answerable: serving,
coming up, or failed, which is the state a list of served tenants omits.

→ [running it](arc42-008-crosscutting/running-it/README.md)

---

## What is not there

Stated because an absence found late is worse than one read early, and because
a capability that is *specified* is not one to build on.

- **Subscription delivery does not run.** The engine exists and is tested;
  nothing mounts it, and no tenant has ever delivered a notification. Use the
  feed.
- **Search beyond tier one** is deliberately unclaimed. Ask the capability
  statement rather than assuming a parameter works.
- **Blob storage** is not built; binary content has no home yet.
- **Routing and horizontal scale** across nodes are specified, not built.
- **A shared-schema tenancy tier** is specified, not built; today a tenant is
  always its own database.

The current answer is always
[the requirement catalogue](arc42-006-runtime/req-catalogue.md): a promise
reads PROVEN only when a test cites it.

<!-- skill: dbo-using -->
```yaml
name: dbo-using
applies-when: >-
  Building or reviewing an application on the DBO store, when work would add
  persistence, users or roles, an audit log, a job queue, encryption, export
  or import, terminology, validation, change notification, or a
  data-protection answer the store may already provide.
reference: docs/using-dbo.md
```
**Reference**
- **A tenant is a database.** Declare a spec; the runtime provisions and
  serves it at `/t/<code>/fhir`, with its own authority at `/t/<code>/oidc`.
  There is no cross-tenant surface, so there is no tenant predicate to add to
  a query and none to forget.
- **Authority comes from the tenant's own records.** An active role record is
  the grant; revoking is ending a period on it. Do not build a second user
  directory — the drift is discovered when somebody who left still has access.
- **Access is granted to a step, not to a person.** Work is processes, steps
  and runs; performing a step is what records why data was reached. A job
  table with a worker loop is this, rebuilt without the proof.
- **Nobody is pushed.** Participants pull work over a lane and report back, so
  a participant behind a firewall needs no inbound hole.
- **One feed primitive** serves paging, subscription, replication and
  catch-up. A consumer is a name and a position; change events are committed
  with the write. Do not add a notification table or a broker.
- **Records keep every version**, linked, so an edited history fails
  verification. Payloads carry a version and convert on read — not by
  migration script.
- **Identity is enforced at the write.** A type declares what identifies it;
  a second claimant is refused rather than merged.
- **Search is declared and strict.** Tier one only: `:exact`, `:missing`,
  `:not`, `:identifier`, `system|code`, one-level chains, `_lastUpdated`,
  `_summary=count`, `_elements`, `_include`, conditional create. An
  unrecognised parameter is refused, never answered more broadly.
- **Validation comes from records.** Structures, profiles, value sets and
  search parameters stream to the tenant over its face and are expanded into
  rows. Write a profile rather than field checks in application code.
- **Terminology is rows.** `$lookup`, `$validate-code`, `$expand`. A code from
  a system the tenant does not hold is unresolvable, which is not invalid.
- **Personal data is encrypted inside the payload** with the person's own key
  when a tenant sets `pdi`. History, feeds, archives and replication carry
  ciphertext by construction. Do not add field-level encryption above it.
- **Erasure destroys the key**, so it reaches copies nobody can recall, and is
  replayed on restore. Do not write a cascade delete.
- **The trail is records**, append-only against everyone. Contribute what
  happened; who and when are stamped from the credential. Do not write an
  audit table.
- **A jurisdiction is a tenant.** Brokers, identifier systems and terminology
  come from a zone over the ordinary chain, not from a config table or a
  release.
- **Configuration is applied as a run** that accounts for what it read,
  applied and skipped, per scope — so a set is exercised in one place before
  another.
- **Backup is export and restore is import**, one sealed archive, verifiable
  without trusting either party.
- **Processes → steps → runs.** A step declares what it consumes, produces and
  who may perform it; access is granted to the step, so performing one records
  why the data was reached. A job table with a worker loop is this, rebuilt
  without the proof.
- **Audit is records, not logging.** Contribute what happened; who and when are
  stamped from the credential. Append-only against everyone, the vendor
  included. Do not write an audit table or an audit writer per path.
- **Authentication is shared; authorisation never is.** The store authenticates
  nobody in production; each tenant resolves the verified person in its own
  records and mints its own token, and a token from one tenant is unintelligible
  at another. Do not build a cross-tenant session.
- **SCIM 2.0 provisions people** at `/t/<code>/scim/v2` — a `User` lands as a
  Person with a linked practitioner capacity, `active=false` deactivates, ETags
  are honoured. Do not write an identity-provider synchronisation job.
- **Each regulatory obligation has a mechanism.** Purpose is the step; records
  of processing are the trail; erasure destroys the key; portability is the
  ordinary export; jurisdiction is a zone. Do not implement a data-protection
  checklist per application — and do not read this as a compliance claim: it
  says where the mechanism is, not anything about a deployment.
- **MUST check the requirement catalogue before relying on a capability**:
  `docs/arc42-006-runtime/req-catalogue.md` is generated and a promise reads
  PROVEN only when a test cites it. Subscription delivery, tier-2 search,
  blob storage, cross-node routing and a shared-schema tier are not built.
<!-- /skill -->
