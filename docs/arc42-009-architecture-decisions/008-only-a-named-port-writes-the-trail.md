**Status: Resolved.** Reflected in [records you can rely on](../arc42-008-crosscutting/records-you-can-rely-on/README.md).

# Only a named port may write the trail

**VERDICT: a named port, held by the receiver.** `PolicyObjectStore` refuses
every direct write to the audit type, for everybody, and keeps doing so. The
replication lane gets through by being `AuditReplay.replayAuditEntry` — one
narrow, purpose-named method that can express no other write — and the refusal
names it, so a reader who meets the refusal finds the admission from where they
are standing.

The question came out of the replication toolset's last bullet: an appliance's
audit entries have to reach its peer with the original actor, the original time
and the appliance named, and the arrival must write no second trail. Three
shapes could have delivered that.

**A caller authority was rejected.** A dedicated `Handling.Authority` for the
lane would have reused vocabulary that already travels with every write — and
turned a *handling* vocabulary into an *authorization* one. The append-only
shield is written in those terms, so the line it guards would have become
subtler exactly where it needs to be blunt, and anyone who could name the
authority could write somebody else's history.

**Writing beneath policy was rejected**, though it is what the lane already did
and would have been the smallest change. It bypasses every policy rather than
the one that is in the way — retention discipline, append-only rules, reach —
and the argument for it is an argument about audit alone. A position that broad
should not be arrived at because nothing wired it otherwise.

**What the port costs, stated.** One more seam, and the receiving store stays
policy-wrapped — which means ordinary replicated *content* is audited on
arrival, as a receipt. That is deliberate: the edge's entry says who did the
work, the cloud's says it received a copy, and the two are different facts
about different events. Only the trail itself is exempt, because a copy of an
event is not an event and a store that audited its own replication would grow
one entry per entry, forever.

**What the port carries is the source's, all of it** — appliance, id, version,
bytes, time. Nothing is re-derived on arrival: an entry rebuilt here would say
what this side can express rather than what the other side recorded. The claim
on the source's identity is what makes an at-least-once lane land exactly once
(an existing mechanism, reused rather than reinvented), and it is also why a second
delivery cannot rewrite the first — a replayed entry is appended or found,
never updated.

**The shield is untouched.** Appending an entry recorded elsewhere and altering
an entry that exists are different acts; the second stays refused for every
caller, the vendor included.
