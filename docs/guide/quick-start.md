---
title: Quick start
eyebrow: Guide
standfirst: >-
  The world running, a person admitted to the hospital, found again, changed
  and read back as he was — then the same person at an insurer a release
  behind, refusing what it does not have.
template: essay.html
---

You need Docker and nothing else. Every command on this page is executed
against the pinned image on every build, so if one of them does not work here,
that is a defect rather than a typo on the page.

The world runs with its authority on, so the commands here obtain a token and
send it. That is a few more characters per line than a walkthrough with
security switched off, and it is the only version of this store that would ever
be deployed — a guide that demonstrated the other one would be teaching a shape
you cannot ship.

## Start the world

```bash
--8<-- "docs/guide/examples/snippets/up.sh"
```

Six tenants come up, and the first time is slow: each is a database provisioned
from nothing with a terminology baseline loaded into it. Expect minutes rather
than seconds, most of it the baseline. That cost is per database and paid once,
which is why a world is something you bring up and keep.

Two of the six are the ones you will use:

```bash
--8<-- "docs/guide/examples/snippets/bases.sh"
```

## Get a credential

The world is guarded, which is the point of it. Ask without one and you are
refused:

```bash
--8<-- "docs/guide/examples/snippets/no-token.sh"
```

```
401
```

Every tenant runs its own authority — its own issuer, its own keys, its own
clients — so a credential is always *for a tenant*, never for the deployment.
Each of these is a client-credentials exchange against the tenant you are about
to talk to:

```bash
--8<-- "docs/guide/examples/snippets/token.sh"
```

`tenant-bootstrap` is the client the deployment holds for each tenant. Its
secret is the one this world's compose file names — in a real deployment an
operator generates it and keeps it in a vault, and the store is told what it
already decided rather than inventing one nobody can present.

Three tokens because there are three tenants in play, and a token for the
hospital will not open the insurer. From here every command carries one, which
is what a client of this store actually looks like.

## Ask each one what it speaks

```bash
--8<-- "docs/guide/examples/snippets/versions.sh"
```

```
5.0.0
4.0.1
```

One engine, two versions, side by side, differing because each tenant declared
a different face. Neither is a gateway in front of the other.

## Admit a patient

```bash
--8<-- "docs/guide/examples/snippets/create.sh"
```

The response is the record as stored, with two things added: an `id`, and a
`meta.security` entry naming the handling its type declared.

**The id is a UUID, and that is not cosmetic.** Ask for a readable one and you
are refused:

```bash
--8<-- "docs/guide/examples/snippets/readable-id.sh"
```

```json
{"resourceType":"OperationOutcome","issue":[{"severity":"error",
 "code":"invalid","diagnostics":"Invalid UUID string: harry"}]}
```

An id a caller chose is an id a caller can collide with, guess, or read meaning
into. What makes this record *the same person* as another is not its id but its
identifier — which is what the hospital's spec said when it declared `Patient`
with `"identity": "identifier"` over the system the zone publishes.

## Find him by that identifier

```bash
--8<-- "docs/guide/examples/snippets/search.sh"
```

One match, the record you wrote. Note the `--data-urlencode`: a token search
carries a `|`, and curl will not escape it for you.

Search here is strict. Ask for something the tenant never declared and you get
a refusal rather than a bundle that quietly ignored half your question:

```bash
--8<-- "docs/guide/examples/snippets/strict-search.sh"
```

```
400
```

A result set that silently answered a wider question looks exactly like the one
you asked for, which is the failure you cannot detect. So it does not happen.

## Change him, and read what he was

```bash
--8<-- "docs/guide/examples/snippets/history.sh"
```

Two entries come back. Nothing was overwritten: the first version is still
there, byte for byte, and the second sits beside it. You did not ask for
history and you did not configure it. The type declared `"handling":
"operational"`, and keeping every version is part of what that means.

## The same person, at the insurer

```bash
--8<-- "docs/guide/examples/snippets/insurer.sh"
```

He exists twice now, once in each organisation, with the same national
identifier and two different ids. That is correct and it is the point: these
are two tenants, two databases, and neither can read the other's records.
Nothing about writing him at the hospital put him at the insurer, and nothing
will, unless a declared exchange carries him there.

## And the older version refuses what it does not have

The insurer speaks R4. Send it an R5-shaped `Coverage` — `kind` is an R5
element, and R4 requires `payor` — and watch what comes back:

```bash
--8<-- "docs/guide/examples/snippets/version-refusal.sh"
```

```json
{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid",
 "diagnostics":"ERROR Coverage: Coverage.payor: minimum required = 1, but only
  found 0 (from http://hl7.org/fhir/StructureDefinition/Coverage|4.0.1)"}]}
```

The refusal names the profile and the version it validated against. That is not
a generic rejection with a version stamped on it: the insurer's face is
validating against the R4 definitions it took from its face root, and the
hospital's is validating against R5 ones, in the same process, at the same
time.

## Stop it

```bash
docker compose -f docs/guide/examples/compose.yaml down -v
```

Nothing is left behind. The databases were on a temporary filesystem, so the
next start is another cold one.

## What you just saw

Five things, none of which you configured:

- **A tenant is a database.** Two organisations, two stores, no shared table
  and no filter to remember.
- **A type declares its own discipline.** History, handling and what identifies
  a record are properties of the type, not habits of the code that writes it.
- **Identity is not the id.** The id is opaque; the identifier is what makes
  two records the same person.
- **A face is a declaration.** One engine served two versions, and each refused
  what its own version does not have.
- **A refusal is an answer.** An unsupported parameter and an invalid resource
  both come back named, rather than being ignored into a plausible result.
