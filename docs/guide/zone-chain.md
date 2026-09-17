---
title: Across faces
eyebrow: Guide
standfirst: >-
  A zone speaks one release and its members may speak another. The conversion
  happens once, in a tenant nobody declared.
template: essay.html
---

Rowling Land speaks R5. The insurer speaks R4 and takes the zone's code
systems. Somebody has to convert between them, and where that work happens
decides how much of it there is.

## The two obvious answers are both bad

**Convert in the zone**, and the zone holds a copy of everything per face it
serves — so publishing one code list means maintaining several, and the zone's
own content is shaped by who happens to be reading it.

**Convert in each member**, and every R4 tenant in the jurisdiction does the
same conversion on the same content, separately, forever — and they can drift,
because nothing makes two tenants convert identically.

## What actually happens

Neither. A **projection** appears: the zone, as seen on a face.

```bash
--8<-- "docs/guide/examples/check.sh:projection"
```

```
4.0.1
5.0.0
```

The first line is `rl-on-r4`, a tenant nobody wrote. It exists because an R4
tenant declared a dependency on an R5 zone, and something had to convert once
rather than per member. The second is the zone itself, still speaking its own
release, unchanged by who reads it.

`rl-on-r4` is an ordinary tenant — database, bring-up, surface, capability
statement — because a thing that is a tenant should be one rather than a second
mechanism that will drift. It reads the zone, converts what it finds, and
serves the result to every R4 member.

Add a second R4 insurer tomorrow and no more conversion happens. It reads the
projection that is already there.

## The member does not know

The insurer asked its own tenant for a code and got an answer. Chapter eight's
`Spell Damage` lookup is the proof: that code was written to an R5 tenant and
read from an R4 one, and nothing in the request said anything about a release.

This is what the whole arrangement buys. A tenant speaks one release — its own
— and the fact that its jurisdiction speaks another is somebody else's problem,
solved once, in a place you can query when you want to know what the conversion
produced.

## What does not convert

Being plain about the limit. R4 and R5 disagree about real things, and a
projection converts what is convertible rather than pretending the releases
agree. Where a concept genuinely has no counterpart, the projection does not
invent one — and the member sees what a member of that release can see.

The chapter on faces made the same point from the other side: a store that
silently made an R5 resource look like an R4 one would be inventing clinical
content, which is worse than an absence a reader can notice.

## What you would otherwise have written

A converter in the application, called on the way out to whichever partner is a
release behind — then a second one in the partner's integration, because they
wrote theirs too and the two disagree at the edges.

A copy of the national code list per release you support, each updated by hand
when the original changes, each a chance to be a version behind.

And the conversation about which of the two is authoritative, which has no good
answer once both exist.
