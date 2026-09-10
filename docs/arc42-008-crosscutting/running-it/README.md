# Running it (§11)

## What operating this actually involves

A store several parties depend on has to be run by somebody, and that somebody
is usually not allowed to read what is in it. So the operational questions are
not only the usual ones:

- how does a developer get the whole store running inside a test?
- where does a tenant's traffic land, and who is allowed to write?
- where does durable work state live when work crosses a boundary?
- how is it backed up by an operator who cannot read the backup?
- how is it upgraded without a window in which it is not serving?

This describes the answers. They have one thing in common: **the operator is
given as little privilege as the job allows**, and the arrangements below are
mostly consequences of that.

## It boots inside a host, not only as a deployment

**The engine is plain Java.** No application framework anywhere in it. That is
not asceticism — it is what makes the next sentence possible.

**A host application can boot the whole store inside its own JVM**, sharing only
a small container runtime and its API. So a consumer's test suite runs against
the real store rather than against a mock of it, and the difference between
"works in the embedded test" and "works in production" is deployment topology
rather than a different implementation.

**Cold start is fast enough to be used that way**: schema setup detects that the
schema is already current instead of replaying a changelog to find out. A store
that took thirty seconds to start would be a store consumers mocked, and a
mocked store is one whose real behaviour is discovered late.

### Inside somebody else's OSGi framework, the host owns logging

The distribution installs one logging arrangement: slf4j-api as a shared
bundle, `dbo-logging` as the binding behind it, SPI-Fly mediating the
ServiceLoader lookup. That is the arrangement for a framework this store
stands up itself, where nothing else provides slf4j.

**A host that already provides slf4j keeps its own, and installs neither of
ours.** Two providers of `org.slf4j` in one framework is not a posture, it is a
race decided by version comparison, and the loser is not the one that fails —
it is the one whose binding is not behind the winner. The bundles then resolve,
components activate, work proceeds, and every line written inside the framework
goes to a facade with nothing behind it. That state is not quiet, it is
inaudible, and from outside the two are identical.

**What such a host has to export is the whole slf4j API**, which is four
packages and not one: `org.slf4j`, `org.slf4j.event`, `org.slf4j.spi` and
`org.slf4j.helpers`. Withholding the last two to keep a binding private does
not do that and cannot — the binding is a *service*, found through
`ServiceLoader`, so hiding the package hides nothing. What it does instead is
break the class space: `Logger` itself references three classes in
`org.slf4j.spi`, because `atInfo()` and its siblings return a builder that
lives there, and `LoggerFactory` references six in `org.slf4j.helpers`. An
API-only export is not a smaller slf4j. It is one that throws at the first
fluent call.

So the store's bundles import all four, by range, and resolve against any host
that exports a coherent slf4j 2.x. The ranges are the part that makes a
mismatch legible: an unversioned import wires to whatever is there, which is
how a second slf4j goes unnoticed for as long as nobody misses the logs.

## How a deployment is shaped

**Requests enter at the closest public node and are then routed to the pod
serving that tenant.** Two hops, each doing one thing: the first is ordinary
network locality, the second is tenant assignment.

**A tenant's serving pod is its single writer.** This is the load-bearing
decision. Because exactly one pod writes a given tenant, local caching and local
subscription state are **correct by construction** rather than by an
invalidation protocol — there is no second writer whose changes this pod would
need to hear about.

**The assignment is durable state with version-driven takeover**, so a pod
failing over is a recorded transition rather than a race between two pods that
both believe they hold the tenant.

**Callers look a tenant's service up in a registry**, and whether they get a
local instance or a proxy to a remote one is indistinguishable. Code that works
in the embedded single-pod case works in the distributed one unchanged.

The cumulative result is worth stating plainly: **no shared-state service is
required.** No cache cluster, no coordination service. Single-writer plus
durable assignment removes the need rather than satisfying it.

## Where durable work state lives

Durable tasks, streams and communication between instances run **on the store's
own database** — no external broker to run, secure and reason about during an
incident.

Workflow state then splits by what it carries, and every step **declares which
plane it belongs to at definition time**:

- the **platform plane** coordinates: which tenant, which step, what happened.
  Its parameters and checkpoints **never contain resource content or
  credentials**.
- the **tenant plane** holds anything that does carry content, inside the
  tenant it belongs to.

Declaring the plane per step rather than inferring it is what makes the
content-free promise checkable instead of aspirational.

**Every hop across a boundary is coordinated by the platform. There is no
direct tenant-to-tenant connection.** That looks like indirection and is
isolation: two tenants that could connect directly would have a channel nobody
else can see. Instead each hop produces three records — the sender's egress,
the receiver's ingress, and the coordination between them — so **audit of
cross-boundary movement is structural rather than something each integration
implements**.

And a hop grant can only be issued for a hop **the declared process shape
actually contains**, so a step that is not in any declared process has no path
across a boundary at all.

## Backup is export, and restore is import

One mechanism rather than four: **the thing you archive is the thing you can
load.** There is no separate backup format to maintain, verify, or discover has
bit-rotted on the day you need it.

The consequence is the argument: because the import path is the same one used
for tenant moves, development seeding and taking on somebody's existing data,
**every backup is implicitly restore-tested by daily use**. A restore path
exercised only during disasters is a restore path nobody has confidence in.

An archive has two elements, each on demand:

- **Current state, portable.** The current version of every record, as
  idempotent upserts. Importing it into the same tenant is a no-op, into a fresh
  tenant a full restore, and **into somebody else's store of the same standard,
  a migration.** That last one is deliberate: portability is the anti-lock-in
  promise applied to ourselves, and a store that cannot export itself into a
  competitor is asking for trust it has not earned.
- **History, high fidelity.** Full version history, the audit trail and consumer
  positions, as a schema-scoped database dump — which is clean because the
  tenant's database separates current state, history and work state into their
  own schemas. A state-only restore is a valid tenant with fresh history; a
  state-and-history restore is byte-faithful.

The state element is cut at **a single consistent snapshot**, and an incremental
export is simply [the feed](../change-and-who-is-listening/README.md) from that snapshot's
cursor — the same primitive again, doing a fourth job.

## The operator runs backups it cannot read

The archive is encrypted with a key held by the tenant's owner. The store
encrypts with a data key and wraps that key with the owner's, so **restore
requires the owner's participation**, a stolen archive is ciphertext, and a
backup can never quietly become an operator-readable copy of a tenant.

**An archive carries two detached signatures** — the vendor's and the tenant's —
and the tenant countersigns without resealing, so **neither party can produce an
attestation alone**. That matters for the same reason the exchange itself does:
an attestation one party could manufacture is not evidence.

## Reshape, the third operation

Backup and restore move a tenant; **reshape moves a tenant's data forward in
place**, and it belongs beside them because it is machinery an operator runs
against a live tenant rather than a project somebody schedules.

The division is the point: **the loop is the store's and the transformation is
the face's.** Paging, resumability, rate bounds, re-accepting and re-stamping,
and the accounting live in one place for every model — where a consumer running
the same loop over the API would rebuild all of it per runner, outside the store
that owns history and identity.

Whether a model can express its converters *as data* is a fact about the
model. FHIR can, so the FHIR face runs package-shipped maps in process; a model
that cannot uses the hand-back lane, and the same loop accounts for both.

Verification is the inventory it already reports: shape counts before and after,
diffed. A verification that read every record would be a second full copy of the
data, performed to check the first one.

## Upgrades ride the deployment

Schema and engine upgrades happen through **rolling deployment**: the
highest-version node leads and migrates, and older nodes passivate. There is no
separate migration step to run, and therefore no window during which the store
is down because somebody is running one.

## Binary content

Documents, audio and device backups are **not rows**. A store that keeps them
as rows charges for them in every backup it ever takes, which is the operational
cost that decides this rather than any preference about storage engines. So the
record and the bytes part company: what the store holds is the description and
a hash of the content, and the content itself lives in the tenant's own storage,
reached directly or through the store according to how the deployment is exposed.

That storage is **provisioned credential-blind** — the operator provisions it
without holding readable credentials, the same discipline as the databases — and
erasure by dropping the tenant's store extends there too. A small deployment
falls back to keeping the bytes in the database behind the same interface, so
the choice is a deployment decision rather than a different product.

## What this costs

**Single-writer per tenant means a tenant's write throughput is one pod's.**
Scale comes from more tenants across more pods, and from adding claimants to
work, rather than from several writers to one tenant. That ceiling is a
deliberate trade for correctness that needs no coordination.

**Owner-held keys mean the operator cannot rescue a tenant that has lost its
key.** Recovery arrangements — escrow, co-owner quorum — are the owner's choice
to make in advance, and an operator who could quietly restore without them would
be an operator who could read the archive.

**Two-element archives are two things to schedule.** A deployment that takes only
the portable element has a valid restore and no history, which is a legitimate
choice and needs to be a deliberate one.

## Where the detail is written down

- **The exact rules and their proofs** — the container, scaling, workflow,
  maintenance and operations entries in the
  [REQ catalogue](../../arc42-006-runtime/req-catalogue.md).
- **What a reshape is converting between** — [records you can rely
  on](../records-you-can-rely-on/README.md).
- **Why an incremental export is a feed** — [change, and who is
  listening](../change-and-who-is-listening/README.md).
- **Why the operator holds no readable credentials** — [data
  isolation](../data-isolation/README.md).
- **What work is, and who performs it** — [processes and
  work](../processes-and-work/README.md).
