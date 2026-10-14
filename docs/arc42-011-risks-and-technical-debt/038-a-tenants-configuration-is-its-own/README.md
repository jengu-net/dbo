**Open, and started. A tenant owner's configuration is to come from a git
repository the owner keeps, applied commit by commit, with its secrets sealed
to keys the store reaches through Vault and its working key held where the
owner says. Nothing of it is built yet: the store applies only the
deployment's declarations, keeps no record of which file wrote which record,
holds no secret a step can reach, and wraps every tenant's keys under the one
deployment key.**

# A tenant's configuration is its own

Issue: [426](https://github.com/jengu-net/dbo/issues/426)

## Where it stands

`ConfigSource` and `ConfigApplication` exist and are type-blind: a source
hands over declarations with a marker, and a pass applies them as one sweep
run, withdrawing what a complete read no longer names. Today they read only
the deployment's tenant declarations and a face's vocabulary at bring-up. No
tenant declares a source of its own, and withdrawal is by type across the
whole store, because nothing records which declarer wrote which record.

`SealedArchive` seals an export under an owner key handed in per act.
`KeyProtector` and `PersonVault` wrap a tenant's signing keys and its people's
keys under the deployment's single key-encryption key. There is no
per-tenant working key, no secret a step can reach by name, and no key service
the store talks to.

## The sequence

1. **A git source and its changelists.** A tenant declares a repository on a
   path the deployment can read, and a default ref. The store reads it at a
   commit with JGit, from git's objects and never a working tree, and
   reconciles: what the commit declares, against what the store recorded as
   applied from it, file by file. The run names the ref asked for, the commit,
   its author and message, the identity that asked, and the counts. The door
   takes `{"ref": …}`; the preview is the same reconciliation, unwritten.
2. **Secrets.** A secret is a value in a SOPS file in the repository, its data
   key wrapped for the tenant's Vault Transit key and for the owner's own
   recovery key. The store reads the format itself and unwraps through
   Transit; a value may instead be a reference into Vault. Applied secrets
   are held by the tenant's authority under its working key, never as a
   record, and a step reaches only the ones it declares.
3. **The working key.** A per-tenant working key wraps the authority's signing
   keys and its people's keys. It is wrapped in turn by a key behind one
   port: the deployment key by default, a per-tenant Transit key in the
   provider's Vault, or a key the owner runs. It is held unwrapped for a
   bounded time and unwrapped again on a timer; a tenant whose key will not
   unwrap is sealed and stops serving. Existing tenants are rewrapped once.

Each is its own change, proven before the next begins.

## Decisions

**Reconcile, never replay.** A changelist computed between two commits breaks
when the commit last applied is gone — a force-push, a shallow clone — and
replays mistakes the store has since corrected. The store records what each
file applied, so an apply is the commit's state against the store's: a
rollback is an apply, drift is corrected, and the git diff is only what the
run shows.

**JGit, embedded.** It reads a bare repository at a commit without a checkout
and is published as an OSGi bundle. Shelling out to `git` puts a binary in the
image and its configuration, hooks and checkout behaviour in the trust path;
nothing else on the JVM reads git.

**SOPS, not an envelope of our own.** It is the format the GitOps tools
already read, encrypts per value so a diff stays legible, and carries a MAC
over the file. The owner keeps a recipient of their own and can recover
without the store. An owner key handed in per apply would have put a key in
every request's headers and a person at every apply.

**No owner layer of the specification.** The tenant's specification stays the
provider's, declared dynamically through the custom resource or the
management tenant. The platforms surveyed — Flux's multi-tenancy, Crossplane,
Capsule — let a tenant add configuration and never schema: a type or a step
changes what the provider runs and supports. An owner who needs one asks the
provider; if that proves too slow, the specification names what an owner may
enable, and the owner's configuration selects from it.

**The store talks to Vault itself; the provisioning service does not
mediate.** A service already holding the database superuser that could also
unwrap working keys could read every tenant. It creates each tenant's Transit
key, policy and role, under a policy that denies it decryption; the store
authenticates with Kubernetes auth, or JWT against an owner's own Vault, and
holds a policy scoped to that tenant's key.

**Say what revocation means.** A revoked owner key makes the tenant's data at
rest and in backups unreadable, and stops the tenant within the window the
working key is held for. It does not make a running node's memory unreadable
to whoever holds root on it. A per-tenant key in the provider's Vault is
revocable by agreement, since the provider's Vault can undo it; revocation
that holds against the provider needs the owner's own Vault or KMS, or a key
the provider's Vault cannot reach.

## Traps

- **A repository is input from outside.** Read blobs at a commit, never check
  out; refuse symlinks, submodules and LFS pointers; cap file size and count;
  watch `objects/info/alternates`, which may point outside the mount. A git
  author is whatever the committer typed, so the run also names who asked.
- **The face's vocabulary pass shares a sweep scope with the tenant's own.**
  A git source records its marker on a run of its own, or two sources
  overwrite each other's.
- **Withdrawal by type is wrong for a source.** It removes every
  configuration-owned record of the type, whoever declared it; a git source
  withdraws only what its files applied.

## Not done here

Fetching, pushing and credentials for remotes; committing edits; a layout for
the repository's directories beyond what a file's own contents say; the owner
layer of the specification.

## Proof

Each step is proven by a leg in the harness through the tenant's door, on r5,
with a negative per leg. Run
`./gradlew --no-daemon :core:harness:test --tests '*ATenantsConfigurationIsItsOwnIT'`.
