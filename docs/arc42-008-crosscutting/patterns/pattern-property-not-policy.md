---
title: "Property, Not Policy"
eyebrow: Pattern
standfirst: >-
  A policy is a commitment somebody can break, forget, or be compelled to set
  aside. A property is something the system does not permit.
pattern: 1
template: essay.html
---

**Intent — turn a promise that depends on somebody's discipline into one that
does not depend on anybody.**

## You are

An organisation holding records that matter to the people in them. You have
written down how those records may be used. The document is good, the training
happened, and the annual audit passes. None of that tells you what took place
on the evening a senior person needed an answer quickly and asked somebody to
look something up.

## The question

How do you make a promise about data whose truth does not rest on everyone
who could break it choosing not to?

## The forces

- Written rules are cheap to make and cheap to change, which is exactly why
  they can be set aside by the same means.
- Every control you enforce in software is one more thing that has to stay
  true while the software changes.
- The people who most need to trust the promise — a regulator, a patient, a
  competitor sharing a project — are the people with the least ability to
  watch you keep it.

## Therefore

**Build the promise so that breaking it has no interface.** Not a rule saying
the thing must not be done, but an arrangement in which there is nothing to
call, no parameter to pass, and no privilege that opens the door — because the
door was never built.

--8<-- "assets/diagrams/pattern-property-not-policy.svg"

<p class="diagram-caption">Only one of the two columns has a line drawn faint,
and that is the whole difference: on the left it is the day the promise is not
kept, and on the right there is no such day to draw.</p>

Every other pattern in this language is one application of the same move. Do
not grant access to a person and then police it; grant it to a step, so there
is no general reach to misuse. Do not separate customers by a filter everyone
must remember to apply; give them separate databases, so a query with a bug in
it has nowhere to go. Do not handle identifying material carefully on the way
out; encrypt it where it is written, so every copy carries ciphertext whether
or not each path was coded correctly.

The discipline the pattern asks of you is not to claim more than the
arrangement gives. Where something really is a policy, say so, and defend it
the way policies have to be defended.

## What each reader gets

- **A regulator** can be shown the arrangement rather than the undertaking,
  and the arrangement is the same on a bad Tuesday.
- **A security officer** stops maintaining a control that consists of asking
  people to be careful, and starts maintaining one the build proves.
- **An administrator** has fewer things to remember, because the things that
  were remembered are now refused.
- **The business** can make a claim in a tender that survives due diligence,
  because the answer to "and what if somebody decides otherwise" is that there
  is no otherwise to decide on.

## Relations

- **Builds on** — nothing. This is the root.
- **Makes possible** — every other pattern in the language, directly or through one of the five that descend from it: [The Tenant Is a Database](pattern-the-tenant-is-a-database.md); [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md); [Work Is the Reason](pattern-work-is-the-reason.md); [Engine and Faces](pattern-engine-and-faces.md); [The Person Is a Key](pattern-the-person-is-a-key.md).
- **Related work**
    - [Clark and Wilson (1987)](https://doi.org/10.1109/SP.1987.10001) on integrity enforced by the system rather than asserted about its users.
    - [Parnas and Clements (1986)](https://doi.org/10.1109/TSE.1986.6312940) on presenting a design as the consequences of its requirements.
    - [Saltzer and Schroeder (1975)](https://doi.org/10.1109/PROC.1975.9939), whose economy of mechanism is the same preference for an arrangement over a rule.
