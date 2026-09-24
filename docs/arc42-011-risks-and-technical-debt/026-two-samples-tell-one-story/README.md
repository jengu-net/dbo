**Open. Two samples exist and one story is told twice. `sample/` is the
distribution's, and the guide includes its source; `samples/` is where the
story is going, built on the Spring Boot assemblies. The server application is
built and serves its own world. The worker application and whatever the two
must share are not written yet, and nothing has moved off `sample/`.**

# Two samples tell one story

## What this is

`sample/` shows the store as a **distribution**: tenants declared as spec
files, served by a Felix launcher, reached over HTTP. It is the guide's — a
chapter includes the file compiled there, so a chapter cannot show a call that
no longer exists.

`samples/` shows the store as a **library**: an ordinary Spring Boot
application that serves tenants because it added one dependency, and another
that performs steps because a bean implements one. That is the claim the
assemblies were built to make, and until now nothing demonstrated it end to
end — the assemblies' own tests prove the wrapper works, which is a different
statement from an application being buildable on it.

## Why both exist at once

Because the guide reads `sample/`'s source, and moving it would break chapters
before there is anywhere for them to go. So the new applications are written
clean — they share no module, no package and no configuration with `sample/` —
and the guide moves when they carry the story.

**The world itself is copied rather than rewritten.** The applications serve
the same six tenants the distribution does — the same faces, the same zone,
the insurer a release behind — so that the only difference between the two
samples is how the store is reached. A second world would have made them two
stories, and then moving the guide would mean rewriting chapters rather than
re-pointing them.

Duplication with an end date is cheaper than a migration with a broken guide
in the middle of it. Duplication with no end date is how a repository comes to
have two of everything, which is why this item exists rather than a comment.

## What has to be true before `sample/` is deleted

- the worker application exists and performs a step end to end
- whatever the two applications must agree on lives in one module under
  `samples/`, rather than being copied into both
- the guide's chapters include the new applications' source, and its compose
  file serves the new world
- `sample:participant` — a participant joining from outside — has an
  equivalent, or its absence is a decision somebody wrote down
- nothing in `docs/` or `site/` still points at `sample/`

Until every line above is true, `sample/` is the one that is current and
`samples/` is the one that is arriving. A reader who finds both should be able
to learn that from this file in one paragraph, which is the whole job it does.

## What is built

- `samples/sample-world` — the six tenants both applications are about, beside
  them rather than inside one: the serving application bootstraps from it, and
  a test seeds what it declares from it.

- `samples/spring-boot-server-app` — a Spring Boot application with one DBO
  dependency, and a test that boots the
  real application and asserts its tenant answers a FHIR read on the
  application's own port and refuses a caller carrying nothing. The test uses
  the module's own world directory rather than a fixture: a test that built
  its own tenant would prove the wrapper again and say nothing about the
  application.

- `samples/spring-boot-worker-app` — a Spring Boot application with one DBO
  dependency, no tenant, no database and no way to get one. A bean implements
  `StepService` and that is the whole of what it writes; the executor identity,
  the durations, the registration and the lane are configuration.

- `assembly/spring-boot-test` — how either is tested. `dbo.test.*` is the only
  namespace a test author writes, and everything the application reads is
  derived from it: one database per JVM, the world, a key minted for the run,
  and where a worker is present, a credential the tenant issued and a lane
  pointing at the port. It replaced a `samples/test-support` written an hour
  earlier, which is where it wanted to live once it was clear an integrator
  wants it too.

## What one JVM cannot prove

The applications are two processes in a deployment, and the tests collapse them
into one Spring context — a testing economy, because two processes in one test
would be two JVMs. Three things follow, and none of them is a defect to fix:

**The process boundary is not exercised.** The HTTP round trip is real — the
worker builds an `HttpLane` and nothing else, so work leaves over a port and
comes back — but nothing proves a worker in another JVM, against a server it
did not start, performs a step.

**One context has one application configuration, and the two disagree.** Both
applications ship an `application.yaml`, and only one can be the one Spring
loads — but it is worse than a choice between them. The worker declares
`spring.main.web-application-type: none`, which is true of it standing alone,
and the serving application it is tested beside needs a servlet container. So
the worker's test states which one wins, along with its identity and poll, and
what that test proves is the worker as configured THERE rather than as its own
file configures it. The assertion names the executor as a literal, so a drift
between the two copies is what fails.

**And a test's own `application.yaml` shadows the application's.** Spring loads
the first on the classpath and test resources come first, so a sample test
written that way proves an application configured by the test rather than one
as it ships. Both samples had that, and both now add to the application's
configuration through a profile instead of replacing it.

## The catalogue cannot see these proofs

Both sample tests declare what they prove — `CONT_EMBEDDED_IN_JVM` for an
application that boots the store inside its own JVM, and
`PROC_STEP_SERVICE_EMBEDDABLE` for a bean that performs a tenant's work over
the lane alone — and assert it through `Proves`, which refuses a promise the
test did not declare.

The requirement catalogue does not know. It is composed from the proofs the
annotation processor writes on the harness's classpath, and these modules are
not on it, so `req-catalogue.md` names no test under `samples/`. The
declaration is real and the guard is real; what is missing is the projection
reaching them.

That is a change to a ratchet rather than to a sample, which is why it is
written here instead of done in passing: either the projection learns to read
these modules, or a promise proved in a sample is a promise the catalogue
reports as proved by somebody else.

## What is not

- the end-to-end test: a step performed through the lane, asserted on the
  executor that performed it rather than on the step having run, because in one
  container a locally-driven run and a lane-driven one look the same from the
  step's side
- anything about retiring `sample/`
