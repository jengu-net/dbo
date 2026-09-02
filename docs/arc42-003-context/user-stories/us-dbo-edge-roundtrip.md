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

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PROC-STEP-SERVICE-EMBEDDABLE` | One embeddable runner registers step services and needs only the participation lane — no orchestrator, no transport, no access to the tenant's dbo — so the same bundle runs inside the platform's container, on a separate machine, or in a pod scaled per step, stateless over the tenants whose lanes it is handed. | PROVEN |
| `REQ-DBO-TEN-STRUCTURAL-SCOPING` | No code path can read or write data without an explicit tenant context. (R3) | PROVEN |
| `REQ-DBO-PROC-A-HOST-HOLDS-A-LANE-WHEREVER-IT-IS` | A host that reaches the store over HTTP obtains the same lane as one that holds the store in-process: the tenant serves the participation verbs on its own private surface, guarded by its own authority, and a runner cannot tell the two apart. The entitlement is derived from the credential and never asked for by the caller, and a credential bounded to steps may work only as itself. | PROVEN |
| `REQ-DBO-PROC-TASK-CARRIES-THE-INPUTS` | The face renders each input as Task.input — the slot name as the parameter's code, the reference displayed rather than resolved, exactly as focus is — in every version the face serves. | PROVEN |
| `REQ-DBO-PROC-A-RUN-NAMES-WHAT-IT-PRODUCED` | A run records the versions it produced, individually up to a cap and as a per-type high-water mark past it, and says which of the two it is. Reading runs in order then reads the content changes in order, so another appliance asks for what it is missing rather than comparing two stores. | PROVEN |
| `REQ-DBO-PROC-CLAIM-IS-THE-INTERSECTION` | What a participant may claim is the intersection of what its credential covers and what the step admits: the lane narrows the work it offers and refuses a claim outside the entitlement, and the store refuses an executor at a scope the step never opened itself to. A step cannot grant its executor more than the executor already holds. | PROVEN |
| `REQ-DBO-PROC-FAILURE-IS-RELEASED` | A failing or throwing step service releases the run with the reason — never closed, never lost — and a later cycle may take it again. | PROVEN |
| `REQ-DBO-POL-AUDIT-AS-RECORDS` | Audit entries are regular, pseudonymous records in the tenant's own store — feed-visible, exported and restored with the tenant, re-identifiable only through the vault. | PROVEN |
| `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` | Every audit entry names its actor from the tenant authority's token (client and subject) — no anonymous mutations under any audited policy. | PROVEN |
| `REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT` | A run's envelope carries holder, step, state and counts — never item references or messages. The envelope is a disclosure surface, and progress must not name what was being processed. | PROVEN |
| `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` | Applications contribute business-level audit events; the machinery stamps actor and time from the validated token and its own clock, overriding caller claims — the trail can be enriched, never impersonated or backdated. | PROVEN |
| `REQ-DBO-CORE-VERSIONED-HISTORY` | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. | PROVEN |
| `REQ-DBO-PROC-WORK-TRAVELS-SEALED` | Work travels in two parts. The manifest — tenant, step, the task, and references to the documents named — is readable, because routing on it is its job. The payload — the documents themselves — is sealed in the carrier form under a data key of its own, wrapped once per participant meant to open it and to nobody who merely carries it. A sealed payload is a copy in flight and not the record: the store keeps the original, and the copy is bounded by the work that caused it. | PROVEN |
| `REQ-DBO-PROC-A-PARTICIPANT-OFFERS-ITS-KEY-AT-ENROLMENT` | A participant generates its keypair before it is enrolled and offers the public half as part of enrolling; the private half never crosses. Payload data keys are wrapped to that key, so what a participant may open is decided by what it holds rather than by what it is told. | PROVEN |
| `REQ-DBO-POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES` | One trail; the target says what an entry is about. A hop that carried work leaves a travel entry about the task. A participant that opened a payload leaves an access entry about the document, landing where every other reading of it lands and naming the task execution as its occasion. The machinery's own read to seal a payload records nothing: a read that yields only ciphertext is not a disclosure. So who read this is answered from the document by somebody who need not know work exists, and where did this go from the task, and the trail can say that nobody looked. | PROVEN |
| `REQ-DBO-WF-HOPS-AUDITED` | Every hop leaves a travel entry about the task — who handed to whom — and a travel entry is not a reading: audit of the journey is structural, not per-integration, and it never says anybody looked at the content. | PROVEN |
| `REQ-DBO-POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK` | A run's travel and access entries each carry a link to the one before, rooted in the task the store minted, so a participant cannot present a journey that never started. The result that closes the run is the last link and carries the head it commits to; the store checks the chain when the result lands, and a completion with a gap is refused and told which link. A travel entry names who it handed to, so a skipped hop is exposed by the next author. Links are signed by the participant, which buys non-forgery and non-repudiation and not omission-proofing: an intended recipient can open a payload and never say so, and that limit is accepted. The link lives on the entry, so the chain outlives nothing the trail does not, and a pruned predecessor reads unchained rather than broken. | PROVEN |
| `REQ-DBO-PROC-A-LANE-OVER-THE-STREAM` | A lane runs over the store's own stream, full duplex, beside in-process and HTTP: work goes out and travel, access and result events come home as they happen on the same channel. It serves exactly the verbs the other two do, and a runner cannot tell which it holds. | PROVEN |
| `REQ-DBO-PROC-THE-ROUTER-HOLDS-THE-CLAIM` | The thing that can reach the store is the participant, and it holds the claim. An instrument behind a router is routed because it cannot reach the lane, so the router claims, forwards, waits and reports — holding a claim on work it cannot read — while the instrument holds the key and does the work. Participant versus routee is a fact about the attachment, not the device. | PROVEN |
| `REQ-DBO-PROC-DONE-MEANS-DONE` | A participant does not report done before the work is done. A run closes on what is reported and the store has no view below that seam, so an early report is a true-looking record of something that has not happened. A participant with durable execution underneath waits for it; a router waits for its edge; a wedged one lets the claim lapse and the run reads released. | PROVEN |
| `REQ-DBO-WF-TWO-PLANES` | Records live in the tenant plane, structurally isolated. The shared platform plane carries coordination and the copies work needs in flight — manifests readable, because routing is what they are for, and payloads sealed to the participant meant to open them. Isolation of a record is structural; of a copy in flight, cryptographic. | PROVEN |
| `REQ-DBO-WF-CONTENT-FREE-PLATFORM-PLANE` | The platform plane never holds tenant credentials, and never holds resource content in a form readable in that plane. A sealed payload satisfies this; the plaintext form would not, however briefly. | PROVEN |

Coverage: {PROVEN=22} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

Nothing this story tells. Every leg above is proven over the HTTP lane and
the stream, and the gaps it was written with have closed one by one; it stays
whole so the next gap has somewhere to be visible.

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
