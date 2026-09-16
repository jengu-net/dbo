# The sample world

Every example in the guide runs against this world, so a name in one chapter
means the same thing in the next. It is six tenant specs and nothing else —
the same shape the quickstart applies, and the same shape an integration test
can point at.

| Tenant | Face | What it is |
|---|---|---|
| `mom` | R5 | the managing tenant — what this deployment was told to serve |
| `rl` | R5 | the zone — Rowling Land, whose terminology the others take |
| `fhir-r5` | R5 | a face root holding the R5 definitions |
| `fhir-r4` | R4 | a face root holding the R4 definitions |
| `hogwarts` | R5 | a hospital |
| `gringotts` | R4 | an insurer, deliberately a version behind |

Two versions is the point of the second pair. A payer on the older release is
what actually happens, so conversion, identity across versions and a refusal
that names the version are demonstrable rather than described.

The zone holds only what the examples use: the code systems and value sets
that are canonical here. It gains more when a chapter needs more.

## What was checked

All six were applied to a server from the published image and served their
capability statement, reporting `5.0.0` and `4.0.1` respectively. A patient
created at `hogwarts` was found again by identifier. The same person was
created at `gringotts`, whose R4 face refused an R5-shaped `Coverage` by
naming the R4 profile it failed against.

First bring-up is not fast: on one laptop the managing tenant took about two
and a half minutes, and each further tenant between twenty-five seconds and a
minute, almost all of it the terminology baseline. That cost is per database,
so a world is something to bring up once and keep, not once per test.
