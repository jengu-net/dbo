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

- `samples/spring-boot-server-app` — a Spring Boot application with one DBO
  dependency, the sample world copied under `world/`, and a test that boots the
  real application and asserts its tenant answers a FHIR read on the
  application's own port and refuses a caller carrying nothing. The test uses
  the module's own world directory rather than a fixture: a test that built
  its own tenant would prove the wrapper again and say nothing about the
  application.

## What is not

- the worker application, and the module holding what it shares with the
  server — the worker's test needs a server to connect to, which is what that
  module is for
- anything about retiring `sample/`
