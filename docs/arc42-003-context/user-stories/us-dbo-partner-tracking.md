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

Nothing is recorded for the tracking page's sake. It is assembled from what
the work already leaves behind:

- a **travel entry per hop** — the thing moved, and who moved it. This is the
  page, and it is deliberately not a data-access record;
- a run that says who holds it, until when, and what it produced;
- a release that says the claim lapsed rather than that the work was done;
- a milestone that says where a failed attempt got to, for whoever takes it
  next.

So tracking is a **projection of what happened**, not a second record of it.
That matters the first time the two would disagree: there is no second record
to disagree with.

## Why the page can be trusted

The entries are chained, each committing to the one before, rooted in the task
the store itself minted. So the page is not merely a list somebody assembled —
**a hop that is missing from it is missing detectably**, because the next hop
commits to it, and a journey that stops leaves the run unclosed rather than
looking finished.

That is what makes it worth showing a customer. A tracking page that could
quietly omit a leg would be worse than no page, because it would be believed.

## What a partner may see

The distinction the whole story turns on is that **following work and reading
work are different questions**. A partner sees:

- that a run exists, its step, its state, and its timestamps;
- which participant held it at each hop, and what each attempt did;
- that a document was opened, by whom, and for what stated purpose — and,
  just as usefully, that across twenty hops **nobody opened it at all**, which
  the travel entries can say positively rather than by silence.

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
| A contributed event carries an actor the machinery stamped, not one the caller claimed | `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` |
| A partner manages tenants: the relation says which, the audience says what of each — *planned* | `REQ-DBO-TEN-A-PARTNER-MANAGES-TENANTS` |
| The page is made of travel entries about the task | `REQ-DBO-POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES`, `REQ-DBO-WF-HOPS-AUDITED` |
| A missing hop is missing detectably | `REQ-DBO-POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK` |
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
- **Hops are not indexed.** A travel entry per hop now exists on the task's
  trail, so the page's central column has its material; nothing answers
  "show me this run's hops" in one ask.
- **`REQ-DBO-PROC-RUN-HAS-A-RECORD` reads PLANNED.** The run record exists and
  is exercised throughout, but the promise that a run *is* a record carries no
  citation yet — so the leg this story leans on hardest is the one the
  catalogue is quietest about.

## Decided in review

- **The view is assembled outside the store.** A store instance is one
  tenant's store, and there is deliberately nowhere to name a tenant; a
  store operation that spanned them would be the cross-tenant surface the
  tenancy design exists to remove. The store's part is to make the relation
  declarable and each trail queryable by task.
- **The relation and the audience compose.** The relation, declared when a
  managed tenant is created, says *which tenants* Kaskad may read at all.
  Within each of them, Kaskad is a declared audience saying *what of each*.
  Making one mechanism do both would overload whichever was picked.
- **Purposes are the audience's to reveal, and are omitted by default.** A
  stated purpose is useful on a tracking page and is also a disclosure about
  a practice's clinical activity to its reseller, so the practice opts in.
- **Kaskad sees the journey and whether the run closed — never chain
  detail.** A run that did not close shows as undelivered. *Why* is the
  practice's and the operator's business: a broken chain discloses a fault
  in somebody else's participant.
