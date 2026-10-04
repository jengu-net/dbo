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
different sentence from "nothing is declared". Resolution reads presence and
nothing a participant says about itself, so a component that has frozen cannot
report that it is fine. The subtlety worth knowing: a participant with *nothing
to do* also stops moving, so silence is only absence when there is work waiting.

**Contact is the application's.** Whether a node has heard a worker for a step
within some silence decides nothing in the store, and the store keeps none of
it. An application that cares registers a listener for the step, naming how
long a worker may say nothing — there is no default — and is told when this
node hears a worker appear, each heartbeat's statistics while it is heard, and
when it has been silent past that threshold; a node that starts tells every
listener that everything about its step is unknown there. Anything a worker
asks for that step is activity, and a **heartbeat** — a verb of the lane that
writes nothing and extends no claim — is how a worker that is woken rather
than polling, or one holding a long claim, stays heard. Its statistics are one
document, nested and namespaced by whoever contributed them, with `dbo.`
reserved for the runner's own counts; a node refuses one over its limit, and
nothing about a person goes in one, because a heartbeat travels outside sealed
work. What contact the application wants kept, it asks for as work, under a key
of its own, and the step writes the record.

**Some workers are reached through another.** A worker with no lane of its own
— one behind a serial line, or inside a network nobody reaches — is reached by
a participant that has one, which reports whom it routes, however many hops
away. The store keeps one fact about each routee: the participant it sits
behind. What a routee is and how it is doing are the router's to say in its
heartbeat statistics.

**The thing that can reach the store is the participant, and it holds the
claim.** A routee is routed *because* it cannot reach the lane, so the router
claims the run, forwards it, waits, and reports — holding a claim on work it
cannot read, which sounds strange and is exactly the point. The routee holds
the key and does the work. Participant versus routee is a fact about the
attachment, not about what the worker is: a worker with its own lane is a
participant, and the same worker behind a router is a routee.

A router reports the whole set behind it each time, so a routee left out of
its latest report is no longer behind it, and nothing is sealed to it until a
report names it again.


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
of the enrolment records opens nothing and signs nothing.

**The private halves are the participant's own custody, and never the store's.**
An application that performs work states them — the worker IS the participant,
and its enrolment is what makes it that one. It is tempting to read that as an
untidiness, and to want a worker's keys kept where the store keeps its own key
material. It is not untidy, and moving them would not rearrange the guarantee:
it would delete it. What a signature buys is that a carrier cannot manufacture
a participant's ask and a participant cannot deny one it signed, and both rest
on exactly one fact — that nobody but the participant holds the half that
signs. The store is the party the signature is shown to. A store able to
produce that signature is a store whose evidence means nothing, including
against itself. So where an application reads its private halves from — an
environment variable, a mounted secret, a file — is a deployment's business,
and the store's business is never to be told. From then on each payload sent to it is sealed
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

**A worker beside the store keeps a pool of its own onto the substrate.** A
worker in the same process as the serving half — the clinic's application
holding St Jerome's lane in Rowling Land is one — does not share the serving
half's pool. The store manages its connections per tenant on purpose, and both
pools are sized from what they carry: the lane's from the lanes it holds, five
connections for one, and the serving side's by three for each tenant's door as
it opens. Sharing would save little and cross that line.
