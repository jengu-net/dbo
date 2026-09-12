---
title: "One configuration, every environment"
headline: "What you tested is what you apply"
eyebrow: Why DBO
standfirst: >-
  Value sets, profiles, search parameters, which tenants a deployment serves,
  whether a step is automated. One declared set, read from a repository and
  applied to a tenant — and then to the next tenant, and to the appliance after
  that, without being rewritten for either.
why: 3
template: essay.html
---

Configuration in most systems belongs to the place it runs. Development has its
own, test has a copy of development from some months ago, and production has
whatever was applied by hand the last time something was urgent. Nobody can say
what differs between them, because there is no single thing to compare.

Here a declared set is a thing in its own right. Where it is read from — a git
repository, a directory, a mounted ConfigMap, a lane from a cloud — is a detail
of reading, and reading is all a source does. What a declaration means, where
it lands and what it costs to apply belong to the applying, and the applying is
the same operation wherever it runs.

## The same set, applied in more than one place

An apply names a **scope**: a tenant, a deployment. The set does not change
between scopes. The scope does.

What a scope last agreed with is recorded on its own run, in the store, rather
than in the source. So two scopes reading the same repository can stand at
different points in it — a test appliance at the revision being tried,
production at the one that was tried a month ago. Moving production forward is
then not a migration somebody writes. It is the same apply, against a revision
that has already been exercised somewhere it did not matter.

That is the lifecycle a large declared set needs. A hundred value sets and
profiles are not reviewed by reading them. They are reviewed by applying them
somewhere harmless and seeing what the pass says it did.

## Which is only safe because a pass says what it did

Applying at start-up, reporting to a log and then believing it is what makes
the sentence every operator has heard: *my configuration had no effect*. A set
you intend to promote cannot be believed that way.

So applying a declared set is **a sweep** — a run, in the same list as the
domain work, with the same counts and the same holders as anything else the
store is doing.

--8<-- "assets/diagrams/a-pass-says-what-it-did.svg"

<p class="diagram-caption">A pass is over a set, not over a resource at a time.
It tallies what it read, what it applied, and what it skipped, and the skip has
a name.</p>

### The four rules that make it trustworthy

**One bad declaration does not take the rest with it.** The others apply, and
the one that did not becomes a card naming it — held work, in the same queue as
everything else waiting for a person, rather than an exception in a log.

**A card closes by re-evaluation.** Fix the cause at the source and the next
pass closes it. There is deliberately no button, because closing by click is
how a card reads resolved while the fault is live. Closing by hand exists only
for conditions nothing can re-check.

**An unchanged source is a read, not a re-application.** What a scope last
agreed with is recorded on its own run, so a pass over unchanged configuration
does nothing — except for a scope with a card still open, which is retried
until it closes.

**Empty and unreachable are different answers.** A source that cannot be read
says so. It never answers with an empty set — because to whoever has to decide
what is missing, "there is nothing declared" and "I could not look" are the
same sentence, and they demand opposite responses.

<div class="takeaway" markdown>
That last rule has teeth on withdrawal. Only a read the source says is
**complete** may withdraw what it no longer names, so a partial read and an
unreachable one take nothing away. And a withdrawal nobody knows how to undo
becomes a card rather than a silence — nothing is removed by machinery that was
never told how to remove it.
</div>

## What is declared can be asked of the store

What a deployment has been told to serve is not a file on a node's disk. It is
records in the managing tenant, applied from whatever source declares them like
any other configuration.

So "what is this deployment supposed to be serving" is a query rather than an
expedition, and a declaration that will not parse is a card naming the file
rather than a line in a boot log that scrolled past during start-up.

A deployment with no managing tenant records nothing and serves exactly as
before. Recording what was declared is not a condition of honouring it.

## Handing a set over

A zone hands its content — value sets, profiles, search parameters — to the
tenants that depend on it as one recorded pass rather than posted a resource at
a time.

The declarer's own name for the set is echoed back and never parsed, and
nothing reaches back afterwards: whoever declared it re-evaluates against what
the pass says it did. The two sides never have to be up at the same time, which
is the property that makes this work across organisations that keep different
hours.

## The store applies its own configuration this way too

Bring-up configuration is not a privileged path with its own error handling. It
goes through the same sweep, produces the same account, and raises the same
cards.

That is deliberate: the code path used once per deployment, by the vendor, in
circumstances nobody else sees, is exactly the code path that should not be the
special one.

<div class="further" markdown>
Why a run is a record at all is [work](../processes-and-work/why-work.md). What a tenant *declares* about
each type is [a type says what it is](../records-you-can-rely-on/why-a-type-declares-what-it-is.md), and what
a runtime is actually doing about each tenant is
[a tenant's whole life](why-tenant-status.md).
</div>
