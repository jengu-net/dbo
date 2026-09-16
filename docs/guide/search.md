---
title: Search
eyebrow: Guide
standfirst: >-
  The tenant tells you what it can be asked, answers exactly that, and refuses
  the rest by name. Pages are held by a cursor, so a write between two pages
  does not make you deduplicate.
template: essay.html
---

You have written records. Finding them again is the next thing your system
does, and it is where most stores quietly make you a promise they do not keep:
a parameter the server does not understand is ignored, the query succeeds, and
the result set is wider than you asked for. It looks exactly like the one you
wanted.

This store will not do that. What follows is the whole of its stance and what
it costs you.

## Ask the tenant what it can be asked

The capability statement is generated from what the tenant actually holds, not
written by hand:

```bash
--8<-- "docs/guide/examples/check.sh:capability-search"
```

```
_id
_lastUpdated
_profile
_tag
active
address
address-city
...
family
gender
given
identifier
name
...
```

Twenty-nine parameters for `Patient` here, and they are there because the
hospital's face root carries the R5 `SearchParameter` definitions, which the
hospital declared a dependency on. A tenant on a different version, or one that
took a narrower set, lists something different. So this is a question to ask
the tenant at runtime rather than a constant to compile in.

## Ask for something else and you are refused

```bash
--8<-- "docs/guide/examples/check.sh:strict-search"
```

```
400
```

Not an empty bundle, and not a bundle that ignored the parameter. The same
applies one level down, to a modifier a parameter does not have:

```bash
--8<-- "docs/guide/examples/check.sh:unknown-modifier"
```

```json
{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid",
 "diagnostics":"unsupported search parameter for Patient: family:nosuch"}]}
```

The refusal names what it refused. That matters more than it sounds: a caller
told only that something is unsupported can do nothing but guess, and the
parameter here *is* supported — it is the modifier that is not.

**What this costs you** is that a query which would have half worked now fails.
That is the trade, and it is deliberate. A wrong answer that looks right is the
failure you cannot detect from the outside, and on a store holding regulated
records it is the one that matters.

## Counting without fetching

```bash
--8<-- "docs/guide/examples/check.sh:count"
```

```json
{"resourceType":"Bundle","type":"searchset","total":1}
```

A bundle with a total and no entries. Useful for the ordinary case of showing a
number beside a filter without paying for the rows.

## Pages are held by a cursor

Ask for a page:

```bash
--8<-- "docs/guide/examples/check.sh:first-page"
```

The bundle comes back with a `next` link, and the link carries a `_cursor`:

```
next -> .../Patient?_count=2&_cursor=djF8a3xNakF5Tmkwd09TMHhObFF3T1Rvek5Eb3hPUzQzTkRFek5ESmF8MDF
```

The cursor is opaque. You are not meant to read it, take it apart, or
construct one — and because you cannot, what it encodes is free to change
without breaking anybody holding one.

Now the part that earns it. Write a record *between* fetching page one and
page two, then follow the cursor:

```bash
--8<-- "docs/guide/examples/check.sh:next-page"
```

Page two contains nothing page one already gave you. That is asserted on every
build, by creating a record between the two fetches and failing if any id
appears twice.

With offsets it would be a different story: *skip 2, take 2* counts from the
start of a result set that has just grown, so a row slides across the boundary
and the caller sees it twice. The usual fix is for every caller to deduplicate
on identity and carry the duplicate window around in application code. Here
that work is designed out rather than worked around.

**One wrinkle worth knowing.** The `next` link is built from the address the
node was told to bind to. This world binds to everything, so the link says
`0.0.0.0` and the example points it at the host you are on. A deployment that
names itself properly produces links a client can follow as given.

## What you would otherwise have written

A whitelist of query parameters per endpoint, and the review that keeps it in
step with the columns. A decision, made once and forgotten, about whether an
unknown parameter is an error or is ignored. Offset paging, and then the
deduplication every caller learns to do, and then the bug report from the one
caller who did not.

And the document describing what your API can be asked, maintained by hand,
correct on the day it was written.
