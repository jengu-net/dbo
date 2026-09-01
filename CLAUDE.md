# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

DBO is a multi-tenant FHIR object store: an OSGi runtime over PostgreSQL that
serves many tenants, each with its own database, its own OIDC authority and
its own FHIR version. The specification is an arc42 tree under `docs/`, and
its section numbers (`§1`–`§17`) are referenced from code comments — those
references resolve, and are expected to keep resolving.

Read [docs/README.md](docs/README.md) first; it is the index and it explains
the `§` numbering.

## Before writing code

Behaviour is promised in [the REQ catalogue](docs/arc42-006-runtime/req-catalogue.md)
and proven by a test. Adding behaviour means claiming or adding a REQ; fixing
a defect means adding the test that would have caught it. Test names are
sentences about behaviour — `aRestoredConsumerStandsAtTheHeadOfTheFeed`.

[docs/plans/implementation-status.md](docs/plans/implementation-status.md) is
what is built and what is only specified. It is the honest one; believe it
over any impression the code gives.

## The traps, in order of how often they bite

<!-- rules:begin — generated from docs/arc42-002-constraints/working-rules.md; do not edit. Regenerate: ./gradlew generateSkills -->
**Failures here are runtime failures.** This codebase's characteristic bug
compiles, resolves, publishes, and then dies on first use. It has happened
through hand-written `Import-Package` lists, through a lazily-reached jar
excluded from a bundle, and through slf4j binding itself into silence. A green
build proves very little. Prove changes by *exercising* — validate a resource,
convert one, ingest a CodeSystem, boot the container — never by compiling.

**A toolset can be built, proven and unreachable, and the tests will not say
so.** It has happened four times in the participation work alone: a lane, a
replication toolset, a type registered for no tenant, and a normalisation
nothing outside the container could call. Every one passed its own tests,
because a harness *is* the container and constructs whatever it needs. Two
questions catch it, and they have to be asked deliberately because nothing
fails: **who constructs this outside a test** — `new X(` matching nothing in
production sources is the whole signal — and **where does its own state live**,
which is cheaper to check and easier to miss, since a surface can be mounted
and correct while the type it writes is registered for no tenant.

**The fat bundles hand-write `Import-Package`.** A newly referenced sibling
package resolves at build and throws `NoClassDefFoundError` at runtime.
`EmbeddedContainerIT` is the ratchet; when it fails after your change it is
telling the truth. Two in-JVM containers exist (`EmbeddedContainerIT`,
`TenantOsgiIT`) and both must install what the distribution installs.

**The R5 validator needs heap.** It loads the FHIR core package eagerly and on
a default heap dies as `HAPI-2330` with a null message, three frames above an
`OutOfMemoryError` nobody sees. Test tasks set `maxHeapSize = "2g"`.

**`dbo-core` has no dependencies**, and `dbo-postgres` only the JDBC driver.
Adding a library to either needs a reason that survives being read aloud.
<!-- rules:end -->

The same document states these as installable skills, along with the rules for
comments and for claiming a behaviour:
[working rules](docs/arc42-002-constraints/working-rules.md).

## Logging

One binding for the runtime: slf4j-api as a shared bundle, `dbo-logging` as
the provider, SPI-Fly mediating the ServiceLoader lookup. Default INFO,
default JSON, one line per event.

INFO is startup with its resolved posture, tenant lifecycle with a rollup,
shutdown, and things that went wrong. It is **never** per-request or per-item,
and never anything carrying identifying data: this store encrypts identifying
elements inside the payload, and a search URL carries `identifier=system|value`
— writing that to a log undoes §14. MDC is a no-op for the same reason.

## Publishing

Artifacts go to the project's own public Maven repository. Maven Central is
configured and unused, blocked on artifact size — see
[RELEASING.md](RELEASING.md). Images go to the fleet registry first and on
their own, because deployments update from it; public registries are a
best-effort second step.

`.github/scripts/check-branding.sh` is a ratchet: `jengu` is allowed only as a
deliberate coordinate, and issue references are forbidden because a reader of
this repository cannot open them. Run it before committing.

## Conventions

Commit messages explain why, not what. **Comments explain the constraint, and
only the constraint.** A tracker reference is not provenance a comment may
lean on: an issue is a moment, superseded by later ones, and its value is the
chronology rather than the state. If a sentence needs the ticket to make
sense, the sentence has not said what it means yet.

**Tracker and decision-record references are refused outright.**
`.github/scripts/check-branding.sh` rejects an issue number — bare, or with a
repository name glued to it — anywhere in the code or the specification tree,
and rejects decision-record citations the same way and for the same reason.
Both are moment-bound: they are superseded, they die when a tracker moves, and
a reader of this repository often cannot open one at all. The check is literal
enough to catch a spelt-out example in prose, this paragraph included, which is
why both shapes are described rather than shown.

Two exceptions, both because the text is about outstanding work rather than
about the store. `docs/tasks/` is exempt entirely: those documents exist to
carry a topic between its issues and its concepts, and they are deleted when
their issues close. And a **`TODO` or `FIXME` may name the issue holding the
work** — if one is filed, name it, since a reader who wants to know what
became of the gap has nowhere else to look. The check exempts the marker's
line and the five after it, so the reference can sit where it reads naturally
inside the comment rather than being forced into the marker.

Documentation describes the current state. The journey belongs in commit
messages.
