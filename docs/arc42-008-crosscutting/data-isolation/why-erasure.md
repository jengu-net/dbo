---
title: "Erasure that reaches backups"
headline: "A person can be forgotten without rewriting history"
eyebrow: Why DBO
standfirst: >-
  Two requirements point in opposite directions, and most systems quietly pick
  one. Every version kept immutably is what makes an audit trail worth
  anything; a person may still require that their data be gone.
why: 7
template: essay.html
---

Keep everything, forever, unalterably — that is what makes a trail evidence
rather than a story. Erase this person on request — that is what the law says,
and it is not negotiable either.

Rewriting history to honour the second destroys the first. Refusing the second
to protect the first is not available. Most systems resolve this by choosing,
quietly, and hoping the question is not asked precisely.

## What is destroyed is the key

Identifying material is encrypted inside the payload, with a key belonging to
that person, in the same atomic write that stores the record. Erasure destroys
that key.

History stays byte-immutable. Archives already taken stay valid as files. And
the person's data is cryptographically gone — from the live store, from
history, and from every archive that ever carried it.

--8<-- "assets/diagrams/crypto-shredding.svg"

<p class="diagram-caption">Nothing is rewritten. The history is the same bytes
it was, and the archive that left the building in March is the same file — it
simply no longer opens.</p>

<div class="takeaway" markdown>
Erasure is not a promise to delete rows. It is the destruction of the only
thing that could ever have read them, and it reaches backups nobody has to go
and find.
</div>

## It reaches the copies you cannot recall

This is the part that is otherwise impossible. A backup on tape in somebody
else's building, an archive handed to a departing tenant, a replica in another
jurisdiction — no deletion request travels to those, and no promise about them
is worth anything.

A destroyed key travels to all of them at once, by not travelling at all. The
bytes are wherever they were. Nothing opens them.

## Asked as work, answered with a receipt

Access, portability and erasure are operations the machinery performs, not
tasks a person carries out and then attests to having carried out.

Erasure in particular is asked for as work and answered by a run: what was
found, how far it got, what it could not reach and why. A receipt the system
produced, rather than an email from somebody saying it was handled.

That difference matters most exactly when it is tested. An auditor asking what
happened to a person's data gets the run, its trail and its outcome — the same
evidence for this as for everything else in the store, rather than a separate
story told about a special case.
