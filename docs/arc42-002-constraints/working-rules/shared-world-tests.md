# Testing against a shared world

Bringing up a world is the expensive part of the suite. A runtime costs
about thirty-four seconds; a tenant on a runtime already up costs about
three and a half; the guide's six-tenant world costs minutes and is paid
once. An integration test takes the cheapest world that can hold what it
proves, and the choice is made deliberately, down a ladder.

## The ladder

1. **A guide step.** `guide/` drives the six tenants of the sample world
   over HTTP, in the order a reader meets them, and each step can cite a
   promise. A test belongs here when what it proves is reachable through a
   face and reads as something a reader would do: a write, a read, a search,
   a refusal, a token, a run, a provisioning call, an entry in the trail.
   The question: could a reader do this with curl?
2. **A shared tenant.** `SharedTenants` in the harness holds one runtime for
   the whole suite and hands out tenants keyed by shape: a face version, a
   type set, an identity class, isolation on or off. A test belongs here when
   it needs the store's own API, the facade, the feed or the database of a
   tenant, and no shape it needs is about the tenant itself. A second or
   third tenant of one shape exists for tests about two tenants not seeing
   each other.
3. **A private tenant on the shared runtime.** When a test needs a shape no
   other test shares, or a tenant nothing else has written to, it declares a
   tenant of its own on the shared runtime under a code only it uses.
4. **A world of its own.** A test builds its own runtime only when it is
   about the tenant coming up, going down or being held out of service;
   when it needs the OSGi container or the distribution; when it tampers
   with what the store holds behind its back; when it needs a runtime
   nothing has touched; when it runs a deployment-wide sweep, which on a
   shared runtime would visit every tenant and count them all; or when its
   assertion is about a whole plane rather than about its own tenant, which
   on a shared runtime would be a claim about every other class's work.

`config/worlds-ledger.txt` records every harness class on the fourth rung
with its reason. [How the move down the ladder is run](../../arc42-011-risks-and-technical-debt/003-tests-move-down-the-ladder/how-it-is-run.md)
sits with the item that tracks it. A new class without a reason fails the build, and the
number that predate the ledger only falls.

## The guide runs against the tree

The guide's published compose file names a pinned image, and `:guide:test`
runs against it to prove the published commands. A promise claimed on a
guide step is proven by `./verify`, which builds a server from the tree and
runs the same suite against it. `docs/guide/examples/guide-on-tree.sh` is
that run on its own.

## Preconditions

A precondition is arranged with the tools a reader would use. A tenant is
declared by writing its spec where the deployment reads specs. A credential
comes from the tenant's token endpoint. A record is written through the
face. Nothing is inserted into the database to arrange a situation, because
a test whose precondition was placed behind the surface proves the store
handles a state it cannot itself produce.

A world's shape is shared cost. A tenant added to the guide world is paid
by every run; a shape added to the shared tenants is paid by every run that
touches it. A test that does not fit what is declared takes a private
tenant.

## Assertions in a shared world

One action, then everything that action settles: what a write returns, what
it leaves behind, what it makes findable, and the audit entry it emits. The
entry is asserted beside the action, in the same step.

Only situations that can really arise. A refusal nobody would provoke is a
test that fails one day for a reason nobody can act on.

- **Never count.** A shared tenant holds what every other test put in it.
  "Exactly one X" and "this type is empty" pass or fail on ordering. Scope
  every assertion to the record, run or code the test itself made.
- **Name what is claimed by identity.** A step name, an identifier value, a
  client id: each carries the test's own name, so two tests cannot claim
  one.
- **Read the state you depend on; never count the writes above you.** A
  record is on whatever version earlier steps left it on.
- **A step belongs in the story that creates what it reads.** Grouping by
  subject puts a read before the write it needs.
- **Assert the request happened before reading anything into the answer.** A
  step that shells out can fail to run at all, and an assertion that
  something is absent is then satisfied by nothing having happened.
- **Do not assert what the tenant's own configuration takes away.** Behind
  the membrane, identifying elements are sealed out of a payload and
  reassembled on the way out; their order is not the author's to keep.

`ATenantCodeBelongsToOneClassTest` refuses a tenant code two classes
declare, because both would address one database on the shared server.

## Moving a test down the ladder

Claim a promise where it is proven. A step that acknowledges an answer does
not prove a promise about what comes afterwards; if the citation needs an
assertion the step does not make, add the assertion to that step. Re-record
the catalogue and the worlds ledger
([recorded projections](recorded-projections.md)), read the promise's row,
and confirm the new site is listed. Only then delete the old test, and only
when everything it asserted is asserted somewhere.

<!-- skill: dbo-shared-world-tests -->
```yaml
name: dbo-shared-world-tests
applies-when: >-
  Writing an integration test, deciding which world it runs in, moving a
  test onto a shared world, or deleting a test that builds a world of its
  own.
reference: docs/arc42-002-constraints/working-rules/shared-world-tests.md
```
**Rules**
- MUST take the cheapest world that holds the proof, in this order: a guide
  step when a reader could do it with curl; a `SharedTenants` shape when the
  test needs the store's API, facade, feed or database; a private tenant on
  the shared runtime when no shape fits; a runtime of its own only for
  lifecycle, the container, tampering, a first boot or a deployment-wide
  sweep.
- MUST give a class that builds its own runtime one of those five reasons in
  `config/worlds-ledger.txt`, re-recorded with `./gradlew
  :core:harness:worldsLedger`.
- MUST arrange every precondition with the tools a reader would use: a spec
  file, the tenant's token endpoint, a write through the face.
- MUST scope every assertion in a shared world to what the test itself
  made, and give anything claimed by identity the test's own name. Never
  count.
- MUST assert everything one action settles beside that action, including
  its audit entry, and assert that a shelled-out request ran before reading
  its answer.
- MUST NOT add a tenant to the guide world or a shape to the shared tenants
  for one test.
- MUST claim a promise where it is proven, re-record the catalogue, and
  confirm the new site is listed before deleting the test it came from.
<!-- /skill -->
