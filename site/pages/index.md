---
title: A store for data somebody will one day audit
template: home.html
hide:
  - toc
---

<div class="hero" markdown>
<div class="wrap" markdown>
<div class="kicker">Open source · MIT · Pre-1.0</div>

# A store for data somebody will one day audit.

<p class="lede">DBO holds regulated records for organisations that do not trust
each other, and the operator running it cannot read them.</p>

<p class="lede">Every disclosure is recorded and says why it happened. Every
version is kept and cannot be quietly changed. Erasing a person is an operation
that answers with a receipt. None of that is a policy somebody promises to
honour. It is a set of things the operator cannot do.</p>

<div class="cta" markdown>
[Why it is shaped this way](why/){ .btn .primary }
[Run it in a minute](technical/quickstart-docker/){ .btn }
[Source on GitHub](https://github.com/jengu-net/dbo){ .btn }
</div>
</div>
</div>

<section markdown>
<div class="wrap prose" markdown>
<div class="eyebrow">Why another one of these</div>

## Storing the records is the easy half.

The questions that decide whether you may operate are different ones. Who read
this record, and for what stated purpose. Can this person be erased, and can you
show that it happened. Has this history been altered since it was written. Which
organisation does this belong to, and what does its jurisdiction require. How
long may it be kept.

Platforms answer those by hand, once per application, above a store that cannot
help. The answers end up scattered across application code, where each one is
somebody's good intention rather than a property of the system.

**DBO is the store that helps**, and it starts somewhere unusual: not with the
data, but with the work that needs it. Nobody reads or changes a regulated
record for no reason — the reason is a step of some process. So a step is what
access is granted to, and performing it is what leaves the proof.

--8<-- "assets/diagrams/where-access-begins.svg"

<p class="diagram-caption">Analysing, deciding and recording the decision are
one piece of work. There is no way to reach the data, or to change it, without
doing the work that needed it.</p>

[Access granted to the work →](why/work/)
</div>
</section>

<section markdown>
<div class="wrap" markdown>
<div class="eyebrow">Where to start</div>

## Three different reasons to care about it.

<div class="cols" markdown>
<div class="col" markdown>
### If you are responsible for lawfulness

Access, portability, restriction and erasure are ordinary operations rather than
projects scheduled per request. Erasure answers with a record of what happened,
which a deletion never could. The trail is append-only against every party
including the operator, so it is evidence rather than a log somebody could have
tidied.

<p class="start">Start with <a href="why/personal-data/">personal data</a>, then
<a href="why/erasure/">erasure</a>.</p>
</div>

<div class="col" markdown>
### If you have to run it

Provision a tenant without ever holding its credentials, so hosting somebody's
data never means being able to read it. Backup is export and restore is import,
so every backup is restore-tested by daily use. One PostgreSQL database per
tenant and nothing else to operate — no broker, no cache, no search cluster.

<p class="start">Start with <a href="why/tenant-status/">a tenant's whole
life</a>, then <a href="why/leaving/">leaving</a>.</p>
</div>

<div class="col" markdown>
### If you are deciding on an architecture

The engine holds no domain: a tenant declares which standard it speaks, and that
declaration is where the domain lives. Work is a record in the same store as the
data, which is what lets two organisations that do not trust each other share a
process rather than a file drop.

<p class="start">Start with <a href="why/engine-and-faces/">the engine and its
faces</a>, then <a href="why/work/">work</a>.</p>
</div>
</div>
</div>
</section>

<section markdown>
<div class="wrap prose" markdown>
<div class="eyebrow">Where it is</div>

## Pre-1.0, and specific about what that means.

Two FHIR versions serve concurrently over one engine today; the shape that lets
a second domain join them is in place and the wiring is not. Which behaviours
are promised, and which of those a test already proves, is a catalogue the
build checks rather than a page somebody remembers to update.

[What is promised, and what proves it →](docs/arc42-006-runtime/req-catalogue/)
</div>
</section>
