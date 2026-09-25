# Participants, and what they may see

Who is out there, how the store knows it, and the split between what a
participant may read and what is sealed past it. The contract they take part
in is [processes and work](README.md).

## Who is out there

Nobody registers a participant in a configuration file. A participant
**announces itself**: which step it performs, in which version, on whose behalf,
at what scope. Resolution walks those announcements, which is what lets a local
implementation and a member's own system be two candidates for the same step,
ranked by how local they are rather than by which machine they run on.

**Presence is worked out, not claimed.** A participant keeping up with what it
asked for is present; one that is behind and not moving is not — and that is a
different sentence from "nothing is declared". Nobody sends a heartbeat, so a
component that has frozen cannot report that it is fine. The subtlety worth
knowing: a participant with *nothing to do* also stops moving, so silence is only
absence when there is work waiting.

**Some things cannot speak for themselves.** An instrument on a serial cable has
no cursor and no credential; neither does a meter in a substation or a sensor in
a container. Each is reached by something that does, and that thing reports what
it can see behind it, however many hops away. The store keeps one row per thing
whose state is worth knowing, at any depth, so the rule about what a state is
exists once rather than once per reporter. What it will not do is decide whether
a report is stale — it has no path of its own to check, and one freshness
threshold across a serial line and a network socket would be wrong for both.
Where something has a cursor, presence is derived from it; where it does not, the
record carries who last saw it and when, because "where it sits" and "who to ask
about it" are different questions.

**The thing that can reach the store is the participant, and it holds the
claim.** An instrument behind a router is routed *because* it cannot reach the
lane, so the router claims the run, forwards it, waits, and reports — holding a
claim on work it cannot read, which sounds strange and is exactly the point. The
instrument holds the key and does the work. Participant versus routee is a fact
about the attachment, not the device: a bench with its own lane is a participant,
and the same bench behind a router is a routee.

A routee that stops being reported is a statement, not a gap. A router reports
the full set behind it, so an absence from that report is something the router
said — distinguishable from a quiet router, whose cursor did not move. The store
keeps a departed routee with its last attestation and marks it no longer
reported, so "gone" reads as *last seen by X at T, absent from X's report at
T+1*: absence with a timestamp, which is a fact.


## What a participant may see and do

A participant's whole world is a few verbs: ask for work, take it, report on it,
read the documents that work names. It never holds a handle to the store, and no
request takes a reference — so it cannot ask for data, relevant or not. It
receives what the work it holds entitles it to, resolved by the side that
legitimately has it.

What it receives has two parts, and the split is what lets one participant serve
many tenants without reading any of them. The **manifest** — which tenant, which
step, the task, and *references* to the documents the work names — is readable,
because routing on it is its job. The **payload** — the documents themselves —
is sealed to the participant meant to open it. Whoever merely carries the work
reads the manifest and holds no key.

What it may work on is the **intersection** of what its credential covers and
what the step admits. Neither widens the other: a step cannot grant its executor
more than the executor already holds, and a credential cannot reach a step that
never opened itself to that kind of participant. There is no implicit
unrestricted — reach is stated when a participant is provisioned, so nobody's
access depends on a parameter somebody forgot.

**A participant is sealed to, and a carrier is not.** A participant offers two
public keys when it enrols — one it is sealed to, one it signs with, because
the curve that agrees cannot sign; the private halves never cross, so a copy
of the enrolment records opens nothing and signs nothing. From then on each payload sent to it is sealed
under a data key of its own, wrapped to that participant — and to nobody who
merely carries it. That is the store's usual answer applied to transport: a
carrier that holds no key cannot read what it moves, whatever it is told it may
do, and the arrangement needs no trust in the carrier to hold.

Three consequences are worth stating because each could have gone the other way.
The seal is **per payload, wrapped per participant**, not per tenant — a carrier
enrolled in a tenant would otherwise hold that tenant's key, and the carrier is
the thing being excluded. What is sealed is the **carrier form** — the record as
the store's own encrypted disclosure mode hands it out, identifying elements
already under the person's key — so a sealed copy still in flight after an
erasure is in the same state as the store's own records after a shred. And a
sealed copy is **a copy in flight, not the record**: the store keeps the
original, still indexes and searches it, and the copy is bounded by the work
that caused it.

What a participant holds decides how its work arrives. One that offered a
key at enrolment is answered with a manifest and sealed payloads, and is
refused its inputs in the clear even when it asks; one that offered none is
served in the clear, as every participant was before there was anything to
seal to, and is refused a seal by name. A router names its routee as the
recipient and is sealed past: it may name only what it has declared behind
it, naming is the forward and leaves the travel link that makes the routee
the chain's next author, and the opening it carries home is its routee's,
signed with the routee's own key.

The lane has three carriers and a runner cannot tell which it holds:
in-process, HTTP, and the store's own stream. The third is the one a shared
fleet holds. The host connects to the durable substrate it already runs on,
each served tenant opens a door there — one long-lived workflow, guarded by
the same authority and the same participation scope as the HTTP door — and a
verb is a message to that door with its answer an event on it. Work goes out
and the signed openings and the result come home on the one channel, no
tenant accepts a callback, and the verbs are encoded once for both wires so
nothing can be served on one that the other cannot carry. The plane between
holds no credential and nothing readable: an ask is signed with the
participant's enrolment key rather than carrying a token, so a lane on the
stream is held only by a participant enrolled with both keys, and the clear
verb is refused there by name. A container given
no substrate serves its lanes over HTTP and in-process only, as every
container did before the fleet.
