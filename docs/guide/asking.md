---
title: Asking
eyebrow: Guide
standfirst: >-
  What is stuck, what needs somebody, who read this record. The questions a
  product asks are methods rather than searches it composes, and the same code
  works from inside the deployment and from across a network.
template: essay.html
---

A store nobody builds on is a filing cabinet, and the application in front of
one spends most of its time asking it things. A worklist. A ward list. A number
beside a filter. Who touched this record last March.

Those are questions, and this chapter is about asking them without composing a
query.

## The questions are the vocabulary

```java title="sample/src/main/java/cloud/jengu/dbo/sample/TheWard.java"
--8<-- "sample/src/main/java/cloud/jengu/dbo/sample/TheWard.java"
```

`asking.work().heldBy(PERSON)` is one sentence, and it is the sentence somebody
actually says. The alternative — a query the application assembles, filtering
on a field it hopes is indexed — is how a worklist ends up reading every run in
the tenant and discarding most of them in the browser.

**What a count is for.** `outstanding()` asks how many without fetching any.
A number beside a filter is the first thing a screen shows, and paying for the
rows to produce it is what makes a dashboard slow. The store answers the count
directly; a stream that counted itself would bring every run across to count
it.

## Asking is not unsealing

A ward list comes back without anybody's name on it.

That is not a limitation of this vocabulary, it is the store's membrane: a
credential entitled to see that a record exists is not thereby entitled to the
person it is about. A screen lists, filters, counts and pages without learning
who anybody is. Learning who somebody is is a disclosure — it states a purpose,
it happens under a run, and it leaves an entry behind.

So the same code that fills a worklist cannot quietly become the code that
identifies everybody on it.

## The same code, wherever it runs

`TheWard` builds `Across.through(...)`, which speaks over the tenant's surface,
because this application is in another building from its store.

Something running *inside* the deployment — a lifecycle callback, a bundle
beside the tenant — takes the other binding off the service registry, where the
tenant registered it beside its store when it came up. Everything after that
line is identical. The vocabulary is declared once and both bindings answer it,
so a caller holding one cannot tell which it has.

That is worth more than it sounds. It means the code you write against a
development deployment embedded in your own tests is the code that runs against
a cluster, rather than a thing that resembles it.

## Where the two honestly differ, they say so

A question the surface has no parameter for is **refused** by the across-a-
network binding, naming what it could not narrow by:

```
work.inScope has no parameter on this tenant's surface: a run's scope is
engine state and the face renders no parameter for it. Answering it here
would read everything and narrow in this process, which moves a tenant's
records across a network to discard most of them
```

The refusal is the point. The alternative is a vocabulary that answers
everything and quietly does the narrowing in your process — which works on a
laptop against fifty records and falls over on the day somebody has fifty
thousand, having transferred all of them.

Inside the deployment the same question is ordinary, because the run's envelope
carries it.

## A number without the rows

`outstanding()` above asks how many without fetching any, and over the surface
that is one request the tenant answers with a total and no entries:

```bash
--8<-- "docs/guide/examples/snippets/count.sh"
```

```json
{"resourceType":"Bundle","type":"searchset","total":1}
```

A number beside a filter is the first thing a screen shows, and paying for the
rows to produce it is what makes a dashboard slow. The vocabulary refuses a
count the tenant will not answer rather than handing back a number meaning
there was no number.

## Following links is declared and not built

`including` and `havingAny` exist, compile, and refuse by name. Joining by
reference is its own subject: what a page does about a referent shared by fifty
members, what stops a lazy follow being one round trip per row, and whether an
include may hand over a record the caller could not have asked for directly.

They are declared so that the answer has somewhere to land, and so that the
three kinds of join — narrowing by something about a referenced record, bringing
those records along, and finding what points at you — do not get folded into one
method by whoever arrives first.

## Watching your own questions

An integrator can attach an observer and see what its own product asked for.
It is off unless asked for, and what it hands over is the *shape* of a question:
the narrowings by name, how many came back, how long it took. Never the values.

A narrowing's value is frequently a person's number. An observer that handed
those over would leak the first time somebody wired it to a log, and they would
wire it to a log because that is what an observer looks like it is for.

And what your own product records is a convenience rather than evidence — the
party being audited controls it. The store's own trail is on the other side of
the boundary and cannot be declined.
