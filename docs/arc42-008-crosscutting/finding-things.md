# Finding things

*A concept the original specification never gathered: the store's answers to
questions, and what makes them trustworthy.*

## Why searching and looking up a code are one concept

Two kinds of question get asked of a store like this. **Which records match
these criteria?** and **what does this code mean, and is it allowed here?**
They look unrelated — one is a query engine, the other a dictionary — and they
share the failure that matters:

**both can be quietly wrong, and the caller cannot tell.**

A search that ignores a criterion it did not understand returns *more* rows than
were asked for, and every one of them looks like a legitimate result. A code
system that is present but cannot answer a lookup makes a record that referenced
it look validated. Neither raises anything. Both are discovered later by
somebody reconciling numbers that should have agreed.

So the promise across both is the same one: **an honest answer, or a refusal you
can act on — never an approximation.**

## A question is answered or refused, never approximated

**An unsupported search parameter is rejected, never silently ignored.** This is
the single most consequential decision here, and it is the opposite of the
forgiving behaviour many servers choose.

The reasoning is asymmetric. If a store refuses a criterion it does not support,
the caller gets an error, fixes the query or the configuration, and nothing
downstream is wrong. If it ignores that criterion, the caller receives a
superset — records that should have been filtered out — and acts on them. A
narrowing filter that silently does nothing is indistinguishable from one that
matched everything.

**Sorting and range filtering are typed.** Numbers sort as numbers, dates as
dates, tokens by their own rules, with indexes that match those semantics — so
a range query does not quietly become a string comparison that happens to look
right for one decade and wrong for the next.

## The description of the surface cannot drift from the surface

A store advertises what it can do. That advertisement is usually written by
hand, which means it is a *second* statement of the truth and starts rotting
immediately: operations that are served but undeclared, and operations declared
but unanswered.

Here the capability description is **generated from what is actually routable** —
the configured types, the interactions their handling permits, the conditional
writes their identity class allows, the history their durability keeps, the
parameters accepted, and the operations the wired components registered. The
router and the statement read **one list**.

The property that gives is worth naming precisely: a served-but-undeclared
operation and a declared-but-unanswered one are not *unlikely* — they are
**unexpressible**. There is no state of the code in which the description and
the behaviour disagree, so nobody has to remember to update it.

## Indexing is part of the contract, not an afterthought

**What can be searched is declared with the type**, from the beginning,
including any side tables a hard parameter needs. That is what makes the
capability description generatable in the first place, and it stops "we support
that" from meaning "it will scan the table".

**A tenant or a module can register a search parameter of its own**, and
extraction, reindexing and the new index follow automatically. This is where
[the payload being the truth](records-you-can-rely-on.md) pays for itself: a new
way to search is a derivation over data that is already there, so it is a
background operation rather than a migration.

## How much search is enough was decided by evidence

A standard's full search grammar is large, and implementing all of it before
anything works is a way to ship nothing. The scope here was set by counting:
every search interaction that production systems — a clinical cloud, a
laboratory system, an assistant — actually issue, across roughly 206 call sites,
[inventoried and kept](../evidence/search-usage-inventory.md).

Two things came out of that count. What real callers use is a small, sharply
defined set, and **that set works identically here**. And they all reach the
store through hand-written clients passing raw query strings — no SDK, no
GraphQL — which tells you what compatibility actually has to mean.

Deciding scope this way is worth copying: it produces a defensible line, and a
tier nobody uses is not a gap in the product but a feature nobody has paid for.

## Terminology is a store, not a shelf of documents

Code systems and value sets are not documents that happen to live here. They are
**held in a normalised, query-optimised form**, and the document a standard
defines for them is a *projection assembled on demand* for the wire — the same
engine-and-face split the rest of the store keeps.

That inversion is what makes the operations answerable at local speed rather
than by parsing a large document per question, and it is what lets a large code
system load as a **native bulk operation** — no chunking workarounds, no
parameter-cap ceilings.

**Every served tenant answers from its own store**, whichever version of a
standard it speaks. No tenant is a second-class reader that has to be redirected
to somebody else's terminology service. And a terminology write reaches the
queryable form rather than being stored whole, because a code system that is
**present and answers nothing is worse than one that is absent** — the absent
one is an error a caller can see.

## A coded value is checked against what this tenant actually holds

When a record carries a code, the question is not merely "is this string
well-formed" but "does this mean anything here". So coded values are resolved
against the tenant's own terminology wherever the carried definitions are
silent, and the answer depends on **how strongly the binding was declared**:

- a **required** binding that is violated is a refusal;
- a **weaker** binding is advice the caller is given rather than refused for;
- a system **nobody holds** is reported as unresolvable — which is a **coverage
  fact, not an invalidity**.

That last distinction is the one that gets lost in most implementations, and it
matters in both directions. Reporting "unresolvable" as "invalid" tells a caller
their data is wrong when the truth is that this deployment has not loaded that
code system yet. Silently accepting it tells them nothing at all.

And **everything the check had to say reaches the caller** — not only the parts
that would refuse. An outcome carrying just the fatal finding trains people to
fix one thing at a time and re-submit.

## What this costs

**Strictness is felt at integration time.** A caller migrating from a forgiving
server will hit refusals for parameters that server ignored, and every one of
those is a query that was silently returning wrong results. That is a real cost,
paid once, in the right place.

**Terminology has to be loaded before it can validate.** A tenant that has not
loaded a code system gets "unresolvable" rather than silent acceptance, which is
correct and still means somebody has an operational task before validation is
meaningful.

**Declared indexing is work up front.** A type's searchability is designed with
the type rather than discovered when a query is slow.

## Where the detail is written down

- **The exact rules and their proofs** — the search and terminology entries in
  the [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
- **The measured basis for the search scope** — the [search usage
  inventory](../evidence/search-usage-inventory.md).
- **Why the queryable projection can always be rebuilt** — [records you can rely
  on](records-you-can-rely-on.md).
- **Which component owes what**, and where a face's validation sits — [the
  engine and its faces](engine-and-faces.md).
