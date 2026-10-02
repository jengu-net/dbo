# US-DBO-FLEET-STEP — one step, performed for every tenant, answered to each

> The Ministry runs the deployment Rowling Land lives on, and it checks
> every organisation's directory entry against what somebody proposes it
> should say. Hogwarts did not install that check, and neither did the
> clinic that joined last week. The Ministry declared it once, one bean
> performs it, and nobody had to redeploy anything when the clinic arrived.
>
> What makes that tolerable to a tenant is that the work still belongs to
> it. A run of the check is authored on the tenant's own door, closes in
> the tenant's own store through the tenant's own rules, and a tenant that
> does not want it done to its data says so in one line.

## The scene

Rowling Land is one deployment and its management tenant, `mom`. Three
parts take part in this scene:

- **The deployment**, which declares `fleet.directory.check` in `mom`'s own
  record under `fleetSteps`. The step takes the organisation it is about by
  reference, a proposed entry as an object, and a list of notes.
- **The serving application**, which holds one bean performing that step.
  The bean names no tenant, and the container finds it rather than being
  told about it.
- **The tenants**: Hogwarts, the hospital, and a clinic that joins while the
  deployment is already running.

The worker application beside them asks for checks and performs none.

## The step is the deployment's, and nobody else may claim its code

The clinic's first declaration offers a step of its own under the same code,
`fleet.directory.check`. It does not come up. The deployment's record of what
it is serving names the code, the management tenant that declares it, and
both keys, `steps` and `fleetSteps`. A code declared at both levels would be a
run two schedulers both reach for, and a declaration is the cheapest place to
catch that.

The clinic renames its own step and comes up. The refusal leaves the
deployment's record when the cause does. Hogwarts, which offers a step of its
own under a code of its own, was never touched by the rule.

Declaring the step also prepared a place for its work before there was any:
a database the runtime owns, named apart from every tenant's and carrying a
durable layer and nothing else.

## Work is asked for on the tenant's own door

The worker application asks Hogwarts for a check of one of Hogwarts'
organisations. It names the organisation by the identifier it knows, because
it holds no credential that could look up the record's id. Hogwarts resolves
that search against its own records when the run is created, and the run
records the reference it matched, so what the run is over cannot change
afterwards. The proposed entry and the notes travel with the run as objects
that are stored nowhere.

A search that matches two organisations fills no slot that takes one, and the
refusal says how many it matched. A step that no level declares is not found.

## One bean performs it for every tenant

The deployment's joiner reads every tenant's work and offers the run into the
step's queue, under the tenant and the run it came from. The bean is handed
the organisation itself, the proposal and the notes, and it performs the check.
The run closes in Hogwarts, naming the bean as its executor, with the bean's
tally on it.

The clinic joined after the bean was deployed. Its check is performed by the
same bean, which still names no tenant. A run of the clinic's own step is left
where it is, because the step is not the deployment's. When the joiner is made
to read the clinic's work again from the start, as it would after a restart,
every run is still offered exactly once.

At the clinic's own lane a participant sees the same three shapes of slot: a
referred record arrives resolved, a given object arrives with no id and no
version, and a list arrives in the order it was written.

## What the clinic reads of it

The clinic reads its register at its own door, with a credential carrying the
scope it authorises a register with. It has one row: the check opens the
organisation and only carries the proposal and the notes. A credential that
reads records is refused there, naming the scope it lacks, and so is the
operator's token.

The check ran over the clinic's data under that row, which the clinic never
authorised, so its account holds an incident naming the step, the slot and
since when. The operator reads at the node that the clinic has a row standing
and an incident against it, and nothing of the clinic's records. The clinic
then authorises the register it read, by writing the rows' digests into its
declaration: it is told nothing changed, and the incident clears. Authorising
a register it did not read is a change at once.

## A tenant says no in one line

The clinic declines the check. Its own door then refuses to start one and says
why, and a run of the check authored inside the clinic is never offered.
Hogwarts said nothing, which admits the step, and its work goes on being
offered. The clinic's register now has no rows, and says what it declined.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PROC-AN-APPLICATION-STEP-IS-THE-DEPLOYMENTS-TO-DECLARE` | A step the deployment performs for every tenant is declared in the management tenant's own descriptor and nowhere else, under a key of its own: what it takes, which of those it OPENS rather than carries, whether a tenant admits it or joining required it, what happens to work whose processing is not yet approved, and where its queue lives. An ordinary tenant declaring one is refused by name, naming the key and the tenant — a tenant declares the steps it offers, and what the deployment does to every tenant's data is not among them. Slots are not checked against the declaring tenant's own types, because the types belong to the tenants whose work it performs. | PROVEN |
| `REQ-DBO-PROC-A-STEP-CODE-BELONGS-TO-ONE-LEVEL` | A step code is the deployment's or a tenant's and never both. A tenant offering a code the management tenant declares under 'fleetSteps' is refused by the sweep that reconciles declarations, naming the tenant, the code and the management tenant — and it is refused whichever side arrived second, because every declaration is read again each pass. A tenant already serving keeps serving while it is refused: what is wrong is the pair, and the tenant may not be the side that changed. | PROVEN |
| `REQ-DBO-PROC-DECLARING-A-STEP-PREPARES-ITS-SUBSTRATE` | Declaring a step the deployment performs prepares the substrate its queue lives on, the way declaring a tenant prepares its database — a runtime-owned database carrying a durable bootstrap and nothing else: no face, no zone, no personal-data isolation, no store schema, no authority. It is made through the admin connection that provisions tenants and never through the path that provisions one, because a thing that is not a tenant must not look like one to everything downstream. Placement is the deployment's: a step names the substrate it wants, several steps may name one and share it, and a step naming none gets its own. A withdrawal closes the step and removes nothing — what was queued belongs to tenants who believe it is being done, and dropping the database is a person's act. | PROVEN |
| `REQ-DBO-PROC-A-PARTICIPANT-ASKS-FOR-WORK-IT-NEED-NOT-PERFORM` | A participant is an initiator as well as a performer: on the same credential and the same enrolment it holds a lane with, it authors a run by asking the TENANT's own step door — which is where every run is authored, because the run belongs to the tenant it is about and this store schedules nothing on a tenant's behalf. The door takes a step the DEPLOYMENT declared as readily as one the tenant did, and still refuses one a participant merely introduced, since what separates them is who wrote the declaration down rather than who performs it. What takes the run is then decided by entitlement, so a participant can ask for work it cannot do and a fleet step asked for by one external application is performed by a bean inside the deployment. | PROVEN |
| `REQ-DBO-PROC-A-SLOT-IS-REFERRED-OR-GIVEN-AND-MAY-REPEAT` | A slot declares what fills it in FHIR's own notation: 'Reference(T)' for one the tenant already holds, a bare 'T' for one given with the run, and '[]' for several of either. A reference is resolved by the store where the data already is, so whoever authors the run need not hold it, be entitled to read it, or send it. A given object has no record, no id and no version: it travels with the run, is opened by whoever performs the step, and is written to the store by nobody unless that step decides it should be and does so as its own act. A slot is homogeneous, so the run records it without a marker — a string is a reference, an object is given, an array is several — and the door refuses a request whose shape is not the one declared, naming the slot and what it takes. Order within a repeating slot is kept, because a list somebody sent is a list they meant. | PROVEN |
| `REQ-DBO-PROC-A-REFERENCE-MAY-BE-A-SEARCH` | A referred slot is filled by 'Type/id' or by a search — 'Organization?identifier=urn:x|1' — so whoever authors a run can name a record by something they know rather than by an id they would have to look up first. The search is resolved at the DOOR and the run records the references it matched, because what the work is over is fixed when the work is created: resolved at claim time instead, two performers could be handed different sets and the register could not say what was opened. A slot that takes one and matched none or several is refused, saying how many, rather than picking one. It buys no reach: the narrowing is compiled by the face's own search compiler and run by the engine, and this door states no purpose and accepts none — so a search that would match on an identifying element is refused here outright, in the door's own words, and stating a purpose does not open it. Asking whether somebody is here is not something a credential for work may do, and on the records surface a stated purpose is exactly what turns that question into an exact lookup through the vault. | PROVEN |
| `REQ-DBO-PROC-THE-JOINER-OFFERS-EVERY-TENANTS-WORK` | One observer reads every tenant's work feed as a named durable consumer and offers each run of a step the deployment performs into that step's queue, on the substrate that step named. A run of a step the deployment does not perform is left where it belongs, which is what the two levels are made of. It CLAIMS nothing — the run stays offered on nothing and a joiner that falls behind reads as a cursor that is not moving rather than as work nobody wanted — and it CONSUMES nothing, holding a writer rather than an executor, so it polls no queue and holds no listener. Offering is idempotent on the run's own identity, because no transaction spans reading a tenant's feed and writing to a step's substrate: a restart or a lost acknowledgement re-offers and writes no second item. A tenant whose feed cannot be read is one tenant and not the fleet: the pass says so once per reason, offers every other tenant's work, and says so again when that tenant can be read — because a deployment where one tenant is in trouble is not a deployment where nobody's work is offered, and which tenants stopped would otherwise be decided by the order they happen to be iterated in. | PROVEN |
| `REQ-DBO-PROC-ONE-BEAN-PERFORMS-FOR-EVERY-TENANT` | A step the deployment performs is offered to an application as ITS STEP'S QUEUE rather than as a lane per tenant: one consumer, whatever the deployment's size, handed items that happen to name different tenants. The bean names no tenant and is not told which exist, so a tenant joining needs nothing redeployed. It keeps nothing between asks — every call is answered from the item it was handed — which is what lets a consumer be restarted mid-run and carry on, and what makes several of them a way to perform a hot step faster. | PROVEN |
| `REQ-DBO-PROC-A-BEAN-IS-FOUND-RATHER-THAN-WIRED` | An application performs a fleet step by registering a bean that names its own step, and nothing else: the container builds the consumer, the durable layer and the pool, and builds one per SUBSTRATE so two steps placed together are served by one. The two arrivals are order-independent — a bean registered before the deployment has read its declaration is held and taken up when the step is declared, because under an assembly the application's beans ordinarily come first. A bean whose step is never declared stays held and is named by the deployment as awaiting a declaration, because a bean nothing will ever offer work is indistinguishable from a step with nothing to do. | PROVEN |
| `REQ-DBO-PROC-A-CONSUMER-TAKES-ONLY-ITS-OWN-STEPS` | A consumer dequeues the queues of the steps it serves and no others. Several steps share a substrate on purpose, and a process listens to every queue registered in its system database unless it says otherwise — so a consumer deployed for one step would otherwise take another step's work, find nothing here that performs it, and drain a tenant's queue into a process that never did the work. Two consumers on one substrate each perform their own step and neither loses the other's. A bean offered for a step a consumer does not serve is refused when it is offered, rather than silently never being called, and an item for a step nothing here performs is a fault rather than a quiet success. | PROVEN |
| `REQ-DBO-PROC-A-FLEET-PERFORMER-IS-HANDED-ITS-OBJECTS` | A fleet performer is handed the run's slots RESOLVED — the objects themselves, not the references the run was authored with — on the hold it just took, because the claim is what entitles it both to the data and to reporting. Its working context is the item it was given: it runs outside the store, has no route into the tenant and no verb that takes a reference, so a slot delivered as a reference would be a slot it could do nothing with. Which is also what referring is FOR: whoever authored the run named data it need not hold, need not be entitled to read and never put on the wire, and the store resolved it where it already was. | PROVEN |
| `REQ-DBO-PROC-THE-WRITEBACK-PASSES-THE-TENANTS-RULES` | Work the deployment performed for a tenant is reported back through that tenant's OWN lane, so an outcome from a fleet consumer meets exactly the rules an outcome from a participant on a port meets: whether a machine may close this step, whether the report is in order for the state the run is in, and who is recorded as having performed it. The run closes in the tenant that authored it, naming the executor the application gave rather than the deployment's own name, and a report that breaks one of that tenant's rules is refused exactly as it would be on a lane. The performer claims before it reports, because the hold is what says whose account of the work counts. | PROVEN |
| `REQ-DBO-PROC-A-TENANT-ADMITS-OR-DECLINES-WHAT-IS-DONE-TO-IT` | A step the deployment performs is admitted by a tenant saying nothing and declined by one line, and a declined step is not offered that tenant's work at all — the run stays where it is, exactly as a run of a step the deployment does not perform does, because from the tenant's side those are one fact. A few steps the deployment REQUIRES, and declining one of those is refused by name at the declaration, saying where the requirement is written: an agreement signed by joining is not an agreement if a tenant can leave it by editing its own file. Declining applies while the tenant serves, because withdrawing authorisation must not cost an outage. | PROVEN |
| `REQ-DBO-PROC-A-TENANT-READS-WHAT-IS-OPENED-OF-ITS-DATA` | A tenant reads a register of every payload the deployment opens of its data: one row per SLOT a step opens, naming the step, the slot, the type, whether it may be declined and what happens to work not yet authorised. A step that only reads the envelope is not on it, because it discloses nothing. The register is DERIVED from the deployment's declaration and the tenant's own, never stored beside them, so what the deployment does and what a tenant reads cannot drift apart — and a step the tenant declined contributes no rows, because a step declined and a step not performed are one fact from the tenant's side. | PROVEN |
| `REQ-DBO-PROC-A-DISAGREEMENT-IS-AN-INCIDENT-NOT-A-REFUSAL` | Where the trail disagrees with the register, the store says so as an incident in the tenant's own account, naming who opened what, in which slot of which step, and on what occasion. It cannot be a refusal: an enrolled processor holds the key to what was sealed to it and no cryptography stops a party that can decrypt from decrypting, so detection is the honest guarantee and is offered as one. The comparison is computed from the tenant's own records rather than stored beside them, because a stored incident would be a second place to ask and the first disagreement between the two would leave a tenant unable to say which was true. | PROVEN |
| `REQ-DBO-PROC-A-PROCESSOR-IS-ENROLLED-PER-TENANT` | An application performing the deployment's steps is enrolled on EACH tenant it performs for, with the public halves of the keys a payload is sealed to and an opening is checked against. Per tenant rather than once for the fleet, because a payload is sealed to an enrolled participant and enrolling at fleet level would mean something re-seals a tenant's payload and therefore holds tenant keys — the thing the carrier rule exists to exclude. One record per tenant covers every step on that tenant's register, because enrolment being per tenant must not become enrolment one step at a time. Only public halves reach the store, so a copy of the record opens nothing, and the credential it carries is minted and held by nobody: the processor is authenticated by its signature and never signs in. | PROVEN |
| `REQ-DBO-PROC-A-TENANT-AUTHORISES-A-REGISTER-AND-SEES-IT-CHANGE` | A tenant authorises a register by writing down which one it read — the register's own digest, one value for the whole of it — so authorising is answerable all at once and a tenant approving rows one at a time could never be sure it had finished — and every row is named individually inside that act, so the store can still say which single row is new or widened. Whether what the deployment does with its data has changed since is then ONE COMPARISON rather than an audit. The digest covers every field a tenant would decide on, including the posture, so a deployment cannot move a row from not-until-approved to processed-and-named without the tenant's copy ceasing to match — which would be a deployment approving its own widening. Never having read a register is a different answer from having read a different one. | PROVEN |
| `REQ-DBO-PROC-AN-UNAUTHORISED-ROW-OBEYS-ITS-POSTURE` | What happens to work under a row a tenant has not authorised is the row's own posture, stated by the deployment where the row is declared. A row that says NOT UNTIL APPROVED has that tenant's work withheld from the step entirely — refusal is real here and nowhere else in this design, because approval is known before anything is sealed, so not offering the work actually prevents the processing. A row that says PROCESSED AND NAMED runs, and the cost is carried by an incident that stands until the row is authorised: it names the tenant, the step and what is being opened, and says how long, because an incident reading the same on day one and day ninety is one nobody acts on. A row that says APPLIED runs under the agreement and raises nothing. Processed-and-named is the default, because a halting default would turn an unanswered register into an outage caused by nobody clicking. Per row, so a deployment may halt for a new row without stopping everything else. | PROVEN |
| `REQ-DBO-PROC-THE-REGISTER-IS-READ-AT-A-DOOR` | A tenant reads its register and the incidents kept against it at a door of its own, under the scope it authorises a register with: the rows the deployment's steps open of its data, the steps it declined, whether what is done changed since it authorised, never present for a tenant that never did, and every opening that ran unauthorised or disagrees with its trail. Whoever operates the deployment reads, under the operator's token, which tenants have rows standing unauthorised and how many incidents each holds, and which beans wait for a step nobody declared — and never which of a tenant's records were opened. | PROVEN |

Coverage: {PROVEN=19} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **A deployment cannot name its processor.** Enrolling the deployment's
  processor on every tenant is built, but nothing in a deployment's
  configuration says who the processor is or which public keys it holds, so
  no deployment enrols one.

## Open decisions

- **How a deployment names its processor**, and where the private halves of
  its keys live while the bean that performs the step runs inside the
  deployment.
- **Whether Rowling Land should declare a step every tenant must accept**, and
  one that waits until a tenant authorises it. Either would show a guide reader
  the two answers a tenant cannot give in one line.
