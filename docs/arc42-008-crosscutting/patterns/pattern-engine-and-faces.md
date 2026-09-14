---
title: "Engine and Faces"
eyebrow: Pattern
standfirst: >-
  The engine knows records, custody, history and erasure, and has never heard
  of a patient. A face is the standard a tenant declares, and it is a real
  server of that standard rather than a gateway.
pattern: 20
template: essay.html
---

**Intent — keep the obligations that every regulated store shares out of the
domain standard that happens to be in front of them.**

## You are

Building on a domain standard, because your customers speak it and your
integrations expect it. The standard says what a record looks like. It does
not say who may change one, what history is kept, how a person is erased, or
how an organisation leaves with its data.

So those get built in the application, once per application, by whoever gets
there first.

## The question

Which of the things you are building belong to the domain, and which belong to
any store holding regulated data at all?

## The forces

- A domain standard is genuinely necessary: clients speak it and tenders
  require it.
- Building custody, history and erasure on top of a domain standard means
  re-answering them for the next domain, and the next version.
- Two versions of one standard disagree about real things, and both have to be
  served at once.

## Therefore

**Put the regulatory mechanism in an engine that has no domain in it, and put
the standard in a face the tenant declares.**

--8<-- "assets/diagrams/pattern-engine-and-faces.svg"

<p class="diagram-caption">Run an eye along the lower row. That the engine has
no domain in it is something you can check from the figure rather than
something the figure asserts.</p>

The engine's concepts are not a neutral abstraction drawn to be tidy. They are
what European regulation asks of any system holding personal data, in almost
this order: know what you hold, know who may change it, prove what happened,
separate identity, erase on request, let people leave with their data.

The test for where something belongs is whether the concept carries domain
meaning. A birth date reducing to its year is the engine's business. A birth
date being spelled a particular way is the face's.

Two versions of one standard can then run in parallel over one engine and
interpret it differently — one using criteria where the other uses topics,
over the same record of changes. If the standard were the model, that would
need two stores.

## What each reader gets

- **A regulator** is shown that the obligations are met once, underneath, not
  separately by each application.
- **A security officer** reviews one set of mechanisms regardless of how many
  domains are served.
- **An administrator** changes the standard a tenant speaks by changing a
  declaration.
- **The business** can enter a second domain without rebuilding custody,
  history and erasure for it.

## Relations

- **Builds on** — [Property, Not Policy](pattern-property-not-policy.md); [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md).
- **Makes possible** — [Bytes Framed, Not Rebuilt](pattern-bytes-framed-not-rebuilt.md); [A Country Is a Zone](pattern-a-country-is-a-zone.md); [One Declared Set, Applied](pattern-one-declared-set-applied.md).
- **Composed of** — [Canonical Data Model](https://www.enterpriseintegrationpatterns.com/patterns/messaging/CanonicalDataModel.html), [Message Translator](https://www.enterpriseintegrationpatterns.com/patterns/messaging/MessageTranslator.html) and [Normalizer](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Normalizer.html), from [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/), and [ports and adapters](https://alistair.cockburn.us/hexagonal-architecture/).
- **Related work**
    - [Ports and adapters](https://alistair.cockburn.us/hexagonal-architecture/), which is this arrangement stated for an application rather than a store.
    - [Fielding (2000)](https://ics.uci.edu/~fielding/pubs/dissertation/rest_arch_style.htm), chapter five, for deriving an interface from constraints rather than from a domain.
