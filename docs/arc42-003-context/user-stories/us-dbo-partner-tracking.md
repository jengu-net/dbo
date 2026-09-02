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

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PROC-RUN-HAS-A-RECORD` | Every run of a step is a record in a tenant's own store — a registered type, so it is envelope-queryable, versioned, carried by the backup and dropped with the tenant. A run in a private table has none of those, and cannot be seen or acted on. | PLANNED |
| `REQ-DBO-PROC-RUN-SAYS-WHO-HOLDS-IT` | A run's load-bearing field is who holds it now: automation running, automation with a retry scheduled, a person, or nobody. Every other field answers a question somebody asks after that one. | PROVEN |
| `REQ-DBO-PROC-RUN-NAMES-WHAT-RAN-IT` | A run records the executor, its version, its provider and the scope it was chosen at. A provider can be withdrawn and a scope re-declared, so a resolution nobody wrote down is a decision nobody can reproduce. | PROVEN |
| `REQ-DBO-PROC-PROGRESS-NAMES-THE-MILESTONE` | A checkpoint can carry the milestone reached; the run records it replaced-never-accumulated, with its position over the declared order derived by the store rather than asserted by the executor, and it survives release and retake. A service that reports nothing behaves exactly as today. | PROVEN |
| `REQ-DBO-PROC-A-RUN-NAMES-WHAT-IT-PRODUCED` | A run records the versions it produced, individually up to a cap and as a per-type high-water mark past it, and says which of the two it is. Reading runs in order then reads the content changes in order, so another appliance asks for what it is missing rather than comparing two stores. | PROVEN |
| `REQ-DBO-PROC-FAILURE-IS-RELEASED` | A failing or throwing step service releases the run with the reason — never closed, never lost — and a later cycle may take it again. | PROVEN |
| `REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT` | A run's envelope carries holder, step, state and counts — never item references or messages. The envelope is a disclosure surface, and progress must not name what was being processed. | PROVEN |
| `REQ-DBO-IDN-WHAT-A-RECIPIENT-SEES-IS-DECLARED` | What may leave and what this particular recipient may see are different questions, and a tenant answers the second by declaring an audience: which types it is answered about at all, and what a read of one of them reveals. A type outside the declaration is absent rather than refused, because a refusal naming it would tell the recipient it exists. The mode follows the declaration rather than the request — a recipient that could ask for more would make the declaration advice — and an audience nobody declared sees nothing, because a typo in a serving surface and a partner who was removed both want silence. Naming no audience is the tenant working with its own records, and nothing about it changes. | PROVEN |
| `REQ-DBO-POL-AUDIT-AS-RECORDS` | Audit entries are regular, pseudonymous records in the tenant's own store — feed-visible, exported and restored with the tenant, re-identifiable only through the vault. | PROVEN |
| `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` | Every audit entry names its actor from the tenant authority's token (client and subject) — no anonymous mutations under any audited policy. | PROVEN |
| `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` | Applications contribute business-level audit events; the machinery stamps actor and time from the validated token and its own clock, overriding caller claims — the trail can be enriched, never impersonated or backdated. | PROVEN |
| `REQ-DBO-TEN-A-PARTNER-MANAGES-TENANTS` | A partner is a tenant that manages other tenants, declared when the managed tenant is created. The relation says which tenants the partner may read at all; within each, the partner is a declared audience saying what of each — runs and their journey, never documents, purposes only if the managed tenant opts in. What the partner is shown is assembled outside the store: a store instance is one tenant's store, and no cross-tenant query is grown to serve a support desk. | PLANNED |
| `REQ-DBO-POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES` | One trail; the target says what an entry is about. A hop that carried work leaves a travel entry about the task. A participant that opened a payload leaves an access entry about the document, landing where every other reading of it lands and naming the task execution as its occasion. The machinery's own read to seal a payload records nothing: a read that yields only ciphertext is not a disclosure. So who read this is answered from the document by somebody who need not know work exists, and where did this go from the task, and the trail can say that nobody looked. | PROVEN |
| `REQ-DBO-WF-HOPS-AUDITED` | Every hop leaves a travel entry about the task — who handed to whom — and a travel entry is not a reading: audit of the journey is structural, not per-integration, and it never says anybody looked at the content. | PROVEN |
| `REQ-DBO-POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK` | A run's travel and access entries each carry a link to the one before, rooted in the task the store minted, so a participant cannot present a journey that never started. The result that closes the run is the last link and carries the head it commits to; the store checks the chain when the result lands, and a completion with a gap is refused and told which link. A travel entry names who it handed to, so a skipped hop is exposed by the next author. Links are signed by the participant, which buys non-forgery and non-repudiation and not omission-proofing: an intended recipient can open a payload and never say so, and that limit is accepted. The link lives on the entry, so the chain outlives nothing the trail does not, and a pruned predecessor reads unchained rather than broken. | PROVEN |
| `REQ-DBO-TEN-STRUCTURAL-SCOPING` | No code path can read or write data without an explicit tenant context. (R3) | PROVEN |

Coverage: {PROVEN=14, PLANNED=2} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

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
