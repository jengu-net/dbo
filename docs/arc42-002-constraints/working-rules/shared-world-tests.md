# Testing against the shared world

An integration test belongs in one of two worlds, and the choice is made
deliberately.

## Which world

**The shared world**, driven over HTTP by `guide/`: one set of tenants
brought up once, every step running against it. A test belongs here when
what it proves is reachable through a face: a write, a read, a search, a
refusal, a token, a run, a provisioning call, an entry in the trail. The
question that decides it: could a reader do this with curl?

**A world of its own**, in the harness. A test belongs here when it reaches
for something a face does not expose (the store's own API, the database, the
server log, the OSGi container) or needs a situation no other test may see:
a tampered row, a first boot, a tenant held out of service.

## Preconditions

A precondition is arranged with the tools a reader would use. A tenant is
declared by writing its spec where the deployment reads specs. A credential
comes from the tenant's token endpoint. A record is written through the
face. Nothing is inserted into the database to arrange a situation, because
a test whose precondition was placed behind the surface proves the store
handles a state it cannot itself produce.

The world's shape is shared cost. A tenant added to it is paid by every run
of every test, so a test that does not fit what is declared stays where it
is.

## Assertions

One action, then everything that action settles: what a write returns, what
it leaves behind, what it makes findable, and the audit entry it emits. The
entry is asserted beside the action, in the same step.

Only situations that can really arise. A refusal nobody would provoke is a
test that fails one day for a reason nobody can act on.

Four rules for a step in a world other steps share.

- **Read the state you depend on; never count the writes above you.** A
  record is on whatever version earlier stories left it on.
- **A step belongs in the story that creates what it reads.** Grouping by
  subject puts a read before the write it needs.
- **Assert the request happened before reading anything into the answer.** A
  step that shells out can fail to run at all, and an assertion that
  something is absent is then satisfied by nothing having happened.
- **Do not assert what the tenant's own configuration takes away.** Behind
  the membrane, identifying elements are sealed out of a payload and
  reassembled on the way out; their order is not the author's to keep.

## Moving a promise onto a shared step

Claim a promise where it is proven. A step that acknowledges an answer does
not prove a promise about what comes afterwards; if the citation needs an
assertion the step does not make, add the assertion to that step. Re-record
the catalogue ([recorded projections](recorded-projections.md)), read the
promise's row, and confirm the new site is listed. Only then delete the old
test, and only when everything it asserted is asserted somewhere.

<!-- skill: dbo-shared-world-tests -->
```yaml
name: dbo-shared-world-tests
applies-when: >-
  Writing an integration test, deciding where one belongs, moving a promise
  onto a shared-world step, or deleting a test that builds a world of its
  own.
reference: docs/arc42-002-constraints/working-rules/shared-world-tests.md
```
**Rules**
- MUST put a test in the shared world when what it proves is reachable over
  HTTP, and in a world of its own when it reaches for the store's API, the
  database, the server log or the container.
- MUST arrange every precondition with the tools a reader would use: a spec
  file, the tenant's token endpoint, a write through the face.
- MUST assert everything one action settles beside that action, including
  its audit entry.
- MUST read the state a step depends on, put a step in the story that
  creates what it reads, and assert that a shelled-out request ran before
  reading its answer.
- MUST NOT grow the shared world to fit one test.
- MUST claim a promise where it is proven, re-record the catalogue, and
  confirm the new site is listed before deleting the test it came from.
<!-- /skill -->
