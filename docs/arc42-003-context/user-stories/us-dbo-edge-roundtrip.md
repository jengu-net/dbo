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
> envelope it routes on and a payload it never opens. When the analyser
> genuinely needs the specimen document, it asks, the store hands it back
> in the clear, and that asking is the thing the practice sees in its
> audit trail — not the twenty hops that carried it there unopened.

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

- the **envelope** — which tenant, which step, the task, and *references* to
  the documents the work names. Readable, because routing is what it is for.
- the **payload** — the documents themselves. Sealed.

Ines's service reads the envelope, decides which analyser the work belongs
to, and forwards it. **It never holds a key.** The one thing it must not be
able to do is the one thing it structurally cannot.

## The analyser opens what it needs

The analyser's step wants the specimen document, so it asks the store's
callback for the decrypted payload. Two things happen at once and neither is
optional:

1. the payload comes back readable;
2. the store records that this participant, at this moment, for this run,
   opened this document.

**Most steps never ask**, and for those there is no data-access entry at all
— because none happened. The audit trail is a record of reading rather than a
record of carrying, which is what makes it worth reading.

## And back

The analyser writes its result, the payload is sealed again, and the same
service carries it home. Ines watches the run close with its tally in the
tenant's own store. Nothing about the result travelled through a database she
operates and can read.

## Joins

| Leg | Promised by |
|---|---|
| One service, many tenants, no state carried between them | `REQ-DBO-PROC-STEP-SERVICE-EMBEDDABLE`, `REQ-DBO-TEN-STRUCTURAL-SCOPING` |
| The same lane whether the participant is in the container or across a wire | `REQ-DBO-PROC-A-HOST-HOLDS-A-LANE-WHEREVER-IT-IS` |
| The work names its inputs, and the run says what it produced | `REQ-DBO-PROC-TASK-CARRIES-THE-INPUTS`, `REQ-DBO-PROC-A-RUN-NAMES-WHAT-IT-PRODUCED` |
| A participant may claim only what its credential and the step allow | `REQ-DBO-PROC-CLAIM-IS-THE-INTERSECTION` |
| Killing the analyser mid-work loses neither half | `REQ-DBO-PROC-FAILURE-IS-RELEASED` |
| Opening a document is recorded against the purpose it was opened for | `REQ-DBO-POL-AUDIT-AS-RECORDS`, `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` |
| The envelope discloses state, not the subject | `REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT` |

## What the store cannot do yet

- **Payloads are not sealed.** Work travels as plain bytes; there is no
  encryption on the transport at all.
- **There is no enrolment key exchange.** No asymmetric encryption exists in
  the production sources, and the one key that does exist is a single
  framework key shared by every tenant — so there is nothing per-tenant to
  seal with.
- **There is no decryption callback**, and therefore no access event
  distinct from a transport event.

Every leg above that is `PROVEN` is proven for work in the clear. The story
is written whole so the gap is visible as a gap.

## Open decisions

- Whether the payload seal is per tenant or per enrolled participant. Per
  participant is what makes a shared carrier structurally unable to read;
  per tenant is simpler and leaves the carrier out of the key set anyway.
- Whether an unopened payload leaves any trace at all. Silence is the honest
  answer and makes the trail meaningful; a carrier that logged every hop
  would drown the entries that matter.
