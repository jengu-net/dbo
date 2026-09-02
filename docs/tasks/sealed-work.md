# Sealed work

**Status** — designed and decided; six slices built — travel and access
are different entries, a participant offers its keys at enrolment, work
leaves as a readable manifest and a payload sealed past the carrier to the
participant named, the run's entries are chained from the task with the
result as the last link, a router holds the claim for the edge behind it,
and the lane runs over the store's own stream beside HTTP and in-process. Ten issues carry it,
three of them re-scoped from questions to builds.

**Issues** — roots: [#166](https://github.com/jengu-net/dbo/issues/166)
(enrolment key exchange), [#175](https://github.com/jengu-net/dbo/issues/175)
(travel and access entries), [#177](https://github.com/jengu-net/dbo/issues/177)
(duplex lane over the stream) · then:
[#174](https://github.com/jengu-net/dbo/issues/174) (sealing, on #166),
[#176](https://github.com/jengu-net/dbo/issues/176) (the chain, on #175 and #166),
[#179](https://github.com/jengu-net/dbo/issues/179) (partner, on #175) ·
beside: [#172](https://github.com/jengu-net/dbo/issues/172) (perform waits),
[#173](https://github.com/jengu-net/dbo/issues/173) (plane rules),
[#178](https://github.com/jengu-net/dbo/issues/178) (departed routee),
[#180](https://github.com/jengu-net/dbo/issues/180) (stories cited by promises)

**Stories** — [edge round trip](../arc42-003-context/user-stories/us-dbo-edge-roundtrip.md) ·
[fleet health](../arc42-003-context/user-stories/us-dbo-fleet-health.md) ·
[partner tracking](../arc42-003-context/user-stories/us-dbo-partner-tracking.md) ·
[trail answers](../arc42-003-context/user-stories/us-dbo-trail-answers.md)

**Concepts** — [processes and work](../arc42-008-crosscutting/processes-and-work.md) ·
[data isolation](../arc42-008-crosscutting/data-isolation.md) ·
[engine and faces](../arc42-008-crosscutting/engine-and-faces.md)

## What this is

One runner fleet carries every tenant's work and cannot read most of it.

Today work travels as plain bytes, so whatever carries a run can read it. That
is fine while every carrier is the tenant's own machinery and stops being fine
the moment one fleet serves every tenant — which is the design: separate
processes, one shared substrate database, every execution in one tenant's
context. The property that makes it safe is structural rather than authorised.
**A carrier that holds no key cannot read what it carries**, whatever it is told
it may do.

Four things have to become true for that sentence to hold while the fleet
stays convenient, and each is an issue above: work splits into a readable
manifest and a sealed payload; opening a payload and carrying one become
different kinds of audit entry; the entries of a run are chained from the task
the store minted so the trail's completeness can be checked; and the lane runs
over the store's own stream in both directions so one service needs no
callback into every tenant.

## Where it stands

Built: a hop leaves a travel entry on the task; a keyed participant's inputs
leave as a manifest plus payloads sealed under a per-payload key wrapped to
its enrolment key, and it opens them on its side and reports each opening,
which lands on the document as the access entry naming the run; the store's
own read to seal records nothing, proven at audit level full; a keyless
participant is served in the clear and refused a seal by name; the carrier
form is what is sealed, so a shred reaches a copy in flight with no special
case; a router names its routee as the recipient, is sealed past, carries
the routee's signed opening home and closes on the head it left, and a
routee that never answers lets the claim lapse so the run reads released.

Everything else it rests on already does:

- the sealing format — `SealedArchive` mints a random data key per payload and
  wraps it, which is the multi-recipient shape one wrap per participant away;
- the hash chain — `VersionChain`, length-prefixed SHA-256 with an explicit
  genesis, and the reasoning for it written at the class;
- the contributed-event path — a participant already contributes an audit
  event, the machinery stamps actor and time from the validated token, and a
  forwarded id makes a re-delivered one land once;
- the fleet tree — routers report what is behind them, presence is derived
  from a cursor, and `/t/{code}/fleet` reads it from outside the container.

Every promise the stories lean on that reads `PROVEN` is proven *for work in
the clear*. That is the distance, and the issues are the wiring between parts
that exist plus the words to tell one record from another.

## Decisions

Taken in review, all ten, and folded into the stories where each one lands.
Kept here in one place so an implementer does not re-litigate them.

**The router holds the claim.** An edge sits behind a router because it cannot
reach the lane — that is why the router exists — so the claim sits with what
can reach the store and the key with what will do the work. The router holds a
claim on work it cannot read, waits for its edge, and a wedged edge lets the
claim lapse. *Participant versus routee is per attachment, not per device.*

**What is sealed is the carrier form.** The machinery seals what the store's
existing encrypted disclosure mode would hand out — identifying elements
already under the person's key — so the person-key layer sits inside the
transport seal. A sealed copy still in flight after an erasure is then in the
same state as the store's own records after a shred, with no special case.
This is also why the machinery's read to seal records no access: a read that
yields only ciphertext is not a disclosure.

**Sealed per payload, wrapped per participant.** Per tenant would hand the
shared router the key, and the router is the carrier being excluded.

**Ciphertext in the shared plane is not resource content**, and the plane
promise is reworded to say *readable in that plane* so the letter and the point
cannot drift.

**One trail; the target is what differs.** A travel entry targets the task.
An access entry targets the document, landing where every other reading of it
lands, and names the task execution as its occasion. *Who read this?* is
answered from the document by somebody who need not know work exists; *where
did this go?* from the task. The occasion is the join.

**The result is the terminal link, and always was.** The chain is verified
when the result lands, which the store handles anyway. The result carries the
head it commits to. **A completion with a gap is refused and told which link**
— lost in transit resends; a link that cannot be resent never existed, and the
run stays owed with a name on it.

**A travel link names who it handed to**, which is what makes a skipped hop
detectable: the next author is always predictable.

**The participant computes and signs its links** with the signing half of its
enrolment keys — Ed25519 beside the X25519 it is sealed to, because the curve
that agrees cannot sign. That buys non-forgery and non-repudiation. It does not stop an intended recipient
from opening a payload and never saying so — the alternative, unwrapping on
the store's side, breaks offline edges and turns a structural exclusion into
a filter, and was declined knowing the cost. The data was legitimately theirs;
what is lost is an audit entry for an authorised read on a device the tenant
answers for. *This is the one decision where the cheap answer has a real
cost.*

**The link lives on the entry.** The chain's lifetime is the trail's, and a
pruned predecessor reads as unchained rather than broken.

**A departed routee is a statement.** Kept with its last attestation, marked
no longer reported.

**A partner view is assembled outside the store**; the relation says which
tenants, the audience says what of each; purposes are the audience's to
reveal, default omitted; a partner sees the journey and whether the run
closed, never chain detail.

## Sequence

| step | promise (REQ-DBO-…) | status |
|---|---|---|
| Travel and access as distinct entries; one trail, two targets — #175  | `POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES` · `WF-HOPS-AUDITED` | **DONE** 2026-09-02, for work in the clear; the decrypt-callback weld moves to #174 |
| Duplex lane over the store's stream — #177  | `PROC-A-LANE-OVER-THE-STREAM` | **DONE** 2026-09-02; one door workflow per tenant on the substrate, verbs serialised per tenant |
| Enrolment key exchange — #166  | `PROC-A-PARTICIPANT-OFFERS-ITS-KEY-AT-ENROLMENT` | **DONE** 2026-09-02; the constraints now state the one asymmetric exception (R5) |
| Stories cited by promises — #180  | `PRM-A-STORY-IS-CITED-NOT-CLAIMED` | **DONE** 2026-09-02; the projection caught a renamed promise in a hand table on its first run |
| Manifest/payload split and sealing in the carrier form — #174  | `PROC-WORK-TRAVELS-SEALED` | **DONE** 2026-09-02, wrapped to the claimant; routee recipients move to #172 |
| The chain: link on entry, travel names recipient, participant signs, result carries head, refuse-and-name — #176  | `POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK` | **DONE** 2026-09-02; a forward's travel link waits for a router that forwards (#172) |
| Partner relation composed with audience — #179  | `TEN-A-PARTNER-MANAGES-TENANTS` | **READY**, needs the partner relation declared in the registration path; owner: the store |
| `perform` waits, one hop further for a router; a router names its routee as the recipient — #172  | `PROC-DONE-MEANS-DONE` · `PROC-THE-ROUTER-HOLDS-THE-CLAIM` | **DONE** 2026-09-02 |
| Plane promise reworded, plaintext ratchet, erasure-reaches-the-copy proof — #173  | `WF-TWO-PLANES` · `WF-CONTENT-FREE-PLATFORM-PLANE` | **DONE** 2026-09-02; the ratchet caught a token and a clear verb on the plane before it passed |
| Departed routee kept with last attestation — #178  | `PROC-A-DEPARTED-ROUTEE-IS-A-STATEMENT` | **DONE** 2026-09-02 |

Critical path: none left. Every sequenced step is done; what keeps this
file open is the partner relation, which has its own issue and shape.

## Not doing

**Store-side unwrapping of the data key.** It would mint the access entry
where the participant cannot skip it, and it was declined: it breaks offline
edges, re-admits the carrier as a potential asker, and turns a structural
exclusion into a filter. The limit it leaves is stated in the trail story.

**Any cross-tenant query in the store**, for the partner view or otherwise. A
store instance is one tenant's store; the view is assembled outside.

**A freshness rule for the fleet.** The store records when a silence began and
who last saw the thing; what it means depends on the hop's cadence, which only
the caller knows.

**Naming an orchestrator in the participation contract.** The constraints name
the substrate as the store's own; the seam still names none, and both are
true.

## When this closes

The four stories' *what the store cannot do yet* sections empty out; the
decisions above move into the concept documents they belong to; this file is
deleted.
