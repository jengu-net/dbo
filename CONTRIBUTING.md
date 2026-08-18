# Contributing

## Before code, a requirement

Every behaviour this store promises has a REQ code in the
[catalogue](docs/arc42-006-runtime/req-catalogue.md), and every REQ has a test
that proves it. A change that adds behaviour adds or claims a REQ; a change
that fixes a defect adds the test that would have caught it.

This is not ceremony. The catalogue is what lets the project say "complete"
about a capability area and mean something checkable.

## Tests describe behaviour

Test names are sentences about what the system does, not about the method
under test — `aRestoredConsumerStandsAtTheHeadOfTheFeed`, not
`testRestore2`. Read the existing suite before adding to it; the naming is
consistent and it is consistent on purpose.

Integration tests run against a real Postgres, a real Felix container and, for
the operator, a real Kubernetes API server. There are no mocks of the things
that break.

## Running the suite

```bash
./gradlew build          # everything, including the integration suite
./gradlew test --tests '*FeedIT'
```

A container runtime is required. The suite starts what it needs.

## Two properties that look like details

**The core has no dependencies.** `dbo-core` imports nothing outside the JDK,
and `dbo-postgres` adds only the driver. A change that puts a library in
either one needs a reason that survives being read aloud.

**Fat bundles hand-write their `Import-Package`.** A newly referenced sibling
package will compile, publish, and then fail at runtime with
`NoClassDefFoundError` and no build-time signal. `EmbeddedContainerIT` is the
ratchet that catches it; if it fails after your change, it is telling you the
truth.

## Logs

INFO is for what an operator needs: startup and its resolved posture, tenant
lifecycle, shutdown, and things that went wrong. It is not for per-request or
per-item events — and never for anything carrying identifying data. This store
encrypts identifying elements inside the payload; writing them to a log with
different retention and different access control undoes that. See
[docs/arc42-008-crosscutting/personal-data-isolation.md](docs/arc42-008-crosscutting/personal-data-isolation.md).

## Commits

Explain why, not what — the diff already says what. A commit that changes
behaviour says what it changes it from.

## Releasing

See [RELEASING.md](RELEASING.md).
