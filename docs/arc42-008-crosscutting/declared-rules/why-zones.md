---
title: A jurisdiction is a tenant
eyebrow: Why DBO
standfirst: >-
  Which identifier systems establish that a person is who they say they are,
  and which brokers may authenticate one, are properties of a country. Building
  them into an application is how a product becomes unexportable.
why: 12
template: essay.html
---

A health system that works in one country and needs a development project to
work in the next one is not a portable product. It is a product with a very
expensive sales cycle.

The facts that vary are not deployment trivia. They decide who can be
authenticated and by what name: which national identity brokers exist and how
they are reached, which identifier systems people are resolved by, which
terminology is canonical. Those are jurisdictional facts, and they change on
their own schedule — a country adds a broker, retires an identifier scheme,
publishes a new terminology release.

## So a zone is a tenant, and its declarations are records

A **zone** is a tenant whose content is those declarations rather than clinical
data. The brokers it recognises and the identifier domains it resolves people
by are ordinary records inside it.

Being records rather than configuration files is the whole point. They are
versioned, so you can see what the rules were on the day something happened.
They are audited, so a change has an author. They are exported like any other
content. And they stream down to the tenants that depend on them through
exactly the same machinery that distributes shared reference data — the
jurisdiction chain and the content chain are one mechanism, not two.

Entering a new country becomes configuration and terminology. Not a release.

<div class="takeaway" markdown>
Nothing in the code knows the name of a country. A zone declares what the
country requires, and tenants inside it inherit that.
</div>

## Secrets are never in a declaration

A broker record names the broker; the machinery then resolves the credential
from custody by that name.

This is not tidiness. A declaration that carried a secret could not be
exported, could not be streamed to a dependent tenant, and could not be read by
an operator checking a configuration — all things a declaration exists to be.
Keeping secrets out is what lets the rest be ordinary content.

## The set is jurisdictional, the choice is organisational

A jurisdiction may well have several identity brokers: one for government
bodies, another for the private sector, a third for a particular profession.
The zone declares which exist.

A tenant then declares which one it uses, and may further restrict which ones
it will *accept*. That restriction is a policy, not a capability claim — the
tenant is not asserting that the others do not work, it is saying it will not
honour them.

This has a pleasant consequence in practice. Sign-in happens once per zone
rather than once per organisation, and the session accumulates which broker
performed which ceremony. A person who signed in for one tenant is usually
already signed in for the next. A stricter tenant can demand its own ceremony
onto that same session without invalidating anybody else's.

## The layering rule, which turns up everywhere

> Outer declares the set. Inner chooses within it, and may narrow — never
> widen.

It is worth naming because the same shape governs three things that look
unrelated. A zone declares brokers and a tenant narrows them. A step declares
who may override it and precedence selects only among those. A credential and a
step intersect to decide what a participant may claim.

In every case the permissive direction requires an act by the party with
standing, and the restrictive direction is always available to the party
underneath. That is what makes a shared deployment safe to join: **nothing an
inner party declares can grant it more than the outer party allowed.**

--8<-- "assets/diagrams/outer-declares-inner-narrows.svg"

<p class="diagram-caption">Containment is the rule, not an illustration of it: a tenant's accepted set is drawn inside the zone's declared one, and there is nowhere outside it to draw.</p>


<div class="further" markdown>
The declarations, the broker and session rules, and the two structural rules
underneath the layering are in
[Declared rules](README.md).
How a zone's content reaches the tenants below it is
[Staying in step](../change-and-who-is-listening/why-subscriptions.md).
</div>
