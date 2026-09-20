# Working rules

How work is done in this repository. [Promise](../promise.md) constrains how
a behaviour is stated and proven; these documents constrain everything else.

A rule is written here only when no test can enforce it. A rule a test can
check is a test. What remains is the set whose violation compiles, resolves,
publishes and is found later by a person.

## Stated once, and projected

Each rule is one document: an argument, then a **skill-block** stating it
imperatively. Two projections are generated from it and neither is
hand-edited:

- a skill per block, into `tools/dbo-conventions/`, so the rule arrives when
  the work that trips it begins;
- the trap section of `CLAUDE.md`, copied from the marked region below.

`./gradlew generateSkills` regenerates both; `verifySkillProjection` fails
the build when what is committed disagrees with its source.

## The rules

- [Proving a change](runtime-proof.md) — `dbo-runtime-proof`. A green build
  proves little; exercise the change.
- [Reachability](reachability.md) — `dbo-reachability`. A type's tests prove
  it works, never that anything can reach it.
- [Tests](tests.md) — `dbo-tests`. A test is named for the behaviour it
  proves and has been seen to fail.
- [The shared world](shared-world-tests.md) — `dbo-shared-world-tests`. Where
  an integration test belongs, and how it asserts.
- [Recorded projections](recorded-projections.md) —
  `dbo-recorded-projections`. Re-record what a change makes stale, in the
  same change.
- [Prose](comments.md) — `dbo-comments`. Comments, commit messages and the
  specification's text.
- [Documentation](documentation.md) — `dbo-docs`. A page reads on GitHub and
  publishes on the site.
- [Diagrams](diagrams.md) — `dbo-diagrams`. A figure is a Lini source, a
  description and a committed SVG.

Two skills live elsewhere. [Claiming a behaviour](../promise.md#claiming-a-behaviour)
— `dbo-promise` — sits with the promise model it belongs to.
[Using DBO](../../using-dbo.md) — `dbo-using` — is a reference for building
on the store.

## Agents

Four subagents in `.claude/agents/` each preload one or two of these skills
and nothing else, so a check runs in a context that holds only the rule it
applies. All four are read-only and return a verdict, never a log.

- `reachability-reviewer` asks the three reachability questions of a diff.
- `runtime-prover` runs the container tests and reports the first cause.
- `site-checker` runs the site build, the diagram ratchet and the branding
  check over a documentation change.
- `prose-reviewer` returns the cuts the prose rule asks for.

## The traps

<!-- claude:begin -->
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
<!-- claude:end -->
