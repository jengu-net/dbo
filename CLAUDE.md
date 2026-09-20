# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

DBO is a multi-tenant FHIR object store: an OSGi runtime over PostgreSQL that
serves many tenants, each with its own database, its own OIDC authority and
its own FHIR version. The specification is an arc42 tree under `docs/`.
[docs/README.md](docs/README.md) is its index and decodes the `§` numbers
that code comments cite.

## Before writing code

Behaviour is promised in [the REQ catalogue](docs/arc42-006-runtime/req-catalogue.md)
and proven by a test. Adding behaviour means claiming or adding a REQ; fixing
a defect means adding the test that would have caught it.

[docs/plans/implementation-status.md](docs/plans/implementation-status.md)
says what is built and what is only specified. Believe it over the impression
the code gives. Its test counts are typed by hand; the catalogue is the
checked answer to what is proven.

The rules a green build cannot enforce are in
[working rules](docs/arc42-002-constraints/working-rules/README.md), one
document per rule, each projected into a skill in `tools/dbo-conventions/`.
A skill fires when the work that trips it begins; the document argues it.

Where each kind of documentation is written is a map in the
[documentation rule](docs/arc42-002-constraints/working-rules/documentation.md).
The tree is being moved to match it; the move is
[the first item in risks and technical debt](docs/arc42-011-risks-and-technical-debt/001-documentation-shape/README.md).

## The traps

<!-- rules:begin — generated from docs/arc42-002-constraints/working-rules/README.md; do not edit. Regenerate: ./gradlew generateSkills -->
**A green build proves little.** The characteristic defect here compiles,
resolves, publishes and dies on first use. Prove a change by exercising it:
validate a resource, convert one, ingest a CodeSystem, boot the container.
(`dbo-runtime-proof`)

**A toolset can be built, proven and unreachable.** A harness is the
container and constructs what it needs, so a type's own tests never show that
anything reaches it. Ask who constructs it outside a test, and where the
state it writes is registered. (`dbo-reachability`)

**Three container tests are the OSGi ratchet.** `EmbeddedContainerIT`,
`TenantOsgiIT` and `ServerDistIT` fail when a package resolves and dies on
first use. When one fails after a change, it is right. (`dbo-runtime-proof`)

**`dbo-core` has no dependencies**, and `dbo-postgres` only the JDBC driver.
Adding a library to either needs a reason that survives being read aloud.

**The R5 validator needs a 2g heap.** Test tasks set it; a new test task that
loads the validator must too.
<!-- rules:end -->

## Logging

One binding for the runtime: slf4j-api as a shared bundle, `dbo-logging` as
the provider, SPI-Fly mediating the ServiceLoader lookup. Default INFO,
default JSON, one line per event.

INFO is startup with its resolved posture, tenant lifecycle with a rollup,
shutdown, and things that went wrong. It is **never** per-request or per-item,
and never anything carrying identifying data: this store encrypts identifying
elements inside the payload, and a search URL carries `identifier=system|value`.
MDC is a no-op for the same reason.

## Publishing

Artifacts go to the project's own public Maven repository. Maven Central is
configured and unused, blocked on artifact size; see
[RELEASING.md](RELEASING.md). Images go to GHCR always, and to the fleet's
own registry when the repository variable `ARTIFACT_HOST` names one; while
it is unset the jar publish is skipped.

## Conventions

Commit messages explain why. Comments explain the constraint, and only the
constraint. Documentation describes the current state.

`.github/scripts/check-branding.sh` is a ratchet. `jengu` is allowed only as
a deliberate coordinate. An issue number or a decision-record citation is
refused anywhere in the code or the specification tree, with two exceptions:
`docs/tasks/`, and a `TODO` or `FIXME` naming the issue that holds the work.
Run the check before committing. The rest is the `dbo-comments` skill.
