---
title: Describe the job, and the rest follows
template: home.html
hide:
  - toc
---

<div class="hero" markdown>
<div class="wrap" markdown>
<div class="kicker">Open source · MIT · Pre-1.0</div>

# Describe the job. DBO does the rest.

<p class="lede">The work your organisation does is made of jobs — clearing a
consignment, validating a batch, admitting a patient. You say what the jobs are,
who may do them, and what each one needs to see. That description is most of the
application.</p>

<p class="lede">Everything underneath follows from it. Getting at the data means
doing one of those jobs, so who looked and why is left behind by the looking.
Keeping every version, answering an erasure with a receipt you can show — the
store's business, not something your code has to remember to do.</p>

<p class="lede">And whoever runs the servers cannot read any of it.</p>

<div class="cta" markdown>
[Read the guide](docs/guide/){ .btn .primary }
[Run it in a minute](technical/quickstart-docker/){ .btn }
[Source on GitHub](https://github.com/jengu-net/dbo){ .btn }
</div>
</div>
</div>

<section markdown>
<div class="wrap prose" markdown>
<div class="eyebrow">What it is for</div>

## Storing the data is the easy half.

The hard half is everything standing around it. Who read this, and what were
they doing at the time. Can this person be deleted, and can you show that it
happened. Has any of this been changed since it was written. Whose is it, and
how long may you keep it.

None of those are extra questions. A job has somebody doing it, a reason for
doing it and a time it happened — the same way a street has a name and a person
has one. Write the job down and you have already answered them.

If instead they are answered in your own code, one at a time, by whoever
remembered, then each answer is as good as the person who wrote it and as
current as the day they left.

Here, getting at the data means doing the job — so there is no second system
keeping the record of the first, and nothing to remember to call.

--8<-- "assets/diagrams/where-access-begins.svg"

<p class="diagram-caption">A job reaches the documents that job needs and no
others, and what it read, when, and what it was for is written down as the job
runs rather than reconciled afterwards. A plain read still reaches the data
too, and closing that is unfinished.</p>

[How work is declared, and performed →](docs/guide/work/)
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

<p class="start">Start with <a href="docs/guide/personal-data/">personal data</a>, then
<a href="docs/guide/erasure/">erasure</a>.</p>
</div>

<div class="col" markdown>
### If you have to run it

Provision a tenant without ever holding its credentials, so hosting somebody's
data never means being able to read it. Backup is export and restore is import,
so every backup is restore-tested by daily use. One PostgreSQL database per
tenant and nothing else to operate — no broker, no cache, no search cluster.

<p class="start">Start with <a href="docs/guide/lifecycle/">a tenant's whole
life</a>, then <a href="docs/guide/export-and-import/">leaving</a>.</p>
</div>

<div class="col" markdown>
### If you are deciding on an architecture

The engine holds no domain: a tenant declares which standard it speaks, and that
declaration is where the domain lives. Work is a record in the same store as the
data, which is what lets two organisations that do not trust each other share a
process rather than a file drop.

<p class="start">Start with <a href="docs/guide/how-it-fits/">the engine and its
faces</a>, then <a href="docs/guide/work/">work</a>.</p>
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
