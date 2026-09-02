# US-DBO-PARTNER-TRACKING — a partner follows work through the tenants it manages, without being able to read it

> Kaskad resells Meristem's laboratory software to practices too small to
> run anything themselves. Twenty-two of them are Kaskad's, and when one
> phones to say "the result never came back", Kaskad wants to answer
> without phoning Meristem.
>
> Their support sees the run: claimed at 09:14, forwarded to the bench at
> 09:14, attempted twice, released once, closed at 09:31. Each hop with a
> timestamp and the participant that held it — the shape of a parcel
> tracking page, assembled from what the store already recorded.
>
> They cannot see the specimen. They were never able to.

## The scene

A **partner** is a tenant that manages other tenants. The relation is
declared when a managed tenant is created, not inferred later from who
happens to be looking, and it is the only thing that makes a managed tenant's
work visible to anybody outside it.

Kaskad manages twenty-two practices. Meristem manages the rest directly, and
neither can see the other's.

## What tracking is made of

Nothing new is recorded for this. Every hop already leaves something:

- a run says who holds it, until when, and what it produced;
- a release says the claim lapsed rather than that the work was done;
- a milestone says where a failed attempt got to, for whoever takes it next;
- and the tenant's own audit trail records the reads and writes along the way.

So tracking is a **projection of what happened**, not a second record of it.
That matters the first time the two disagree: there is no second record to
disagree with.

## What a partner may see

The distinction the whole story turns on is that **following work and reading
work are different questions**. A partner sees:

- that a run exists, its step, its state, and its timestamps;
- which participant held it at each hop, and what each attempt did;
- that a document was opened, by whom, and for what stated purpose.

A partner does not see the document. The audit entry names the act, not the
content — and a partner reading its managed tenants' trails is reading the
same entries the tenant itself reads, from the tenant's own store.

## Why it is a declared relation

A partner is not a role somebody holds inside a tenant. It is a tenant with a
standing relationship to others, and the relationship has to be created
deliberately, because the alternative is a support desk that can widen its own
reach by asking about more tenants.

## Joins

| Leg | Promised by |
|---|---|
| A run says who holds it, and until when | `REQ-DBO-PROC-RUN-SAYS-WHO-HOLDS-IT` |
| A failed attempt is released, not lost, and says where it got to | `REQ-DBO-PROC-FAILURE-IS-RELEASED`, `REQ-DBO-PROC-PROGRESS-NAMES-THE-MILESTONE` |
| A run names what ran it and what it produced | `REQ-DBO-PROC-RUN-NAMES-WHAT-RAN-IT`, `REQ-DBO-PROC-A-RUN-NAMES-WHAT-IT-PRODUCED` |
| Every read and write is recorded in the tenant's own trail | `REQ-DBO-POL-AUDIT-AS-RECORDS`, `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` |
| The run envelope discloses state rather than the subject | `REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT` |
| What a particular recipient sees is declared, not negotiated | `REQ-DBO-IDN-WHAT-A-RECIPIENT-SEES-IS-DECLARED` |
| A tenant's records are reachable only through that tenant's own store | `REQ-DBO-TEN-STRUCTURAL-SCOPING` |

## What the store cannot do yet

- **There is no partner relation.** A tenant is not managed by another
  tenant in any declared way; the concept does not exist in the spec or the
  registration path.
- **There is no cross-tenant read, by construction.** A store instance *is*
  one tenant's store, and there is deliberately nowhere to name a tenant.
  Whatever serves a partner reads each managed tenant's store separately and
  assembles the answer outside — the store will not grow a query that spans
  them.
- **Hops are reconstructed, not indexed.** The facts exist across runs and
  audit entries; nothing today answers "show me this run's hops" in one ask.
- **`REQ-DBO-PROC-RUN-HAS-A-RECORD` reads PLANNED.** The run record exists and
  is exercised throughout, but the promise that a run *is* a record carries no
  citation yet — so the leg this story leans on hardest is the one the
  catalogue is quietest about.

## Open decisions

- **Where the assembly happens.** A partner-facing view that reads N tenant
  stores is a consumer's job; a store operation that did it would be the
  cross-tenant surface the tenancy design exists to remove. The first is
  almost certainly right and should be written down as such.
- **Whether the audience mechanism carries this.** A managed tenant could
  declare its partner as an audience — which already fixes what a named
  recipient sees — or the partner relation could be its own thing. Reusing
  the audience keeps one mechanism; a separate relation says more clearly
  that this is about management rather than disclosure.
- **Whether a partner sees purposes.** An audit entry carries what a reader
  said the access was for. That is exactly what makes a tracking page useful
  and exactly what a practice might not expect its reseller to hold.
