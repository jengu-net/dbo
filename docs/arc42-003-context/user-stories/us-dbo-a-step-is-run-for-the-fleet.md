# US-DBO-A-STEP-IS-RUN-FOR-THE-FLEET — what only the deployment can see of a step it runs for every tenant

> Petra runs a deployment whose management tenant, `registry`, declares the
> steps the deployment performs for every tenant it serves. A tenant can see
> its own runs. Petra has to answer for everything else: which tenants took
> the step and which said no, what the deployment opens of each tenant's
> data and whether the tenant agreed to it, who holds the keys, where the
> work waits, and whether anything the deployment wrote back broke a
> tenant's rules.
>
> All of that is answered inside the deployment's own process, so she asks
> there.

## The scene

This is a technical story. It is told from the side of whoever operates a
deployment and the application that performs its steps, and it does not
take place in Rowling Land, because the scene needs `registry` to declare
things `mom` does not: two steps placed on one substrate, a step every
tenant must accept, a step that waits for approval, a step that is
withdrawn, and a named processor. A fleet step is declared for the whole
deployment, so declaring one of these in Rowling Land would change every
tenant there; the store has no way to scope a fleet step to some tenants.
What a tenant and its operator read of a fleet step — the register, its
incidents, whether it changed since the tenant authorised it — is walked in
Rowling Land, in [the fleet-step story](us-dbo-fleet-step.md).

Petra's deployment holds one tenant behind the personal-data membrane, two
tenants the processor is enrolled on, one tenant that says nothing about
the steps, one that declines one of them, and one that has authorised
nothing yet. Beside it, the harness's shared deployment holds two tenants of
one shape and the steps whose consumers are built by hand.

## One bean is found, not wired

The application registers its beans before the deployment has read its
declaration, which is the usual order under an assembly. Each bean names
its own step and nothing else. When `registry` declares the steps, the
container takes the beans up. It builds one consumer for the substrate the
two steps were placed on, and the bean is handed the record the run
referred to rather than the reference.

A bean whose step nothing declares stays waiting, and the deployment names
it as waiting for a declaration. A bean that will never be called looks
exactly like a step with nothing to do, and only the deployment can tell
the two apart.

## Work is asked for on the tenant's door

A participant holding a credential that may act in work asks the tenant's
own door for a run of the deployment's step. The door takes it, though the
tenant never declared that step. It refuses a step no level declares.

A slot can be filled by a search. The run records the reference the search
matched, a search that matches two is refused with the count, and a search
on an identifying element is refused at the door whatever purpose the
caller states.

## A processor is enrolled per tenant

The deployment names its processor by the public halves of two keys: one a
payload is sealed to, and one an opening is checked against. Every tenant
that comes up holds that enrolment. When `registry` adds a step, the same
enrolment covers it, and no tenant has to do anything a second time.


## A tenant admits or declines

The tenant that said nothing has its work offered to the step. The tenant
that declined it has its work left where it is. When that tenant tries to
decline a step the deployment requires, the declaration is refused. The
refusal names the step and `registry`, and it says the requirement is part
of joining.

## A row nobody authorised obeys its posture

The tenant that authorised nothing authors work under three rows. Work
under the default row runs, and an incident stands naming the tenant, the
slot, its declared type and how long it has been going on. Work under the
row applied by agreement runs and raises nothing. Work under the row marked
*not until approved* is never offered. Authorising that one row releases
its step and leaves the other incident standing.

## A step's substrate is prepared, and kept

Every declared step has somewhere for its work to wait before any work
exists. Two steps that name one substrate share it, and a step that names
none gets its own. Each substrate is a database named apart from the
tenants', holding a durable layer and no store schema. When a step is
withdrawn, its substrate stays, because the work queued there belongs to
tenants who believe it is being done.

## What a tenant reads, and what it is told

On the shared deployment, a tenant's register has one row for each slot a
step opens: the step, the slot, the declared type, whether the tenant may
decline it, and its posture. A step that only routes is not on it.

A router declared to open nothing opens its run's input anyway and reports
the opening, signed with the key it enrolled. Nothing stops it. The tenant's
account then holds an incident naming the router, the slot and who opened
it.

## One consumer, many tenants, and each tenant's rules

One consumer, built by hand over the shared deployment's substrate,
performs work authored in two tenants and names neither. When it is closed
and built again, it performs the work offered while it was down. It serves
both steps placed on its substrate. Two consumers on that substrate each
take only their own step's work, and a bean offered to a consumer for a
step it does not serve is refused.

The writeback goes through each tenant's own lane. A run closes in the
tenant that authored it, with the performer's tally and the name the
application gave it. A report the step's declaration does not admit is
refused, naming the action, and the run stays open.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PROC-A-BEAN-IS-FOUND-RATHER-THAN-WIRED` | An application performs a fleet step by registering a bean that names its own step, and nothing else: the container builds the consumer, the durable layer and the pool, and builds one per SUBSTRATE so two steps placed together are served by one. The two arrivals are order-independent — a bean registered before the deployment has read its declaration is held and taken up when the step is declared, because under an assembly the application's beans ordinarily come first. A bean whose step is never declared stays held and is named by the deployment as awaiting a declaration, because a bean nothing will ever offer work is indistinguishable from a step with nothing to do. | PROVEN |
| `REQ-DBO-PROC-A-FLEET-PERFORMER-IS-HANDED-ITS-OBJECTS` | A fleet performer is handed the run's slots RESOLVED — the objects themselves, not the references the run was authored with — on the hold it just took, because the claim is what entitles it both to the data and to reporting. Its working context is the item it was given: it runs outside the store, has no route into the tenant and no verb that takes a reference, so a slot delivered as a reference would be a slot it could do nothing with. Which is also what referring is FOR: whoever authored the run named data it need not hold, need not be entitled to read and never put on the wire, and the store resolved it where it already was. | PROVEN |
| `REQ-DBO-PROC-ONE-BEAN-PERFORMS-FOR-EVERY-TENANT` | A step the deployment performs is offered to an application as ITS STEP'S QUEUE rather than as a lane per tenant: one consumer, whatever the deployment's size, handed items that happen to name different tenants. The bean names no tenant and is not told which exist, so a tenant joining needs nothing redeployed. It keeps nothing between asks — every call is answered from the item it was handed — which is what lets a consumer be restarted mid-run and carry on, and what makes several of them a way to perform a hot step faster. | PROVEN |
| `REQ-DBO-PROC-A-CONSUMER-TAKES-ONLY-ITS-OWN-STEPS` | A consumer dequeues the queues of the steps it serves and no others. Several steps share a substrate on purpose, and a process listens to every queue registered in its system database unless it says otherwise — so a consumer deployed for one step would otherwise take another step's work, find nothing here that performs it, and drain a tenant's queue into a process that never did the work. Two consumers on one substrate each perform their own step and neither loses the other's. A bean offered for a step a consumer does not serve is refused when it is offered, rather than silently never being called, and an item for a step nothing here performs is a fault rather than a quiet success. | PROVEN |
| `REQ-DBO-PROC-DECLARING-A-STEP-PREPARES-ITS-SUBSTRATE` | Declaring a step the deployment performs prepares the substrate its queue lives on, the way declaring a tenant prepares its database — a runtime-owned database carrying a durable bootstrap and nothing else: no face, no zone, no personal-data isolation, no store schema, no authority. It is made through the admin connection that provisions tenants and never through the path that provisions one, because a thing that is not a tenant must not look like one to everything downstream. Placement is the deployment's: a step names the substrate it wants, several steps may name one and share it, and a step naming none gets its own. A withdrawal closes the step and removes nothing — what was queued belongs to tenants who believe it is being done, and dropping the database is a person's act. | PROVEN |
| `REQ-DBO-PROC-A-PARTICIPANT-ASKS-FOR-WORK-IT-NEED-NOT-PERFORM` | A participant is an initiator as well as a performer: on the same credential and the same enrolment it holds a lane with, it authors a run by asking the TENANT's own step door — which is where every run is authored, because the run belongs to the tenant it is about and this store schedules nothing on a tenant's behalf. The door takes a step the DEPLOYMENT declared as readily as one the tenant did, and still refuses one a participant merely introduced, since what separates them is who wrote the declaration down rather than who performs it. What takes the run is then decided by entitlement, so a participant can ask for work it cannot do and a fleet step asked for by one external application is performed by a bean inside the deployment. | PROVEN |
| `REQ-DBO-PROC-A-REFERENCE-MAY-BE-A-SEARCH` | A referred slot is filled by 'Type/id' or by a search — 'Organization?identifier=urn:x|1' — so whoever authors a run can name a record by something they know rather than by an id they would have to look up first. The search is resolved at the DOOR and the run records the references it matched, because what the work is over is fixed when the work is created: resolved at claim time instead, two performers could be handed different sets and the register could not say what was opened. A slot that takes one and matched none or several is refused, saying how many, rather than picking one. It buys no reach: the narrowing is compiled by the face's own search compiler and run by the engine, and this door states no purpose and accepts none — so a search that would match on an identifying element is refused here outright, in the door's own words, and stating a purpose does not open it. Asking whether somebody is here is not something a credential for work may do, and on the records surface a stated purpose is exactly what turns that question into an exact lookup through the vault. | PROVEN |
| `REQ-DBO-PROC-THE-WRITEBACK-PASSES-THE-TENANTS-RULES` | Work the deployment performed for a tenant is reported back through that tenant's OWN lane, so an outcome from a fleet consumer meets exactly the rules an outcome from a participant on a port meets: whether a machine may close this step, whether the report is in order for the state the run is in, and who is recorded as having performed it. The run closes in the tenant that authored it, naming the executor the application gave rather than the deployment's own name, and a report that breaks one of that tenant's rules is refused exactly as it would be on a lane. The performer claims before it reports, because the hold is what says whose account of the work counts. | PROVEN |
| `REQ-DBO-PROC-A-TENANT-ADMITS-OR-DECLINES-WHAT-IS-DONE-TO-IT` | A step the deployment performs is admitted by a tenant saying nothing and declined by one line, and a declined step is not offered that tenant's work at all — the run stays where it is, exactly as a run of a step the deployment does not perform does, because from the tenant's side those are one fact. A few steps the deployment REQUIRES, and declining one of those is refused by name at the declaration, saying where the requirement is written: an agreement signed by joining is not an agreement if a tenant can leave it by editing its own file. Declining applies while the tenant serves, because withdrawing authorisation must not cost an outage. | PROVEN |
| `REQ-DBO-PROC-A-TENANT-READS-WHAT-IS-OPENED-OF-ITS-DATA` | A tenant reads a register of every payload the deployment opens of its data: one row per SLOT a step opens, naming the step, the slot, the type, whether it may be declined and what happens to work not yet authorised. A step that only reads the envelope is not on it, because it discloses nothing. The register is DERIVED from the deployment's declaration and the tenant's own, never stored beside them, so what the deployment does and what a tenant reads cannot drift apart — and a step the tenant declined contributes no rows, because a step declined and a step not performed are one fact from the tenant's side. | PROVEN |
| `REQ-DBO-PROC-A-DISAGREEMENT-IS-AN-INCIDENT-NOT-A-REFUSAL` | Where the trail disagrees with the register, the store says so as an incident in the tenant's own account, naming who opened what, in which slot of which step, and on what occasion. It cannot be a refusal: an enrolled processor holds the key to what was sealed to it and no cryptography stops a party that can decrypt from decrypting, so detection is the honest guarantee and is offered as one. The comparison is computed from the tenant's own records rather than stored beside them, because a stored incident would be a second place to ask and the first disagreement between the two would leave a tenant unable to say which was true. | PROVEN |
| `REQ-DBO-PROC-A-PROCESSOR-IS-ENROLLED-PER-TENANT` | An application performing the deployment's steps is enrolled on EACH tenant it performs for, with the public halves of the keys a payload is sealed to and an opening is checked against. Per tenant rather than once for the fleet, because a payload is sealed to an enrolled participant and enrolling at fleet level would mean something re-seals a tenant's payload and therefore holds tenant keys — the thing the carrier rule exists to exclude. One record per tenant covers every step on that tenant's register, because enrolment being per tenant must not become enrolment one step at a time. Only public halves reach the store, so a copy of the record opens nothing, and the credential it carries is minted and held by nobody: the processor is authenticated by its signature and never signs in. | PROVEN |
| `REQ-DBO-PROC-A-TENANT-AUTHORISES-A-REGISTER-AND-SEES-IT-CHANGE` | A tenant authorises a register by writing down which one it read — the register's own digest, one value for the whole of it — so authorising is answerable all at once and a tenant approving rows one at a time could never be sure it had finished — and every row is named individually inside that act, so the store can still say which single row is new or widened. Whether what the deployment does with its data has changed since is then ONE COMPARISON rather than an audit. The digest covers every field a tenant would decide on, including the posture, so a deployment cannot move a row from not-until-approved to processed-and-named without the tenant's copy ceasing to match — which would be a deployment approving its own widening. Never having read a register is a different answer from having read a different one. | PROVEN |
| `REQ-DBO-PROC-AN-UNAUTHORISED-ROW-OBEYS-ITS-POSTURE` | What happens to work under a row a tenant has not authorised is the row's own posture, stated by the deployment where the row is declared. A row that says NOT UNTIL APPROVED has that tenant's work withheld from the step entirely — refusal is real here and nowhere else in this design, because approval is known before anything is sealed, so not offering the work actually prevents the processing. A row that says PROCESSED AND NAMED runs, and the cost is carried by an incident that stands until the row is authorised: it names the tenant, the step and what is being opened, and says how long, because an incident reading the same on day one and day ninety is one nobody acts on. A row that says APPLIED runs under the agreement and raises nothing. Processed-and-named is the default, because a halting default would turn an unanswered register into an outage caused by nobody clicking. Per row, so a deployment may halt for a new row without stopping everything else. | PROVEN |

Coverage: {PROVEN=14} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **A fleet step cannot be scoped to some tenants.** It is declared for the
  whole deployment, so a step every tenant must accept, one that waits for
  approval or one withdrawn cannot be shown in a world whose other tenants
  are in use.
- **A deployment's configuration cannot name a processor.** Enrolling one
  on every tenant is built. Saying who it is still takes code that holds
  the deployment's manager.

## Open decisions

- **Whether this story moves into Rowling Land** once those doors exist and
  `mom` declares a required step and one that waits for approval.
