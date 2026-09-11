---
title: The trail is a record, not a log
eyebrow: Why DBO
standfirst: >-
  Who read this, who changed it, on whose authority, and when. Kept as ordinary
  records in the tenant's own store — append-only against everyone, the vendor
  included — rather than as lines in a file somewhere else.
why: 9
template: essay.html
---

Most systems audit by writing to a log. The log then goes to an aggregator,
which belongs to whoever operates the deployment, and is retained according to
that aggregator's settings.

Every part of that is wrong for evidence. It leaves the tenant. It can be
edited, or simply not shipped. Its retention is somebody else's dial. And when
you need it — years later, under question — you are asking an operations team
to search infrastructure that has been replaced twice since.

Here the trail is records. They live in the tenant's own store, they appear on
its change feed, they are exported and restored with it, and they obey the same
authority as everything else in it.

## Append-only, and the exemption is unconditional

Audit entries are **exempt from the tenant's own write discipline**. Whatever a
tenant declares for its other types, an audit entry has no update and no
tombstone under any policy. Retention's sweep is the only thing that can remove
one, and every removal is itself audited — without retaining what was removed.

There is no interface that edits an entry, for anybody, including the party
running the deployment. An audit trail somebody can edit is a document rather
than evidence.

<div class="takeaway" markdown>
The actor on an entry comes from the tenant authority's validated token — the
client and the subject — never from what the caller said about itself. Under
any audited policy there are no anonymous mutations.
</div>

## Applications may enrich it, and cannot impersonate it

An application can contribute business-level events, so the trail can carry what
actually happened in a domain rather than only what happened at the database.

What it cannot do is lie about two fields. The machinery stamps the actor from
the validated token and the time from its own clock, overriding whatever the
caller claimed. The trail can be enriched. It cannot be backdated or written in
somebody else's name.

## Carrying is not reading

One trail, and the target of an entry says what kind it is.

A hop that **carried** work leaves a travel entry, about the task. A participant
that **opened** a payload leaves an access entry, about the document — landing
exactly where every other reading of that document lands.

So "who read this" is answered from the document, by somebody who need not know
that work exists at all, and "where did this go" is answered from the task. And
the trail can say that *nobody looked*.

The machinery's own read to seal a payload records nothing, deliberately: a read
that yields only ciphertext is not a disclosure, and recording it as one would
make every genuine disclosure harder to find.

## Evidence you cannot find is not evidence

A compliance question starts by narrowing. *When was this tenant suspended, and
by whom.* If the only way to ask that is to read the whole trail, the answer is
technically available and practically absent — and the failure has a particular
shape: a bounded read of a busy trail returns the last few things that happened,
so a rare entry sitting in the store reads as one that never happened.

So the trail is asked, not scanned. Six ways to narrow: **who** acted, **what**
record it was about, **what action**, **when**, **which run** it belonged to —
that last one turns a journey across organisations into a single question — and
**what kind of event** it was.

<div class="takeaway" markdown>
The kind is searchable because the code is what the store lifts out of a
contributed document when the entry is written. Everything else a domain
contributed rides opaquely inside the document and is not indexed — so it is not
searchable, and the list of parameters says so by leaving it out.
</div>

That decides something worth stating, because it is the shape of an answer most
systems get wrong. A search for `type=urn:example:audit-type|tenant.suspended`
names a code **and** the system it was coded in. The system was never indexed.
Matching the code and quietly dropping the system would answer a narrower
question with a wider result — and on this surface of all surfaces, that is the
failure that matters, because the caller cannot see it happened: the rows come
back looking exactly like the ones they asked for.

It is refused instead, with the search that would have worked. A caller told
only that something is unsupported can do nothing but guess, and this parameter
is supported — it is the qualifier that is not.

## A trail, chained

--8<-- "assets/diagrams/a-trail-that-is-chained.svg"

<p class="diagram-caption">The links are the claim, not the entries. A row of
entries with nothing joining them is a log.</p>

## It survives erasure

This is where two requirements usually collide. Append-only says nothing may be
removed; the right to erasure says a person may require exactly that.

They coexist here because [shredding destroys a key rather than rewriting
anything](../data-isolation/why-personal-data.md). The entries remain, complete and in order — the
person evaporates from them. What happened is still provable years later; who
it happened to is gone.

Audit entries are pseudonymous to begin with, re-identifiable only through the
vault, so this is a smaller step than it sounds.

## No second vocabulary

On a FHIR tenant the trail is served as `AuditEvent`, rendered from the native
records on read. The narrowing above is ordinary FHIR search, the scopes that
gate it are the tenant's ordinary scopes, and there is no separate audit console
with its own login to secure.

The audit level itself is declared in the tenant's configuration beside its FHIR
version, validated when the tenant is registered, and visible in the
CapabilityStatement — so what is being recorded is something a client can ask
rather than something an operator remembers.

<div class="further" markdown>
Write discipline, retention as a floor and a ceiling, and what a restore
re-applies before it will serve are in
[Declared rules](../declared-rules/README.md).
The trail as a FHIR client sees it is
[The FHIR face](../the-fhir-face/README.md).
</div>
