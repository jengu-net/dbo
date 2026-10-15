---
title: A tenant opens
eyebrow: Guide
standfirst: >-
  A clinic is a file the application's store is watching. It comes up with a
  database and an authority of its own, the identity provider fills its staff
  directory, and what a clinician may reach is a grant the application
  declares.
template: essay.html
---

Ines builds the platform a clinic group runs on. She does not run a database
team, she does not want to operate a second identity system, and she is not
going to write a tenant boundary of her own. Kevadkliinik is the first clinic to
go live, and Sügiskliinik follows on the same running store: the second must
cost a declaration, not a deployment. The scene is
[US-DBO-TENANT-OPENING](../arc42-003-context/user-stories/us-dbo-tenant-opening.md),
walked by
[`ATenantOpensAndItsPeopleGetInIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/ATenantOpensAndItsPeopleGetInIT.java),
where each clinic's code carries the story's name and a mark for the run.

## The store is a dependency

The application is a `@SpringBootApplication` that asks for a store:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/ServerApplication.java:application"
```

What makes it serve tenants is `dbo-spring-boot-server` on its classpath,
`@EnableDboServer` on the class, and `dbo.tenants.directory` in its
`application.yaml`
([quick start](quick-start.md#embedded-one-jvm)). The directory is watched: a
file appearing there is a tenant coming up, and a file leaving is a tenant
retracted. A tenant is added by writing a file, not by a release.

## A declaration is the whole of opening a clinic

This is a tenant, complete — the hospital in the sample world:

```json
--8<-- "samples/sample-world/tenants/hogwarts.json"
```

It names the standard it speaks (`face`), the zone whose rules it takes, the
types it holds and, for each, what identifies one and how it is handled, and
the steps its work is made of. Nothing in it is a hint; the store enforces all
of it. The story opens Kevadkliinik with a smaller one, handed to the same
scan a file in the directory is, which applies it on its own clock:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/ATenantOpensAndItsPeopleGetInIT.java:declare"
```

There is no migration to run, no schema to apply and no restart. What the
clinic gets underneath is a database of its own rather than a slice of a shared
one, and the credential that made it never passes through code you wrote.

## Doing something as a tenant opens

--8<-- "assets/diagrams/a-tenants-life.svg"

The application is told when a tenant reaches a point in its life, by a bean
annotated `@DboTenantListener`. The simplest notices it:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/NoticingATenant.java:noticing"
```

A listener is told once, at the point it named, and suits what is rare and can
be derived again if it is missed. Anything that must not miss an event is an
observer instead ([care is recorded](care-is-recorded.md#watching-the-work)).

`target` narrows which tenants a listener hears about, by facts the tenant
publishes. This one declares what Kevadkliinik's people may do as soon as the
clinic serves, in every clinic that keeps a staff directory:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/OpeningAClinic.java:opening"
```

A clinician's reach is a role grant held as a record in the clinic's own store,
not code the application ships. Declaring it at every start is the ordinary
case: a bring-up that refused a grant it already had would make every redeploy
a migration. The application is a client of each clinic too, with the one scope
its screens need.

### Selecting tenants

A listener and an observer take the same filter, over facts a tenant publishes
under `dbo.tenant.`: its code, face, zone and kind, whether it has steps, SCIM,
an authority, a vault. A filter naming a fact that does not exist is refused
where it is registered, because `dbo.tenant.hasVualt` would otherwise match
nothing, for ever, in silence.

The store's own activities filter on resolved facts and never on a kind. A
filter on a resolved fact keeps answering correctly for a kind of tenant nobody
anticipated, while a filter on a label has to be found and edited first. A
kind is for what selects tenants coarsely from outside, such as billing, a hook
for a sort of tenant, or a policy per kind:

```json
{ "code": "clinic", "face": "r5", "kind": "clinic.small" }
```

A tenant that says nothing is `dbo.tenant`. A face root is `dbo.face`, and a
projection the store makes is `dbo.projection`. Any name outside `dbo.` is
free and decides nothing in the store. A `dbo.` kind the store does not know is
refused by name, and so is a kind that disagrees with what the declaration
makes the tenant. The kind is published as `dbo.tenant.kind`.

A listener that throws is reported by name and cannot stop the tenant serving.
Nor does it work out its own applicability: where it applies is the filter, so
the answer lives in one place.

### Enrolling the worker

The same kind of listener issues the worker its credential in each tenant it
performs for:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/EnrollingTheWorker.java:reached"
```

## What the store guarantees

- **Nothing answers until the clinic's own authority says so.** An anonymous
  request is refused with 401 rather than 404: a mounted, guarded surface and
  an absent one are different facts.
- **The token comes from the clinic's own issuer**, is checked where it is
  presented without a call to anybody, and its scope is the whole of what it
  reaches.
- **The boundary is structural.** Kevadkliinik's token presented to
  Sügiskliinik is 401, not 403: the second clinic's authority has never heard
  of that issuer, so the token is noise rather than a credential being refused.
- **The identity provider fills the directory over SCIM**, with a credential
  that reaches the directory and nothing else. What lands is the person, with a
  practitioner capacity linked to them; groups are read and never written. A
  tenant that declares SCIM without personal-data isolation does not come up.
- **A zone that names no identity broker is its own**, so declaring Rowling
  Land for its rules did not oblige anybody to stand up an identity provider.

Each of these is a leg of the story, and its
[joins table](../arc42-003-context/user-stories/us-dbo-tenant-opening.md#joins)
names the promise and the test that proves it.

## The owner's configuration, from their repository

The declaration is the provider's. What the clinic is made of (its wards, its
value sets) can be the owner's, kept in a git repository the owner commits to
and the deployment keeps a copy of on a path it can read:

```json
{ "code": "clinic", "face": "r5",
  "configuration": { "git": "/srv/config/clinic.git", "ref": "main" } }
```

Every JSON file outside a hidden directory is one record, and its
`resourceType` says which. The declared ref is applied when the tenant comes
up. A client holding the `configuration` scope applies any ref at the tenant's
door:

```http
POST /t/clinic/configuration
{"ref": "v2"}
```

An empty body applies the declared ref. `POST /t/clinic/configuration/preview`
with a ref lists the files that applying it would add, change or remove, and
writes nothing.

Each apply compares the commit with what each file applied last time:

- a file that has not changed is not read;
- a changed or new file is applied;
- whatever a file stopped declaring is withdrawn, and nothing another
  declarer wrote.

Applying an older ref is the same comparison, so a rollback is an apply. The
answer, and an act recorded under the configuration's run, name the ref, the
commit, its author and message, and the client that asked.

The store reads git's objects at the commit and never checks anything out. It
refuses a declaration that is a link or a submodule, a pointer to Large File
Storage, a repository that borrows objects from elsewhere, and a file larger
than 4 MB. It never fetches: keeping the copy current is the deployment's. A
record that names nothing it could be found by again, such as an organisation
with no identifier, is refused as a card naming its file, because it could
never be withdrawn.

### Secrets in the repository

A connection's password goes in the repository encrypted, in a file ending
`.sops.json` written by [SOPS](https://getsops.io). Each value is encrypted
under its name, and a MAC covers the file. The data key is wrapped by the
tenant's own key in the deployment's Vault Transit engine: `tenant-<code>`,
or the `key` the configuration names. The owner adds a recipient of their own,
such as an age key, so the file opens without the store:

```sh
sops --encrypt --hc-vault-transit https://vault.example/v1/transit/keys/tenant-clinic \
     --age age1... secrets/connections.json > secrets/connections.sops.json
```

Applying the file opens it through the deployment's Vault alone, whatever
Vault the file names, and only under the tenant's own key. Each top-level
string becomes a secret by its name, kept by the tenant's authority wrapped
and never as a record. A changed value replaces the old one, and a name the
repository stops declaring is forgotten. A file wrapped by another key is a
card naming the key it needed.

A deployment can also write such a file for the owner. A screen where somebody
types a connection's password posts it to the tenant's configuration door,
which answers with the whole file, ready to commit:

```http
POST /t/clinic/configuration/secrets
{"path": "secrets/connections/lab.sops.json", "secrets": {"connection.lab.password": "…"}}
```

The file is encrypted to the recipients the repository's `.sops.yaml` names for
its path, at the declared ref: the tenant's Transit key, and any age keys the
owner listed. With no rule, it is encrypted to the tenant's Transit key alone. A
rule naming a key type the deployment cannot use (PGP, a cloud KMS, key
groups), or another Transit key, is refused with what it named. The data key is
wrapped through Transit's encrypt endpoint only, and `SopsWriter` takes a
`VaultTransit` of its own, so a writer whose token may only encrypt writes a
file it cannot read. The owner's `sops` opens it as one they wrote themselves.

A step reaches a secret it declares, by name:

```json
{ "code": "clinic.lab.connect", "slots": { "organisation": "Organization" },
  "secrets": ["lab-connection-password"] }
```

```java
String password = work.secret("lab-connection-password");
```

A step may also declare a family by a name ending `.*`. `"connection.*"`
reaches every secret whose name extends `connection.`, at any depth, so an owner
adds a connection without the provider changing the declaration. It does not
reach `connection` itself or `connections.x`, and a `*` anywhere else is
refused when the tenant is declared. Each secret a run reaches is recorded on
the run's trail by its own name, never the family, with who reached it.

In process the value is handed over as it is. Over HTTP it travels only sealed
to the participant's enrolment key, and is opened with the key the participant
holds. A participant that enrolled no key is refused the run of a step
declaring secrets when it would take it, naming the step. A step that does not
declare the secret is refused by name.

A worker attached while its application runs holds keys when
`dbo.worker.identity.keys-dir` names where they are kept: adopted if present,
made if not. Every lane it attaches then holds them, and `DboWorker.publicKeys()`
gives the public halves its enrolment hands the tenant with its client. If the
keys are made again after a rebuild, the worker is refused (saying its client
must be ensured with the keys it now holds) until that is done.

The store logs into Vault as itself: Kubernetes auth when
`dbo.vault.kubernetes.role` is set, otherwise a token read from
`dbo.vault.token.file`. `dbo.vault.address` names the Vault, and
`dbo.vault.transit` the engine's mount (`transit` by default). Whatever
provisions tenants creates each tenant's key and needs no right to decrypt.

## The tenant's working key

Everything a tenant keeps secret (its signing keys, its secrets, its people's
keys) is wrapped under a working key of its own. That key is made once and
kept in the tenant's database only wrapped. By default the deployment's own
key wraps it. The declaration may name a Transit key instead, in the
deployment's Vault or in one the owner runs:

```json
{ "code": "clinic", "face": "r5",
  "workingKey": { "key": "owner-clinic", "vault": "https://vault.clinic.example",
                  "auth": { "mount": "jwt", "role": "dbo-clinic" } } }
```

Without `vault`, the key is in the deployment's Vault. With it, the store
presents its service account token to the owner's Vault under the role the
owner grants it, so the owner hands the deployment no secret.

The tenant comes up only once its working key unwraps. While it serves, a key
held in Vault is asked again every `dbo.tenant.workingkey.recheck` (15 minutes
by default). A key that will not unwrap takes the tenant down, and the tenant
does not come up while it stays that way. Each bring-up rewraps the working
key under the Transit key's latest version, so a rotation reaches it.
Declaring another key moves the working key under that one, and nothing it
wraps is touched.

What revoking the key means, said plainly: the tenant's data at rest and in
backups can no longer be read, and the tenant stops within the recheck time.
It does not make a running node's memory unreadable to whoever holds root on
it. A key in the deployment's own Vault can be revoked only by agreement, since
that Vault's operator can undo it; revocation that holds against the provider
needs the owner's own Vault or KMS.

A tenant that ran before it had a working key moves its keys under one the
first time it comes up, and keeps the index its people are looked up by.
Backups taken before that move still hold keys under the deployment's key.

## What the store cannot do yet

- **No shared tier.** Every tenant is a database. A clinic too small to justify
  one has nowhere cheaper to go.
- **No quotas.** Nothing bounds what one tenant can consume, so a busy clinic
  and a quiet one on the same node are isolated from each other by nothing but
  luck.
- **The records surface is not private yet.** The design says applications
  reach data through work and that the store's own records surface is never
  publicly routed. Today keeping it off a public route is the deployment's job.
