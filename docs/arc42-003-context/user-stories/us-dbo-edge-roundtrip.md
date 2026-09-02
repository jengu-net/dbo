# US-DBO-EDGE-ROUNDTRIP — one synchronisation service serves every tenant's devices, and reads a payload only when it has to

> Meristem builds laboratory software. Their analysers sit in practices
> all over the country, and each practice is a tenant of their own
> deployment of this store.
>
> They run **one** synchronisation service. It is not per tenant, and it
> was never going to be — a service per practice is a fleet to operate
> before it is a feature. It enrols each analyser, carries work out to it,
> and carries results back.
>
> Most of what it carries, it cannot read. A worklist arrives as an
> manifest it routes on and a payload it never opens. When the analyser
> genuinely needs the specimen document, the store's own callback opens it
> *there*, on the analyser, and says so back down the channel. That saying
> is what the practice sees in its audit trail as a reading. The twenty
> hops that carried it unopened are in the trail too — as travel, which is
> a different thing and is what makes the reading legible.

## The scene

Meristem's builder, Ines, is integrating a device fleet. She has:

- one **synchronisation service**, shared across every tenant, in its own JVM;
- an **analyser** in each practice, bound to exactly one tenant;
- a store that already separates tenants by giving each its own database.

What she is not willing to build is a copy of the store's work model on her
side of the wire, and what she is not allowed to build is a service that can
read every practice's specimens because it happens to carry them.

## Enrolling an analyser

The analyser generates its own keypair before it is ever enrolled and offers
the public half as part of enrolling. The private half never crosses, so a
copy of the enrolment records opens nothing.

From then on the store seals payloads it sends to that analyser, and the
analyser seals what it sends back. Ines writes none of this: the store's
enrolment toolset is what exchanges the keys, and her service is the thing
that carries the sealed bytes.

## Work goes out

A run is claimed for a tenant, and what travels is two parts:

- the **manifest** — which tenant, which step, the task, and *references* to
  the documents the work names. Readable, because routing is what it is for.
- the **payload** — the documents themselves. Sealed.

Ines's service reads the manifest, decides which analyser the work belongs
to, and forwards it. **It never holds a key.** The one thing it must not be
able to do is the one thing it structurally cannot.

## Everything the worker touches comes through the substrate

Ines's step code holds no handle to any tenant's store, and there is nothing
for it to hold: work arrives on the stream and results leave on it. That is
not a restriction she has to remember — it is the only door there is.

## The analyser opens what it needs

The analyser's step wants the specimen document, so it asks the callback the
store put on its side of the wire. The plaintext never crosses the network:
**the payload is opened where it was going anyway**, and what travels back is
the fact that it happened.

Two things happen together and neither is optional:

1. the payload comes back readable, on the analyser;
2. an access event goes home on the return channel, and is recorded against
   this participant, this run and this document.

**Most steps never ask**, and for those there is no data-access entry at all
— because none happened. The trail records reading rather than carrying,
which is what makes it worth reading.

## Every hop says it happened, and that is not a reading

Each carry leaves a travel entry **about the task** — the journey belongs to
the work. The access entry above is **about the document**, and lands exactly
where every other reading of that document lands, naming the task execution as
its occasion.

That is what decides who can ask what. The practice asking *who has read this
result?* reads the document's own entries and never has to know that work
exists as a concept. Ines asking *where did this task go?* reads the task's.
Neither question has to understand the other, and the occasion is the join
when somebody wants to cross between them.

Collapsing the two into one target would fill a document's history with
carries nobody made, and bury the one reading that matters.

It also means the trail can say **nobody looked** — the gap between two travel
entries is positive evidence rather than missing information.

## The events come home on the same channel, chained

The stream is full duplex, so the access and travel events flow back as they
happen rather than being reconciled afterwards. Each links to the one before,
and the chain is rooted in the task itself — which the store minted, so a
participant cannot present a journey that never started.

The result message is the last link and the one that closes the run, which it
already was. So the check has a natural home: when the result arrives, its
chain is verified as part of closing the work. A link that never came home is
visible because the next one commits to it, and a chain that simply stops
leaves the run unclosed and the work owed — which is a state this store
already surfaces.

## And back

The analyser writes its result, the payload is sealed again, and the same
service carries it home. Ines watches the run close with its tally in the
tenant's own store. Nothing about the result travelled through a database she
operates and can read.

## Joins

| Leg | Promised by |
|---|---|
| One service, many tenants, no state carried between them | `REQ-DBO-PROC-STEP-SERVICE-EMBEDDABLE`, `REQ-DBO-TEN-STRUCTURAL-SCOPING` |
| The same lane across a wire as in the container, and a worker that never holds a store handle | `REQ-DBO-PROC-A-HOST-HOLDS-A-LANE-WHEREVER-IT-IS` |
| The work names its inputs, and the run says what it produced | `REQ-DBO-PROC-TASK-CARRIES-THE-INPUTS`, `REQ-DBO-PROC-A-RUN-NAMES-WHAT-IT-PRODUCED` |
| A participant may claim only what its credential and the step allow | `REQ-DBO-PROC-CLAIM-IS-THE-INTERSECTION` |
| Killing the analyser mid-work loses neither half | `REQ-DBO-PROC-FAILURE-IS-RELEASED` |
| Opening a document is recorded against the purpose it was opened for | `REQ-DBO-POL-AUDIT-AS-RECORDS`, `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` |
| The envelope discloses state, not the subject | `REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT` |
| A participant contributes an event and cannot forge who or when | `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` |
| A version links to the one before it, so a rewrite is detectable | `REQ-DBO-CORE-VERSIONED-HISTORY` |
| Work travels as a readable manifest and a sealed payload, in the carrier form, wrapped per participant | `REQ-DBO-PROC-WORK-TRAVELS-SEALED` |
| The analyser offers its public key when it enrols | `REQ-DBO-PROC-A-PARTICIPANT-OFFERS-ITS-KEY-AT-ENROLMENT` |
| Carrying and reading are different entries, on the task and on the document | `REQ-DBO-POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES`, `REQ-DBO-WF-HOPS-AUDITED` |
| The events come home chained from the task, and the result is the last link | `REQ-DBO-POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK` |
| One duplex channel carries work out and events home | `REQ-DBO-PROC-A-LANE-OVER-THE-STREAM` |
| The service holds the claim and waits for the analyser | `REQ-DBO-PROC-THE-ROUTER-HOLDS-THE-CLAIM`, `REQ-DBO-PROC-DONE-MEANS-DONE` |
| The shared plane holds the sealed copy and nothing readable — *stated, proofs pending* | `REQ-DBO-WF-TWO-PLANES`, `REQ-DBO-WF-CONTENT-FREE-PLATFORM-PLANE` |

## What the store cannot do yet

- **The plane proofs are not written.** That the shared plane holds nothing
  readable is stated and the sealing is proven on the wire; the ratchet that
  no plaintext lands in the substrate is its own issue.

Every leg above that is `PROVEN` is proven over the HTTP lane. The story is
written whole so the gap is visible as a gap.

## Decided in review

- **Sealed per payload, wrapped per participant.** Per tenant would hand the
  shared service the key — it is enrolled in every tenant it serves, and it
  is exactly the carrier being excluded. The archive format already has the
  shape: a random data key, wrapped once per recipient.
- **What is sealed is the carrier form** — the record as the store's existing
  encrypted disclosure mode would hand it out, identifying elements already
  under the person's key. So a sealed copy still in flight after an erasure
  is in the same state as the store's own records after a shred, and no
  special case is needed.
- **The participant computes and signs its links**, with the key it was
  enrolled with. That buys non-forgery and non-repudiation. It does not stop
  an intended recipient from opening a payload and never saying so, and the
  story does not pretend otherwise: the data was legitimately theirs, and
  the gap is an audit entry for an authorised read on a device the tenant
  answers for.
