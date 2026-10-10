package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Promise;

/**
 * The store's promise catalogue — whole (2026-08-27). The pilot carried
 * SHAPE and PDI; everything else migrated in one pass from
 * hand-written req-catalogue.md prose, verbatim, each row a constant. The
 * port and the citation were deliberately two passes, so a promise with no
 * test said so rather than reading PROVEN by proximity to ones that did; the
 * citation pass has since run, and what still carries a {@code TODO: prove
 * it in a test} javadoc is what genuinely has no proof — each one naming
 * what a proof would have to show. The constant's name IS the code, prefixed
 * by the namespace, so a citation cannot drift from a declaration.
 */
@Catalogue(namespace = "REQ-DBO")
public enum DboPromises implements Promise {

    // ── SHAPE — shape versioning ────────────────────────────────────────

    SHAPE_WRITTEN_UNDER_STAMPED("Every object accepted through the face carries, as a fact "
            + "of the accept event stored beside the payload, the version of each declared "
            + "pack profile it was validated against; re-accepting replaces the stamp, "
            + "never accumulates it."),

    SHAPE_STAMP_IS_DERIVED("The shape stamp is a per-version fact column in state and "
            + "history; the envelope's shape dimension is rebuilt from it on reindex, and "
            + "every history version serves its own stamp."),

    SHAPE_SERVED_BESIDE_THE_CLAIM("The stamp is served in meta as the published "
            + "urn:dbo:shape extension beside the unversioned meta.profile canonical; an "
            + "echoed copy of the engine's own stamp is dropped at accept, so stored bytes "
            + "stay the author's claims."),

    SHAPE_MIRRORED_KEEPS_ITS_STAMP("The stamp travels the sync wire beside the "
            + "storage-format version, so a mirrored copy keeps the stamp of the store "
            + "that validated it; only an authored accept restamps."),

    SHAPE_QUERYABLE_BY_VERSION("Objects are searchable by shape-stamp bound — below, or "
            + "at and above, a stated major for a stated profile — pageable like any "
            + "search, on every serving surface."),

    SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED("A shape the tenant holds is validated "
            + "against, whatever path it arrived by. A validation view is built from what "
            + "the store held when it was built, and shapes arrive afterwards by paths no "
            + "facade served — replicated from a zone, restored from an archive, applied by "
            + "a lane — so a claim on a shape that is in the store and not in the view loads "
            + "it rather than refusing it. Telling an author that this tenant does not have "
            + "a profile they can see in it is the store being wrong about its own "
            + "contents."),

    SHAPE_STOCK_COUNTED("The tenant inventory counts shape stock per type, profile and "
            + "stamped version — including objects that declare a profile and carry no "
            + "stamp at all — so the same report runs before and after a migration and "
            + "diffs line by line."),

    SHAPE_UNPARSEABLE_VERSION_REFUSED("A pack shape whose version has no parseable "
            + "leading integer major is refused at accept, by name — it would stamp "
            + "objects no version bound can ever match."),

    SHAPE_RESHAPED_IN_PLACE("The store converts stamped objects to a target major in "
            + "place: each rewrite is an ordinary versioned write, so history keeps the "
            + "pre-conversion object with its own stamp and the new version carries the "
            + "new one."),

    /**
     * The default converges everything below the bound, which is what a sweep
     * wants and what an admission gate cannot use: a gate holds a tenant out
     * of service on a narrow condition and has to clear only that condition,
     * or it shuts the clinic for longer than a naive design would.
     *
     * <p>The filter is compiled by the face that serves the searches rather
     * than parsed a second time, so what counted the stock is what converts
     * it. Two expressions that merely agree can stop agreeing, and a gate
     * would then clear a condition it never measured.
     */
    SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING("A reshape and a hand-back claim accept the "
            + "search narrowing a read of the same stock accepts, compiled by the same face, "
            + "so one expression measures and converges; a parameter the store cannot honour "
            + "is refused by name before anything is converted, never dropped."),

    SHAPE_RESHAPE_RESUMABLE("A reshape is paged and rate-bounded, hands back a cursor and "
            + "its counts, and a re-run finds only what is still behind."),

    SHAPE_REFUSED_OBJECT_LEFT_BEHIND("An object no converter covers is named and left "
            + "behind rather than stranding the rest; the run reports it and the next run "
            + "tries again."),

    SHAPE_STAMP_OUTLIVES_ITS_PACK("A stamp is a fact about a past accept: withdrawing or "
            + "re-numbering a pack version leaves stock stamped with it findable, "
            + "countable and convertible."),

    SHAPE_NEWER_DATA_REFUSED("An object stamped above what the tenant's pack declares for "
            + "that shape is refused on every read — naming the object, the stamp and the "
            + "pack's version — never served best-effort and never silently omitted from a "
            + "search."),

    SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER("The refusal is a distinct, documented error a consumer "
            + "can gate on, told apart from a fault, a permission and a malformed request."),

    SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING("Claiming stock for conversion elsewhere writes "
            + "nothing and holds nothing: the version check on the way back is the only "
            + "guard, so an abandoned claim strands no data and a duplicated one converges."),

    SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE("Converted forms handed back are re-accepted through "
            + "the face — validated, re-stamped, version-checked — and accounted exactly as "
            + "the in-process lane accounts, so the hardest conversions do not run with the "
            + "least discipline."),

    // ── PDI — personal-data isolation ───────────────────────────────────

    PDI_STRUCTURAL_VAULT("Identifying elements, declared per type/element, live encrypted "
            + "in the tenant's person vault; the main store holds pseudonymous records and "
            + "the engine reassembles full resources for authorized reads — isolation is "
            + "beneath the API, not a caller discipline."),

    PDI_CRYPTO_SHREDDING("Erasure destroys the person's key: history stays byte-immutable, "
            + "existing archives stay valid as files, and the person's data is "
            + "cryptographically gone from live store, history, envelopes and archives at "
            + "once."),

    /**
     * The inverse of the derivation, and the reason it is a walk rather than
     * a lookup: the index that would make it fast is the correlatable link
     * the derivation exists in order not to have, and it would have to be
     * deleted on an erasure by somebody remembering to.
     *
     * <p>The trail is the other half. Turning a pseudonym back into a person
     * is a disclosure, so it names who asked and why — and records the person
     * and the scope rather than the pseudonym, because a row pairing those
     * two would be the index arriving one question at a time.
     */
    PDI_PSEUDONYM_RESOLVED_BY_SCAN("Which person a pseudonym belongs to is answered by "
            + "deriving over the people whose keys could have made it, never by a stored "
            + "mapping; the question states its scope and its purpose and is recorded as the "
            + "disclosure it is, and the record names the person rather than the pseudonym. A "
            + "person whose key is destroyed is not found, and is not distinguishable from one "
            + "who was never here."),

    PDI_UNFINDABLE_AFTER_ERASURE("Search indexes derived from personal elements are "
            + "vault-scoped or rebuilt on shred — an erased person is unfindable, not "
            + "merely unreadable."),

    PDI_BLIND_OPERATIONS("Backup and restore are machinery-driven end to end over "
            + "ciphertext; the operator can run the whole lifecycle without the ability to "
            + "read personal data, and opening an archive outside the running system is an "
            + "owner-only act."),

    PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE("A tenant's database is part of this store's "
            + "runtime, and personal data passes through it in the clear only in flight — "
            + "validated, extracted, converted — never landing anywhere the person's key does "
            + "not cover. The one way it could land is the server logging a statement's "
            + "parameters, so every database the store is allowed to pin is pinned not to — "
            + "a managed server keeps that setting for its superuser and hands this store an "
            + "ordinary role, which is a server to check rather than a store that cannot run. "
            + "What the sessions will actually see is checked at every bring-up rather than "
            + "assumed, and an isolated tenant refuses to come up on a database that would "
            + "write its people down."),

    PDI_SHRED_LEDGER("Erasures are recorded without personal data and re-applied on every "
            + "restore before serving resumes — an old archive cannot silently resurrect "
            + "an erased person."),

    PDI_RIGHTS_AS_OPERATIONS("Access, portability and restriction are standard machinery "
            + "operations over the vault join, not per-request projects."),

    PDI_EXACT_RESOLUTION("An exact, purpose-stated lookup on a vault-indexed value — a "
            + "claimed identifier (system|value) or an indexed contact point — resolves "
            + "through the vault to the records holding it, served under the caller's "
            + "disclosure mode. The match runs over keyed hashes and every resolution "
            + "leaves a value fingerprint in the disclosure trail; after erasure the "
            + "answer is empty. Anything inexact, unsystemed, or combined with other "
            + "predicates is refused, never half-answered."),

    // ── PROC — distributed work; the first slice beyond the pilot ──

    PROC_STEP_DECLARES_ITS_SLOTS("A step declaration names its input slots — ordered, "
            + "named, each an opaque shape reference — and a runner that joins the step "
            + "has agreed to that API: there is nothing else it can receive."),

    PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS(
            "A run context answers for the documents its run named and for nothing else — a "
            + "document of a declared type the run was not given is as absent as one that "
            + "never existed, so a credential that may act in a step cannot use it to read "
            + "past what the step was handed."),

    PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE(
            "A person takes a task with their own token, issued by the tenant's identity "
            + "provider, and holds it as a PractitionerRole the tenant holds — named as what "
            + "holds the task, as a person and not as automation — on a lease their checkpoints "
            + "extend, as an "
            + "executor's are. While they hold it the run's context answers them and nobody "
            + "else, and each reading names them. Somebody with no role here, or whose work "
            + "does not reach the step, is answered as for a run that never existed."),

    PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN(
            "A run context answers its owner while the task is claimed, whoever that is — the "
            + "client that started it, an executor, or a person as their role. A run that is "
            + "over answers its performer exactly as a run that never existed — the "
            + "same answer either way, because saying that a run is over confirms that it "
            + "was real. So performing a step leaves no standing way in behind it, which "
            + "is the difference between access granted to a step and access granted once "
            + "by way of one."),

    PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS(
            "A run context answers the client performing the run and nobody else, and so "
            + "does the run's end. Whoever starts a run at the step door holds it, reading "
            + "what it was given and saying it is done, until a participant claims it on a "
            + "lane — and from then on the context is the claimant's. Another credential "
            + "that may act in work at the same tenant, holding the run's id, reads none "
            + "of it and cannot end it, and is answered exactly as for a run that never "
            + "existed; so is everybody while nobody holds the run, save its asker inside "
            + "the window a step that declares an answer gives it. A run's id is a thing "
            + "that gets logged and passed around, and it is not a key to the documents "
            + "the run was given."),

    PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER(
            "A step that declares an answer gives its run's requester a window, from the "
            + "moment the result is written until the step's declared length later, in which "
            + "it alone reads what the run was given and each version the run produced, read "
            + "as that version. The run is over and nobody holds it: collecting is optional, "
            + "so the window is a time beside the run and never a state of it, and the asker "
            + "may shut it early by saying it is done. A step that declares no answer closes "
            + "as it always did and opens no window."),

    PROC_AN_UNCOLLECTED_ANSWER_LAPSES(
            "Past its window a run's context answers its asker exactly as a run that never "
            + "existed, byte for byte. The window shuts by the clock, with no transition of "
            + "the run and no sweep, and the run's answer still names the versions it "
            + "produced."),

    PROC_THE_ASKER_READS_NOTHING_WHILE_THE_WORK_IS_DONE(
            "While a participant performs a run, the application that asked for it reads "
            + "nothing through the run's context and is answered as for a run that never "
            + "existed. The context is the performer's while the work is done and the "
            + "asker's only once it is over, so the two never read through one run at "
            + "once."),

    PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES("A lane may say that it has work, and a runner "
            + "waiting on one looks again instead of waiting out its tick. What arrives is "
            + "that something changed and never the work: the runner then polls and claims "
            + "through the ordinary path, because the claim race is what decides who takes "
            + "a run and a second mechanism deciding it would be a second answer beside the "
            + "run record's account of what is owed and by whom. The poll stays underneath "
            + "as the fallback, so a runner whose lane can say nothing — or whose wake-up "
            + "never arrives — does the work anyway, and a delivery that goes missing is a "
            + "latency bug rather than a lost run. A lane that cannot say is not degraded, "
            + "and nothing above the facade can tell which kind it holds except by how long "
            + "it waited."),

    PROC_RUN_INPUTS_FILL_THE_SLOTS("A run's inputs fill the step's declared slots, fixed "
            + "at creation: a slot the step does not declare and a declared slot left "
            + "unfilled are both refused by name, and the record round-trips them in "
            + "order."),

    PROC_TASK_CARRIES_THE_INPUTS("The face renders each input as Task.input — the slot "
            + "name as the parameter's code, the reference displayed rather than "
            + "resolved, exactly as focus is — in every version the face serves."),

    PROC_INPUTS_ARRIVE_WITH_THE_WORK("A claimed run's inputs arrive with the work, "
            + "resolved by the party that holds the objects; the runner's only read takes "
            + "the run, a run the asking identity has not claimed is refused, and a run "
            + "without slots delivers exactly nothing."),

    PROC_MILESTONES_ARE_DECLARED("A step declares its milestones, in order; where "
            + "declared, a name outside them is refused naming both sides, and a step "
            + "that has not declared any is not narrowed."),

    PROC_PROGRESS_NAMES_THE_MILESTONE("A checkpoint can carry the milestone reached; the "
            + "run records it replaced-never-accumulated, with its position over the "
            + "declared order derived by the store rather than asserted by the executor, "
            + "and it survives release and retake. A service that reports nothing behaves "
            + "exactly as today."),

    PROC_TASK_SAYS_WHERE_THE_WORK_IS("The rendered Task's businessStatus says where the "
            + "work is when a milestone is recorded — the step's own word for it, with its "
            + "derived position — in every version the face serves."),

    PROC_STEPS_ARRIVE_BY_INTRODUCTION("A participant on a lane introduces the step "
            + "declarations it brings beside its own candidacy; the catalogue records "
            + "them with the introducer's name, and every consumer of the catalogue — "
            + "validation, actions, milestones, the mandatory-steps classification — "
            + "sees them the moment presence does."),

    PROC_ONE_ID_ONE_DEFINITION("Two DEFINITIONS of one step id is a collision refused by "
            + "name, never an override — a conflicting introduction, or a module "
            + "installed beside one. Scaling stays possible by construction: parallel "
            + "runners introducing an identical declaration co-introduce without refusal "
            + "or thrown races (DBOS runs parallel consumers, and a fleet is not a "
            + "conflict), and re-introduction by the same participant replaces."),

    PROC_INTRODUCTION_GRANTS_NOTHING("A step introduced over a lane grants its "
            + "introducer nothing: the declaration binds the introducer exactly as it "
            + "binds anybody, and what it may take stays the intersection of its scopes "
            + "and what the step admits."),

    PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS("A step may say when automation may take "
            + "its task, as a condition over the task's inputs, and the store decides it once, "
            + "when the task is authored: a task the condition does not admit is open to people "
            + "alone. A condition the store cannot evaluate, or one that reads an element "
            + "identifying a person — which deciding would mean unsealing — is refused when the "
            + "step is declared, naming what stopped it. A step that says nothing is open to "
            + "automation."),

    PROC_CLAIM_IS_THE_INTERSECTION("What a participant may claim is the intersection of "
            + "what its credential covers, the scope the step admits, and whether the task is "
            + "open to automation: the lane narrows the work it offers and refuses a claim "
            + "outside the entitlement, the store refuses an executor at a scope the step "
            + "never opened itself to, and a machine is offered no task open to people alone "
            + "and is refused one when it claims it. A step cannot grant its executor more "
            + "than the executor already holds."),

    PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED("A lane's entitlement is stated when the "
            + "lane is provisioned — everything, because the host is the tenant, or the "
            + "steps a credential covers. There is no implicit unrestricted, so the "
            + "reach of a remote participant never depends on a parameter somebody "
            + "forgot."),

    PROC_REFUSED_IS_NOT_UNANSWERED("A lane verb that was refused and one the store "
            + "never answered are told apart on the exception, because the two want "
            + "opposite recoveries: a refusal is settled and asking again is wrong, an "
            + "unanswered call is transient and asking again is the only way through. A "
            + "participant that confused them would back off from work it is entitled to "
            + "and lose the claim it was holding when the deadline passed."),

    PROC_A_FAULT_THE_CALLER_IS_NOT_TOLD_IS_STILL_RECORDED("A lane verb that could not "
            + "complete tells the caller that and no more — the caller is another party "
            + "and the cause is this deployment's business — and records the cause on this "
            + "side, at error, beside the verb it was asked for. Both halves are the "
            + "promise: a fault said in full to a stranger is a disclosure, and one said "
            + "to nobody at all leaves the cause in no place anybody can reach, which is "
            + "an outage whose diagnosis costs a reproduction."),

    PROC_A_LANE_IS_ATTACHED_WHILE_THE_APPLICATION_RUNS("A worker holds a lane from the "
            + "moment its application attaches one, with a credential asked for on every call "
            + "and written down nowhere, and lets it go when told. While it holds the lane it "
            + "can keep a place of that tenant up to date or stop, and stopping withdraws the "
            + "place's link and nothing else: the lane goes on carrying work."),
    PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS("A host that reaches the store over HTTP "
            + "obtains the same lane as one that holds the store in-process: the tenant "
            + "serves the participation verbs on its own private surface, guarded by its "
            + "own authority, and a runner cannot tell the two apart. The entitlement is "
            + "derived from the credential and never asked for by the caller, and a "
            + "credential bounded to steps may work only as itself."),

    // ── PROC continued — migrated from hand-written prose (2026-08-27).
    //
    // What stood behind these was once summarised here, as a count of how
    // many carried no citation yet. It rotted, and it cost somebody: a
    // consumer checking what backed a promise before building on it read the
    // count, believed it, and filed against a gap that had been closed for
    // weeks. The count was true the day it was written and nothing made it
    // move.
    //
    // So there is no count here now. Status is DERIVED from citations and
    // read from the catalogue or the report; a number in a comment is a
    // second copy of it that no test can fail. A promise nothing proves says
    // so in its own first word, which is the only place that cannot drift
    // from the thing it describes.

    PROC_STEP_SERVICE_EMBEDDABLE("One embeddable runner registers step services and needs "
            + "only the participation lane — no orchestrator, no transport, no access to "
            + "the tenant's dbo — so the same bundle runs inside the platform's container, "
            + "on a separate machine, or in a pod scaled per step, stateless over the "
            + "tenants whose lanes it is handed."),

    PROC_FAILURE_IS_RELEASED("A failing or throwing step service releases the run with the "
            + "reason — never closed, never lost — back to the list: open to automation again "
            + "only for a fault its step declared will pass, and to people otherwise."),

    PROC_A_CLAIM_IS_LOST_TO_SOMEBODY_NOT_TO_THE_CLOCK("A participant holds the run it "
            + "claimed until somebody acts on the run — the housekeeping that hands a lapsed "
            + "claim back, or another participant taking it — and not merely until its "
            + "deadline passes. The deadline is what lets others notice a holder that died; "
            + "it never tells a holder that paused that its work has gone, so a holder slower "
            + "than its own deadline is still handed the work it claimed, and a run nobody "
            + "began is not sent to people as the failure of work that never happened."),

    PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN("Only the participant holding a run may checkpoint "
            + "it, name a milestone on it, hand it back or close it, and only while it holds "
            + "it: once somebody else has acted on the run — the housekeeping that hands a "
            + "lapsed claim back, another participant taking it — what the former holder says "
            + "is refused as a refusal and changes nothing, however the two writes race. A "
            + "runner told so drops the work quietly instead of releasing it as a failure, so "
            + "a run housekeeping already routed is not sent to people, and nobody's live "
            + "claim is cleared by a participant that no longer holds it. The same holds at "
            + "the step door: a person who claimed the run there, or the client that started "
            + "it, closes or checkpoints it only while it still holds it, and one that no "
            + "longer does is answered as for a run that never existed."),

    PROC_A_CLAIM_IS_NAMED_BY_THE_STORE_NOT_BY_ITS_TAKER("A claim is told from every other "
            + "claim by a name the store gives it as it lands, and a holder is judged by the "
            + "claim it carries rather than by what it calls itself. Two replicas of one "
            + "executor — the same name, version and credential — can each hold a run in turn, "
            + "and the one whose claim was handed back is refused what it says about the run "
            + "the other now holds."),

    PROC_A_HOLD_RUNS_FROM_WHEN_THE_CLAIM_LANDS("A participant claims a run for a duration, "
            + "and the store measures it from when the claim is written, by its own clock — "
            + "never from a deadline the participant computed before asking. However long "
            + "the ask took to arrive, nobody else may take the run until the whole hold has "
            + "passed since the claim landed."),

    PROC_A_RUNNER_ASKS_ONCE_IT_HOLDS_ITS_STEPS("A runner told which steps its host is about "
            + "to register asks no lane for work until it holds them all, so a host whose lanes "
            + "arrive before its services does not move its participant past the work of the "
            + "steps still arriving — work that would then never be offered to it."),

    PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT("The runner reports its counts per "
            + "step — performed, failed, mean duration and the last failure's reason — in each "
            + "heartbeat under dbo.runner, beside what the worker's own contributors add under "
            + "namespaces of their own; a contributor claiming dbo. is refused. A declaration "
            + "carries no counts and is said when it changes or did not land, never as a sign "
            + "of life; presence stays derived from the cursor."),

    /** TODO: prove it in a test. No single test walks catalogue → CodeSystem/PlanDefinition
     * → "never hand-edited" end to end; the projection generator itself has no negative
     * test that a hand-edit would be overwritten or refused. */
    PROC_CATALOGUE_IN_STORE("Planned — Process and step definitions (with profiles, planes and "
            + "projections) are part of DBO's own vocabulary; projections are generated, "
            + "never hand-edited."),

    PROC_STEP_DECLARES_ITSELF("A step declares its id, version, the storage domains "
            + "it reads and writes, opaque shape references for what it consumes and "
            + "produces, the actions it contains, and whether it may be overridden. Ids "
            + "are <module>.<process>.<step>, globally stable, contributed by being "
            + "installed, and a step referenced but not installed is refused by name."),

    PROC_RUN_NAMES_THE_STEP_VERSION("A run records the version of the step declaration it "
            + "ran under, beside the executor's version and provider: reproducing a "
            + "decision needs the definition as well as the runner."),

    PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS("A tenant's spec declares the steps its "
            + "work cannot do without. The tenant serves and its runs queue regardless — "
            + "the system is asynchronous by design — and what the list decides is "
            + "classification: a mandatory step nothing has contributed is an incident, "
            + "named and cleared as contributions come and go, while every undeclared "
            + "step's absence is no incident at all."),

    PROC_REPORT_THROUGH_DECLARED_ACTIONS("A report lands through the actions the "
            + "step declares: closing needs close, reopening needs reopen, and a verb "
            + "the step does not declare is refused naming both sides. A step that has "
            + "not declared actions is not narrowed, and releasing is never narrowed — "
            + "failure honesty must not be refusable."),

    PROC_CLOSED_CAN_BE_REOPENED("A closed run, or one waiting for people, can be reopened "
            + "— a deliberate, recorded act through the step's declared reopen action — making "
            + "the run claimable again with the reason on the record, and saying whether "
            + "automation may take it, instead of a second run invented to disagree with the "
            + "first."),

    PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT("Undoing a judgment already made about "
            + "work is reached through the lane like every other act, and by its own half "
            + "of an entitlement. A credential that performs a step does not thereby "
            + "overturn its closures — not even one that speaks for the whole tenant — and "
            + "a credential that supervises takes no work. Both halves must admit the act: "
            + "the entitlement names the step and the step declares the action, and a "
            + "supervisor asked for a step it does not name, or for one whose declaration "
            + "omits reopening, is refused by name rather than quietly doing nothing."),

    PROC_STEP_SHAPE_VALIDATION("A payload is validated against the shape a step declares "
            + "through the face's existing payload capability, and a shape the face "
            + "cannot resolve is an issue rather than a pass."),

    /** TODO: prove it in a test. The free-string process-domain code exists on Run and
     * is written; no test yet asserts a view or projection filtering BY it — that arrives
     * with the console (#75). */
    PROC_DOMAIN_CODE_FILTER("Planned — Every process and step carries a free-string process-domain "
            + "code; views and projections filter by it."),

    /** TODO: prove it in a test. ArchiveCoversEveryTableIT proves every TABLE is archived,
     * generically — no test isolates the run type's own claim (envelope-queryable,
     * versioned, dropped with the tenant) the way BackupCoversTheTenantIT does for PDI. */
    PROC_WORK_IS_AUTHORED_ON_THE_SURFACE(
            "Work is authored on the tenant's own surface, never on the lane: a document "
            + "posted there that names a declared step becomes a run, created through the "
            + "same door every run is minted at and refused by name where its rules are "
            + "not met — an unknown step, an undeclared slot, an unfilled declared slot, a "
            + "key already used. The run is what is stored, and it reads back as the "
            + "same document as it advances. A participation credential cannot author."),
    PROC_RUN_HAS_A_RECORD("Every run of a step is a record in a tenant's own store — a "
            + "registered type, so it is envelope-queryable, versioned, carried by the "
            + "backup and dropped with the tenant. A run in a private table has none of "
            + "those, and cannot be seen or acted on."),

    PROC_A_RUN_KEEPS_STATUS_CLAIMANT_AND_ELIGIBILITY_APART("A run keeps three facts "
            + "apart, each where a FHIR Task keeps it: where it stands — ready, in progress, on "
            + "hold, completed, failed or cancelled — who holds it — an executor as a FHIR "
            + "Device, a person as a PractitionerRole — and who may take it next — automation "
            + "as well as "
            + "people, or people alone, and not before when. One word for all three could not "
            + "say that released work is waiting, that a person holds it, or that it was ended "
            + "rather than done."),

    PROC_RUN_TALLY_AND_ITEM_OUTCOMES("A run over N items where K fail records one run "
            + "with a tally and K item outcomes, and does not abandon the remaining "
            + "N minus K."),

    PROC_ESCALATION_BY_FAILURE_CLASS("A failure goes where its step declared. A fault the "
            + "step declares will pass returns the task held back, open to automation again "
            + "after a delay, and counted, and past the declared attempts it goes to a person; "
            + "a record fault ends the task as failed, because trying again would be refused "
            + "in the same words; and any other failure returns it to the list open only to "
            + "people, with the failure as its reason. A lapsed claim passes only where the "
            + "step says so. So a fault nobody said would pass is seen rather than retried "
            + "without end."),

    PROC_CLOSE_BY_RE_EVALUATION("Where a condition is machine-checkable, fixing the "
            + "cause closes the run on the next pass; closing by hand exists only for "
            + "conditions nothing can re-check. Closing by click is how a card reads "
            + "resolved while the fault is live."),

    PROC_CONFIG_WITHDRAWAL_IS_DECLARED("Only a read a source says is complete may "
            + "withdraw what it no longer names, so a partial read and an unreadable one "
            + "take nothing away. What is held is the applier's to answer and undoing is "
            + "the applier's to do: one that cannot say withdraws nothing, and one that "
            + "cannot undo makes a card rather than a silence — nothing is removed by "
            + "machinery that was never told how to remove it."),

    PROC_CONFIG_READ_FROM_A_SOURCE("Configuration is read from a declared source — a "
            + "repository, a mounted directory, a lane — and what a scope last agreed "
            + "with is recorded on its own run, so an unchanged source is a read rather "
            + "than a re-application, and a scope with a card open is re-applied until "
            + "the card closes. A source that cannot be read says so: it never answers "
            + "with an empty set, because empty and unreachable are the same sentence "
            + "to whoever then has to decide what is missing."),

    PROC_CONFIG_APPLIES_AS_A_SWEEP("Applying a declared set is a sweep: it closes when "
            + "what is here agrees with what was declared, one declaration nobody can "
            + "apply is a card naming it and the rest still apply, and the pass tallies "
            + "what it read, applied and skipped. The store's own bring-up configuration "
            + "goes through it too — a partial application whose only account is a log "
            + "line is what presents to whoever declared it as 'my configuration had no "
            + "effect'."),

    PROC_RUN_KINDS("A pipeline closes when every item is terminal; a sweep closes when "
            + "the world agrees. A reconciler modelled as a pipeline never ends, and its "
            + "needs-a-person queue fills with work that is merely still converging."),

    /** TODO: prove it in a test. Run.parent is a String key and nothing walks a chain of
     * runs to assert it never crosses a domain or a system — the rule is enforced by
     * convention at the two call sites (item(), not by a refusal anywhere. */
    PROC_ONE_PARENT_NEVER_ACROSS_A_BOUNDARY("Planned — A run has at most one parent, and "
            + "parenthood never crosses a domain or a system: items are children, "
            + "subprocesses and continuations are references. A parent's close must "
            + "mean something for its children, and cannot across a boundary this "
            + "runtime does not control."),

    PROC_CORRELATION_TRAVELS_OPAQUE("A correlation carried from another system is "
            + "echoed and never interpreted, so a cross-system join is queryable from "
            + "either side without that system's vocabulary entering the engine."),

    PROC_TRACE_RIDES_THE_LANE("A run carries the trace context it was given, across "
            + "the participation lane and down to the runs it causes, so work claimed in "
            + "one process and performed in another is one chain. It is carried and never "
            + "minted, and never a metric dimension."),

    PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT("A run's envelope carries its "
            + "status, who may take it, its step and its counts — never item references or "
            + "messages. The "
            + "envelope is a disclosure surface, and progress must not name what was "
            + "being processed."),

    PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC("Resolution walks the overlay chain — "
            + "baseline, zone, organisation — and the most local willing and permitted "
            + "candidate runs, one at a time in declared order. Racing candidates makes "
            + "the same input behave differently under load and doubles effects nothing "
            + "outside the store can undo."),

    PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE("Precedence selects; the step declares "
            + "whether it may be overridden and by which scope class, and not "
            + "overridable is the default. Specificity is self-declared, so precedence "
            + "alone lets any party displace a national rule by narrowing its scope."),

    PROC_RUN_NAMES_WHAT_RAN_IT("A run records the executor, its version, its provider "
            + "and the scope it was chosen at. A provider can be withdrawn and a scope "
            + "re-declared, so a resolution nobody wrote down is a decision nobody can "
            + "reproduce."),

    PROC_AUTOMATION_IS_DECLARED("Whether a step is automated here is declared "
            + "configuration on the same chain, as visible and as auditable as a "
            + "terminology overlay — never a code path that happens to be "
            + "unreachable."),

    PROC_FALL_THROUGH_IS_COUNTABLE("Work no executor took is open only to people and "
            + "counted per step and per zone. That number is the automation backlog "
            + "stated as a fact rather than an opinion."),

    PROC_WHO_OWES_THE_NEXT_ACT_IS_DERIVED("Who owes a run's next act is derived from its "
            + "status, its claimant and who may take it, never stored beside them: a claimed "
            + "run waits for its owner, an unclaimed one open to automation for a machine and "
            + "is nobody's card, one open to people alone for a person, and one that is over "
            + "for nothing. Waiting for a person is one question, ready and for a person alone, "
            + "asked the same of the store, across the wire and at the console."),

    PROC_EXECUTOR_DECLARES_ITSELF("A participant announces process, step, scope, "
            + "version and provider as a record in the tenant's store, and resolution "
            + "walks those declarations rather than the bundles installed in one "
            + "container. A candidate that can only come from a local bundle makes a "
            + "tenant a single machine."),

    PROC_A_TRACKABLE_MAY_ROUTE_OTHERS("A participant may route others, and the store keeps "
            + "one fact about each routee: the participant it sits behind, at any depth. That "
            + "edge is what lets a router seal work past itself to a routee; what a routee is "
            + "and how it is doing are the router's to say in its heartbeat, and the store "
            + "imposes no freshness rule on them."),

    PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB("A router reports whom it routes the way it "
            + "reports what it can do: a verb of the participation lane, beside declare, "
            + "carrying the whole set each time. The reporter is the lane's own participant "
            + "rather than anything on the wire, so a routee named with nobody in front of it "
            + "sits behind that participant and a router cannot report routees for somebody "
            + "else."),

    PROC_A_DROPPED_ROUTEE_IS_NOT_SEALED_TO("A routee missing from its router's latest report "
            + "is no longer behind it: the edge goes with the report that left it out, and the "
            + "router may not seal work to it or carry home an opening in its name until a "
            + "report names it again."),

    PROC_PRESENCE_IS_DERIVED("A participant is present while its named feed cursor "
            + "moves; a declaration whose consumer is behind and unmoving is "
            + "declared-but-not-present, skipped by resolution and shown as such. Resolution "
            + "reads no heartbeat and no lease — a heartbeat is contact, which is the "
            + "application's and decides nothing here — and a caught-up participant's cursor "
            + "does not move either, so silence with nothing waiting is not absence."),

    PROC_A_CONTACT_LISTENER_IS_OPTIONAL_PER_STEP("An application may register, per step, a "
            + "listener told when a node comes into contact with a worker of that step and when "
            + "it loses it; with none registered the node tracks nothing for the step. Activity "
            + "is any request from that worker for that step — a poll naming it, a claim, "
            + "checkpoint, release or close of one of its runs, and a heartbeat for every step "
            + "the worker declared — and a worker is its client, its executor's name and its "
            + "version. Contact is held in memory on the node that heard the worker, decides "
            + "nothing in the store, and every event names its node."),

    PROC_A_CONTACT_LISTENER_DECLARES_ITS_SILENCE("A contact listener declares how long a "
            + "worker may say nothing before it is unknown, and there is no default: a listener "
            + "declaring none is refused at startup, by name. A worker heard after it was "
            + "unknown appears, each heartbeat while it is in contact delivers its statistics, "
            + "and silence past the listener's own threshold makes it unknown — never gone. Two "
            + "listeners on one step may declare different silences."),

    PROC_A_NODE_START_RESETS_CONTACT("When a node starts, every contact listener is told that "
            + "everything for its step is unknown on that node, and a worker heard there again "
            + "appears again: contact is not carried across a restart."),

    PROC_CONTACT_IS_RECORDED_ONLY_THROUGH_WORK("Nothing about contact reaches a tenant's "
            + "records unless a listener asks for it as work: the listener starts a run through "
            + "the ordinary way of asking for one, under a key of its own so the same decision "
            + "made twice — on two nodes, or after a retry — is one run, and the run's step "
            + "writes the record through its result, validated by the tenant and carrying the "
            + "run. A listener in the container starts it through the initiator the container "
            + "registers; an application over HTTP through the same contract bound to the "
            + "tenant's door."),

    PROC_A_HEARTBEAT_IS_A_LANE_VERB("A worker says it is still there with a heartbeat, a verb "
            + "of the participation lane on every transport, carrying one JSON object of "
            + "statistics. It counts as activity for every step the worker declared on that "
            + "lane, so a worker that is woken rather than polling, or one holding a long claim, "
            + "stays in contact; it writes nothing to the tenant's records and never extends a "
            + "claim."),

    PROC_HEARTBEAT_STATISTICS_ARE_OPAQUE_AND_BOUNDED("Heartbeat statistics are an open, "
            + "nested document whose top-level keys are namespaced by whoever contributed them, "
            + "with dbo. reserved for the store's own runner. The store neither interprets, "
            + "validates nor stores them, and a node refuses a heartbeat whose statistics exceed "
            + "its limit — 64 KB unless the deployment says otherwise — naming the limit. They "
            + "travel authenticated and outside any sealed work, so a sender puts nothing about "
            + "a person in them."),

    PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE("What a peer sends applies once however "
            + "often it is sent, and a batch arriving behind a newer one does not put "
            + "the older version back. The comparison is the source version, so neither "
            + "property depends on the transport being careful."),

    PROC_LANE_EPOCH("A lane carries an epoch, and a peer resuming a cursor issued by "
            + "another lane instance is refused rather than replayed — a replica "
            + "restored from a copy looks healthy while resuming a position that no "
            + "longer means anything."),

    PROC_LANE_IS_A_TENANT_SERVICE("A tenant's replication lane stands in the service "
            + "registry beside its store, so a bundle in the same framework takes the "
            + "one the tenant's own door serves rather than assembling a second set of "
            + "cursors for the same peer or re-entering over loopback with a credential. "
            + "It stands there for every tenant: a lane needs no authority, because the "
            + "registry never asks who is calling."),

    // ── sealed work — decided in review, nothing built ──

    PROC_WORK_TRAVELS_SEALED(
            "Work travels in two parts. The manifest — tenant, step, the task, and "
            + "references to the documents named — is readable, because routing on it "
            + "is its job. The payload — the documents themselves — is sealed in the "
            + "carrier form under a data key of its own, wrapped once per participant "
            + "meant to open it and to nobody who merely carries it. A sealed payload is "
            + "a copy in flight and not the record: the store keeps the original, and the "
            + "copy is bounded by the work that caused it."),
    PROC_IDENTITY_IS_REASSEMBLED_AT_THE_TENANT(
            "Opening a sealed payload yields the carrier form, so a runner needs no vault "
            + "to do its work. Putting the person back together is a further act, and it "
            + "is performed at the tenant on the runner's behalf rather than by handing "
            + "the vault over — everywhere, and not only where the runner is far away, "
            + "because a reassembly the store did not perform is one the trail cannot "
            + "answer for. It is refused without a stated purpose, as every identifying "
            + "read is, refused for a document the run does not name, as every reach is, "
            + "and recorded as a disclosure naming the run that occasioned it. The answer "
            + "comes back sealed to the asker alone, because the plane it may cross holds "
            + "nothing readable and reassembled identity is the last thing that should be "
            + "the exception."),
    PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT(
            "A participant generates its keypair before it is enrolled and offers the "
            + "public half as part of enrolling; the private half never crosses. Payload "
            + "data keys are wrapped to that key, so what a participant may open is "
            + "decided by what it holds rather than by what it is told."),
    PROC_THE_ROUTER_HOLDS_THE_CLAIM(
            "The thing that can reach the store is the participant, and it holds the "
            + "claim. A routee behind a router is routed because it cannot reach the "
            + "lane, so the router claims, forwards, waits and reports — holding a claim on "
            + "work it cannot read — while the routee holds the key and does the work. "
            + "Participant versus routee is a fact about the attachment, not about what it is."),
    PROC_DONE_MEANS_DONE(
            "A participant does not report done before the work is done. A run closes on "
            + "what is reported and the store has no view below that seam, so an early "
            + "report is a true-looking record of something that has not happened. A "
            + "participant with durable execution underneath waits for it; a router waits "
            + "for its routee; a wedged one lets the claim lapse and the run reads released."),
    /**
     * The type described a decision nobody could make: a record for it, a
     * resolution rule that honoured it, and nothing that could write one.
     *
     * <p>Where it is read is what it can promise. A reader on the resolution
     * chain alone would have made the console honest and stopped nothing —
     * work here is pulled rather than dispatched, and the claim path never
     * consults resolution. So it is read where a claim is taken, which every
     * automatic claim passes through, and the console reads the same one.
     */
    PROC_AUTOMATION_IS_A_DECLARED_SWITCH("Whether a step is automated here is declared "
            + "configuration on the scope chain, most local winning, and it is read where a "
            + "claim is taken — so switching it off stops the next automatic claim, leaves "
            + "work already held alone, and says the same thing to an operator asking who "
            + "would run the step as it does to the runner asking to take it."),

    /**
     * The first of the two that hold the level invariant up. This one makes
     * the declaration possible and legal in exactly one file; the other
     * refuses one code declared at both levels. They are separate because
     * declaring is not running: nothing consumes a fleet step yet, and a
     * design whose invariant arrived after its joiner would have acquired two
     * schedulers over one run in between.
     */
    PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE(
            "A step the deployment performs for every tenant is declared in the management "
            + "tenant's own descriptor and nowhere else, under a key of its own: what it "
            + "takes, which of those it OPENS rather than carries, whether a tenant admits "
            + "it or joining required it, what happens to work whose processing is not yet "
            + "approved, and where its queue lives. An ordinary tenant declaring one is "
            + "refused by name, naming the key and the tenant — a tenant declares the steps "
            + "it offers, and what the deployment does to every tenant's data is not among "
            + "them. Slots are not checked against the declaring tenant's own types, because "
            + "the types belong to the tenants whose work it performs."),

    /**
     * The other half, and the one with a deadline: it has to hold before
     * anything joins work. A code at both levels is not a configuration
     * mistake that shows up as a bad answer — it is two schedulers reaching
     * for one run, each correct, which is the failure this store already
     * describes for two sites of one tenant.
     */
    PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL(
            "A step code is the deployment's or a tenant's and never both. A tenant offering "
            + "a code the management tenant declares under 'fleetSteps' is refused by the "
            + "sweep that reconciles declarations, naming the tenant, the code and the "
            + "management tenant — and it is refused whichever side arrived second, because "
            + "every declaration is read again each pass. A tenant already serving keeps "
            + "serving while it is refused: what is wrong is the pair, and the tenant may not "
            + "be the side that changed."),

    PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE(
            "Declaring a step the deployment performs prepares the substrate its queue lives "
            + "on, the way declaring a tenant prepares its database — a runtime-owned "
            + "database carrying a durable bootstrap and nothing else: no face, no zone, no "
            + "personal-data isolation, no store schema, no authority. It is made through the "
            + "admin connection that provisions tenants and never through the path that "
            + "provisions one, because a thing that is not a tenant must not look like one to "
            + "everything downstream. Placement is the deployment's: a step names the "
            + "substrate it wants, several steps may name one and share it, and a step naming "
            + "none gets its own. A withdrawal closes the step and removes nothing — what was "
            + "queued belongs to tenants who believe it is being done, and dropping the "
            + "database is a person's act."),

    PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK(
            "One observer reads every tenant's work feed as a named durable consumer and "
            + "offers each run of a step the deployment performs into that step's queue, on "
            + "the substrate that step named. A run of a step the deployment does not perform "
            + "is left where it belongs, which is what the two levels are made of. It CLAIMS "
            + "nothing — the run stays offered on nothing and a joiner that falls behind reads "
            + "as a cursor that is not moving rather than as work nobody wanted — and it "
            + "CONSUMES nothing, holding a writer rather than an executor, so it polls no "
            + "queue and holds no listener. Offering is idempotent on the run's own identity, "
            + "because no transaction spans reading a tenant's feed and writing to a step's "
            + "substrate: a restart or a lost acknowledgement re-offers and writes no second "
            + "item. A tenant whose feed cannot be read is one tenant and not the fleet: the "
            + "pass says so once per reason, offers every other tenant's work, and says so "
            + "again when that tenant can be read — because a deployment where one tenant is "
            + "in trouble is not a deployment where nobody's work is offered, and which "
            + "tenants stopped would otherwise be decided by the order they happen to be "
            + "iterated in."),

    PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT(
            "A step the deployment performs is offered to an application as ITS STEP'S QUEUE "
            + "rather than as a lane per tenant: one consumer, whatever the deployment's size, "
            + "handed items that happen to name different tenants. The bean names no tenant "
            + "and is not told which exist, so a tenant joining needs nothing redeployed. It "
            + "keeps nothing between asks — every call is answered from the item it was handed "
            + "— which is what lets a consumer be restarted mid-run and carry on, and what "
            + "makes several of them a way to perform a hot step faster."),

    PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED(
            "An application performs a fleet step by registering a bean that names its own "
            + "step, and nothing else: the container builds the consumer, the durable layer "
            + "and the pool, and builds one per SUBSTRATE so two steps placed together are "
            + "served by one. The two arrivals are order-independent — a bean registered "
            + "before the deployment has read its declaration is held and taken up when the "
            + "step is declared, because under an assembly the application's beans ordinarily "
            + "come first. A bean whose step is never declared stays held and is named by the "
            + "deployment as awaiting a declaration, because a bean nothing will ever offer "
            + "work is indistinguishable from a step with nothing to do."),

    PROC_A_PARTICIPANT_ASKS_FOR_WORK_IT_NEED_NOT_PERFORM(
            "A participant is an initiator as well as a performer: on the same credential and "
            + "the same enrolment it holds a lane with, it authors a run by asking the "
            + "TENANT's own step door — which is where every run is authored, because the run "
            + "belongs to the tenant it is about and this store schedules nothing on a "
            + "tenant's behalf. The door takes a step the DEPLOYMENT declared as readily as "
            + "one the tenant did, and still refuses one a participant merely introduced, "
            + "since what separates them is who wrote the declaration down rather than who "
            + "performs it. What takes the run is then decided by entitlement, so a "
            + "participant can ask for work it cannot do and a fleet step asked for by one "
            + "external application is performed by a bean inside the deployment."),

    PROC_A_RUN_ANSWERS_ITS_INITIATOR(
            "A run answers the application that asked for it. At the run's own address, on the "
            + "credential it asked with, that application reads the run as a Task: how it "
            + "stands, what it was over, and what the step produced — the counts it kept and "
            + "the versions it wrote. So an application that asks for work learns how the "
            + "work ended without being handed a door onto the tenant's records, which the "
            + "credential that asks for work deliberately does not hold. Nobody else can read "
            + "that answer: another client, a credential that may not act in work, and a run "
            + "that does not exist are all answered alike, as not found, because a refusal "
            + "that differed from absence would tell whoever asked which runs exist."),

    PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT(
            "A step's result may carry records, and the tenant writes them: the participant "
            + "that performed the step holds no records credential and is given none. They are "
            + "written under the run, through the same path a transaction posted to the "
            + "tenant takes — so the profile validates them, the identity rules hold for them "
            + "and what identifies a person is sealed as for any other write — and all of one "
            + "result lands or none of it does. Only the types the step's declaration says it "
            + "writes are accepted. The run then names each version it produced, which is how "
            + "the application that asked for the work learns which record it made."),

    PROC_A_REFUSED_RESULT_ENDS_THE_RUN(
            "A result the tenant refuses for what it says — a record its profile rejects, an "
            + "identity it already holds, a type the step does not write — ends the run with "
            + "the tenant's reason, and the application that asked reads the run as failed, "
            + "with that reason. It is not handed back for another attempt, because the same "
            + "result would be refused in the same words; a step that crashed or ran out of "
            + "time is, because another attempt may succeed. So the asker can tell work that "
            + "was done, work that was refused, and work still owed apart."),

    PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT(
            "A slot declares what fills it in FHIR's own notation: 'Reference(T)' for one the "
            + "tenant already holds, a bare 'T' for one given with the run, and '[]' for "
            + "several of either. A reference is resolved by the store where the data already "
            + "is, so whoever authors the run need not hold it, be entitled to read it, or send "
            + "it. A given object has no record, no id and no version: it travels with the run, "
            + "is opened by whoever performs the step, and is written to the store by nobody "
            + "unless that step decides it should be and does so as its own act. A slot is "
            + "homogeneous, so the run records it without a marker — a string is a reference, "
            + "an object is given, an array is several — and the door refuses a request whose "
            + "shape is not the one declared, naming the slot and what it takes. Order within a "
            + "repeating slot is kept, because a list somebody sent is a list they meant."),

    PROC_A_REFERENCE_MAY_BE_A_SEARCH(
            "A referred slot is filled by 'Type/id' or by a search — 'Organization?identifier="
            + "urn:x|1' — so whoever authors a run can name a record by something they know "
            + "rather than by an id they would have to look up first. The search is resolved at "
            + "the DOOR and the run records the references it matched, because what the work is "
            + "over is fixed when the work is created: resolved at claim time instead, two "
            + "performers could be handed different sets and the register could not say what "
            + "was opened. A slot that takes one and matched none or several is refused, saying "
            + "how many, rather than picking one. It buys no reach: the narrowing is compiled "
            + "by the face's own search compiler and run by the engine, and this door states no "
            + "purpose and accepts none — so a search that would match on an identifying "
            + "element is refused here outright, in the door's own words, and stating a purpose "
            + "does not open it. Asking whether somebody is here is not something a credential "
            + "for work may do, and on the records surface a stated purpose is exactly what "
            + "turns that question into an exact lookup through the vault."),

    PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS(
            "A fleet performer is handed the run's slots RESOLVED — the objects themselves, not "
            + "the references the run was authored with — on the hold it just took, because the "
            + "claim is what entitles it both to the data and to reporting. Its working context "
            + "is the item it was given: it runs outside the store, has no route into the "
            + "tenant and no verb that takes a reference, so a slot delivered as a reference "
            + "would be a slot it could do nothing with. Which is also what referring is FOR: "
            + "whoever authored the run named data it need not hold, need not be entitled to "
            + "read and never put on the wire, and the store resolved it where it already was."),

    PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES(
            "Work the deployment performed for a tenant is reported back through that "
            + "tenant's OWN lane, so an outcome from a fleet consumer meets exactly the rules "
            + "an outcome from a participant on a port meets: whether a machine may close this "
            + "step, whether the report is in order for the state the run is in, and who is "
            + "recorded as having performed it. The run closes in the tenant that authored it, "
            + "naming the executor the application gave rather than the deployment's own name, "
            + "and a report that breaks one of that tenant's rules is refused exactly as it "
            + "would be on a lane. The performer claims before it reports, because the hold is "
            + "what says whose account of the work counts."),

    PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT(
            "A step the deployment performs is admitted by a tenant saying nothing and "
            + "declined by one line, and a declined step is not offered that tenant's work at "
            + "all — the run stays where it is, exactly as a run of a step the deployment does "
            + "not perform does, because from the tenant's side those are one fact. A few steps "
            + "the deployment REQUIRES, and declining one of those is refused by name at the "
            + "declaration, saying where the requirement is written: an agreement signed by "
            + "joining is not an agreement if a tenant can leave it by editing its own file. "
            + "Declining applies while the tenant serves, because withdrawing authorisation "
            + "must not cost an outage."),

    PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA(
            "A tenant reads a register of every payload the deployment opens of its data: one "
            + "row per SLOT a step opens, naming the step, the slot, the type, whether it may "
            + "be declined and what happens to work not yet authorised. A step that only reads "
            + "the envelope is not on it, because it discloses nothing. The register is DERIVED "
            + "from the deployment's declaration and the tenant's own, never stored beside "
            + "them, so what the deployment does and what a tenant reads cannot drift apart — "
            + "and a step the tenant declined contributes no rows, because a step declined and "
            + "a step not performed are one fact from the tenant's side."),

    PROC_A_DISAGREEMENT_IS_AN_INCIDENT_NOT_A_REFUSAL(
            "Where the trail disagrees with the register, the store says so as an incident in "
            + "the tenant's own account, naming who opened what, in which slot of which step, "
            + "and on what occasion. It cannot be a refusal: an enrolled processor holds the "
            + "key to what was sealed to it and no cryptography stops a party that can decrypt "
            + "from decrypting, so detection is the honest guarantee and is offered as one. The "
            + "comparison is computed from the tenant's own records rather than stored beside "
            + "them, because a stored incident would be a second place to ask and the first "
            + "disagreement between the two would leave a tenant unable to say which was true."),

    PROC_A_PROCESSOR_IS_ENROLLED_PER_TENANT(
            "An application performing the deployment's steps is enrolled on EACH tenant it "
            + "performs for, with the public halves of the keys a payload is sealed to and an "
            + "opening is checked against. Per tenant rather than once for the fleet, because a "
            + "payload is sealed to an enrolled participant and enrolling at fleet level would "
            + "mean something re-seals a tenant's payload and therefore holds tenant keys — the "
            + "thing the carrier rule exists to exclude. One record per tenant covers every step "
            + "on that tenant's register, because enrolment being per tenant must not become "
            + "enrolment one step at a time. Only public halves reach the store, so a copy of "
            + "the record opens nothing, and the credential it carries is minted and held by "
            + "nobody: the processor is authenticated by its signature and never signs in."),

    PROC_THE_REGISTER_IS_READ_AT_A_DOOR(
            "A tenant reads its register and the incidents kept against it at a door of its own, "
            + "under the scope it authorises a register with: the rows the deployment's steps "
            + "open of its data, the steps it declined, whether what is done changed since it "
            + "authorised, never present for a tenant that never did, and every opening that ran "
            + "unauthorised or disagrees with its trail. Whoever operates the deployment reads, "
            + "under the operator's token, which tenants have rows standing unauthorised and how "
            + "many incidents each holds, and which beans wait for a step nobody declared — and "
            + "never which of a tenant's records were opened."),
    PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE(
            "A tenant authorises a register by writing down which one it read — the register's "
            + "own digest, one value for the whole of it — so authorising is answerable all at "
            + "once and a tenant approving rows one at a time could never be sure it had "
            + "finished — and every row is named individually inside that act, so the store can "
            + "still say which single row is new or widened. Whether what the deployment does "
            + "with its data has changed since is then ONE COMPARISON rather than an audit. The digest covers every field a tenant "
            + "would decide on, including the posture, so a deployment cannot move a row from "
            + "not-until-approved to processed-and-named without the tenant's copy ceasing to "
            + "match — which would be a deployment approving its own widening. Never having "
            + "read a register is a different answer from having read a different one."),

    PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE(
            "What happens to work under a row a tenant has not authorised is the row's own "
            + "posture, stated by the deployment where the row is declared. A row that says NOT "
            + "UNTIL APPROVED has that tenant's work withheld from the step entirely — refusal "
            + "is real here and nowhere else in this design, because approval is known before "
            + "anything is sealed, so not offering the work actually prevents the processing. A "
            + "row that says PROCESSED AND NAMED runs, and the cost is carried by an incident "
            + "that stands until the row is authorised: it names the tenant, the step and what "
            + "is being opened, and says how long, because an incident reading the same on day "
            + "one and day ninety is one nobody acts on. A row that says APPLIED runs under the "
            + "agreement and raises nothing. Processed-and-named is the default, because a "
            + "halting default would turn an unanswered register into an outage caused by "
            + "nobody clicking. Per row, so a deployment may halt for a new row without "
            + "stopping everything else."),

    PROC_A_CONSUMER_TAKES_ONLY_ITS_OWN_STEPS(
            "A consumer dequeues the queues of the steps it serves and no others. Several steps "
            + "share a substrate on purpose, and a process listens to every queue registered in "
            + "its system database unless it says otherwise — so a consumer deployed for one "
            + "step would otherwise take another step's work, find nothing here that performs "
            + "it, and drain a tenant's queue into a process that never did the work. Two "
            + "consumers on one substrate each perform their own step and neither loses the "
            + "other's. A bean offered for a step a consumer does not serve is refused when it "
            + "is offered, rather than silently never being called, and an item for a step "
            + "nothing here performs is a fault rather than a quiet success."),

    PROC_A_LANE_OVER_THE_STREAM(
            "A lane runs over the store's own stream, full duplex, beside in-process and "
            + "HTTP: work goes out and travel, access and result events come home as they "
            + "happen on the same channel. It serves exactly the verbs the other two do, "
            + "and a runner cannot tell which it holds."),
    PROC_A_STREAM_RIDES_ANY_CARRIER(
            "The stream protocol rides a carrier the store does not own as well as its own "
            + "substrate: a host registers a carrier, every tenant's door opens on it, and the "
            + "same work reaches the same outcome as over HTTP. The store keeps authentication, "
            + "authorisation, the signature over each ask's bytes, sealing and the feed's "
            + "cursors; a carrier carries opaque asks, answers and wake-ups, may delay or drop "
            + "an ask, and an ask it altered is refused as a forgery."),
    PROC_A_STREAM_DOOR_OPENS_FOR_WHOEVER_CAN_ASK(
            "A tenant's door on the deployment's stream is opened when a participant that signs "
            + "its asks is enrolled on it, at bring-up or at any time after, and not before: a "
            + "tenant nobody can reach that way holds no door on the substrate, and one enrolled "
            + "later is served through a door opened for it."),
    PROC_THE_LANE_HAS_TWO_BOUNDS(
            "What moves between two replicas of one tenant has two bounds, deliberately "
            + "different: declarations by type — the tenant's own definitions, none of it "
            + "about anybody — which travel as every version of the types asked for since "
            + "the peer's position, filed under their source, read-only there, shadowed by a "
            + "local override and never revoked by work; and patient data by work, which "
            + "arrives with a task and leaves with it. What a run produced travels with the "
            + "run as a copy that outlives it. A type the lane does not admit is refused by "
            + "name, never quietly left out."),
    PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY("A record travels to a replica because a "
            + "piece of work names it, and is removed when no open run there still "
            + "names it. Work-driven arrival without work-driven expiry is a replica "
            + "accumulating a register one task at a time."),

    PROC_AUDIT_REPLICATES_AS_RECORDED("A replica's audit entries reach its peer as "
            + "that replica recorded them — original actor, original time, and the "
            + "replica named — and the arrival writes no second trail. Direct writes to "
            + "the audit type stay refused for every caller; the replication lane is "
            + "admitted through one narrow port that can express no other write, and a "
            + "re-delivered entry lands exactly once under the source's own identity."),

    PROC_MIRRORED_RUNS_ARE_FILED_BY_SOURCE("A run arriving from another replica "
            + "of the same tenant is stored under that replica, beside the local run "
            + "of the same key rather than on top of it."),

    PROC_CONTENT_CHANGES_INSIDE_WORK("A type may declare that every change to it "
            + "belongs to a run; a write with no run in scope is refused, naming the "
            + "rule. A change that belongs to nothing is visible and unexplainable — "
            + "history has it and audit names who, and nobody can say what it was "
            + "for."),

    PROC_A_RUN_NAMES_WHAT_IT_PRODUCED("A run records the versions it produced, "
            + "individually up to a cap and as a per-type high-water mark past it, and "
            + "says which of the two it is. Reading runs in order then reads the "
            + "content changes in order, so another replica asks for what it is "
            + "missing rather than comparing two stores."),

    PDI_ERASURE_IS_A_RUN("A person's erasure is asked for as work and answered by a run: the "
            + "run is the receipt, carrying what was found, how far the erasure got and when. "
            + "Asking twice finds the run that already exists rather than opening a second "
            + "account of one erasure, and a person this store never held closes the run "
            + "saying so — a repeated request is not an error and an unknown subject is not a "
            + "refusal. A failure releases the run with its reason rather than closing it, "
            + "because an erasure that read as done is the one outcome the record exists to "
            + "prevent."),

    PDI_ERASURE_SAYS_HOW_FAR_IT_GOT("The erasure names the points it passes — the key "
            + "destroyed, the index removed, the ledger written — so an erasure that stopped "
            + "between the irreversible half and the half that makes a restore safe is "
            + "visible as exactly that. It reports what it did rather than that it ran: "
            + "whether a key was there to destroy tells erased apart from was-never-here, "
            + "which are different answers to a data subject."),

    PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT("What a node reports about work leaves it "
            + "as measurements labelled from a closed set — whose work, which process and "
            + "step, what ran it, and how it ended as one word from a fixed vocabulary. A "
            + "failure's own words stay on the run, in the store of the tenant whose work it "
            + "was: an open field in a stream declared anonymous is how the declaration stops "
            + "being true without anybody editing it. Identifiers a caller chose are not "
            + "labels either, being unbounded, and neither is a correlation echoed from "
            + "another system, because nobody here knows what is in it."),

    PROC_REPORTING_RUNS_WHERE_NOTHING_COLLECTS("A node emits whether or not anything is "
            + "collecting: the discarding destination is the default rather than a fallback, "
            + "and an exporter that cannot be loaded leaves the node serving and quiet. "
            + "Emission that switched itself off without a collector would be a path "
            + "exercised nowhere but in production, and a store that refused to run without "
            + "a monitoring stack would have made observability a dependency of serving."),

    PROC_A_NODE_ANSWERS_ITS_CATALOGUE("A node says what it knows how to do — the steps "
            + "installed in it and the steps a participant introduced over a lane, each with the "
            + "party that contributed it, in which version, and which executor would take it "
            + "here now. It answers while serving no tenant at all, because the catalogue is "
            + "what is installed rather than what is running, and a node that has stopped "
            + "serving is exactly when somebody asks."),

    /**
     * Narrowed from the promise that also covered one node (now {@link
     * #PROC_A_NODE_ANSWERS_ITS_CATALOGUE}): the union, and the piece it needed was an
     * inventory rather than a transport. Declared candidates and introduced steps
     * accumulate without one, because every participant writes into the tenant's
     * store whatever node it runs on. What never left a node was its INSTALLED
     * catalogue — it cannot travel through the introduction door, since a step
     * declared by both doors is refused as a collision, which two nodes carrying the
     * same modules would hit immediately — so each node now serves it as an inventory
     * under the deployment's token, and the reader outside unions the inventories.
     */
    PROC_NETWORK_MAP("A deployment answers what its nodes have installed between them, "
            + "and in which versions — one answer rather than a walk. What each node has "
            + "is descriptive, an inventory of that node, and never a second declaration "
            + "of a step somebody else already declared."),

    PROC_TRACE_JOIN("From any process instance, the steps and the exact resource "
            + "diffs and audit records they produced are navigable."),
    // ── CORE — migrated from hand-written prose (2026-08-27) ──

    CORE_TWO_WRITERS_OF_ONE_OBJECT_TAKE_TURNS(
            "Two writers of one thing at the same moment take turns rather than one of them "
            + "failing: an object two writers create at once is created by one and changed by "
            + "the other, alone or inside a unit, and a code system imported twice at once "
            + "ends on the import that finished last. Writers of different things never wait "
            + "for each other."),
    CORE_PAYLOAD_IS_TRUTH(
            "A stored object's payload is the single source of truth; every searchable "
            + "projection is derived from it and can always be rebuilt."),
    CORE_DECLARED_TRUTH_FORM(
            "Which representation is authoritative for a type (payload or normalized "
            + "form) is declared by its personality, never implicit."),
    CORE_REINDEX_IS_AN_OPERATION(
            "Changing how objects are indexed is a background operation, never a data "
            + "migration."),
    CORE_EXTERNAL_IDENTIFIERS(
            "Every object has one internal id and any number of `{system, value}` "
            + "identifiers, rebuilt from the payload on each write and searchable "
            + "together."),
    CORE_REFERENCE_EDGES(
            "References between objects are extracted as owned edges on write and power "
            + "referential reads."),
    CORE_VERSIONED_HISTORY(
            "Every write appends an immutable version; version-aware reads and "
            + "optimistic concurrency (ETag) are first-class."),
    CORE_READ_YOUR_WRITES(
            "A write returns only after its data and its change event are committed in "
            + "one transaction. (D1)"),
    CORE_UPGRADE_ON_READ(
            "Old payload versions are upgraded lazily by registered converters; a "
            + "schema-version transition never requires a big-bang rewrite."),
    CORE_PARAMETERIZED_SQL(
            "No value is ever concatenated into SQL text. (D2)"),
    CORE_SIBLING_MODELS(
            "Non-FHIR object models ride the same engine as FHIR resources, not beside "
            + "it. (R6)"),
    CORE_A_SEPARABLE_DOMAIN_HAS_A_SCHEMA_OF_ITS_OWN(
            "A domain that is handed over on its own — dumped, restored, granted on, "
            + "dropped — lives in a schema of its own rather than sharing the schemas "
            + "every other domain is told apart inside by a prefix, because a schema is "
            + "the unit the database moves. Everything that names its tables finds them "
            + "there: the store, its history, its feed, the retention sweep and the "
            + "backup, whose sweep discovers a domain by looking for its tables and "
            + "would otherwise carry every domain except the one made portable, "
            + "silently."),
    CORE_DECLARED_IDENTITY(
            "Every type in every personality declares exactly one primary identity "
            + "class — canonical url, designated identifiers, or internal — and the "
            + "contract fails closed at registration without it."),
    CORE_IDENTITY_SURVIVES_CONVERSION(
            "Conversion between FHIR versions or object shapes never changes identity; "
            + "canonical urls and identity-bearing identifiers are preserved bit-exact "
            + "and verified after every conversion."),
    CORE_NO_IMPLICIT_MERGE(
            "Two PEOPLE claiming the same identity-bearing identifier are a conflict "
            + "surfaced to the owner, never an implicit merge. One human is spoken about "
            + "by several records — a Person and a Patient sharing a national number are "
            + "that human twice, not two of them — so what is refused is a second record "
            + "of a type the tenant declared identified by that system, where the value IS "
            + "the record's identity, and a link that would join two people each holding "
            + "identity claims of their own."),
    CORE_IDENTITY_KEYED_CONDITIONALS(
            "Conditional writes are accepted only when keyed on the type's primary "
            + "identity; a conditional write on any other criterion is rejected."),
    CORE_CONDITIONAL_REFERENCES(
            "A reference may be a question — `Type?identifier=system\\|value` — and it "
            + "is answered when the document is written: exactly one match becomes the "
            + "concrete reference, none or several refuse the write naming the question. "
            + "Inside a transaction, the entries' own claimed identities answer before "
            + "the store: a reference to an identity exactly one entry claims resolves to "
            + "that entry, wherever it sits in the document — a hierarchy authored as one "
            + "document lands whole. The question may ask only by the identity its type "
            + "is claimed under, so what a write means does not depend on what else "
            + "happens to match today, and no unanswered question — one neither the "
            + "document nor the store answers — is ever stored."),
    CORE_CONDITIONAL_UPSERT(
            "A write may be addressed by identity rather than by id: `PUT "
            + "[type]?identifier=…` or `?url=…` creates the resource when absent and "
            + "replaces it when present, standalone and inside a bundle. Configuration "
            + "that must match a source can therefore be expressed as itself, rather than "
            + "as a create that silently does nothing when the record already exists."),
    CORE_ATOMIC_TRANSACTION_BUNDLE(
            "A transaction bundle lands whole or not at all: every entry validated "
            + "before anything is written, all writes in one engine transaction with "
            + "data, history and outbox together, and entries may reference each other by "
            + "`urn:uuid` — resolved to the allocated ids, never stored dangling. What a "
            + "transaction does not serve is refused by name with nothing applied."),
    CORE_BATCH_ANSWERS_PER_ENTRY(
            "A batch bundle applies each entry independently through the same path the "
            + "standalone request takes, and answers one response entry per request "
            + "entry, in order, each with its own status — a failing entry says nothing "
            + "about its neighbours, and the statuses are the ones the standalone "
            + "requests would have answered."),

    // ── CONT — migrated from hand-written prose (2026-08-27) ──

    CONT_FRAMEWORK_FREE_CORE(
            "The core is plain Java; no Spring/Micronaut-class framework dependency "
            + "anywhere in the engine. (R1, R2)"),
    CONT_DYNAMIC_TENANT_SERVICES(
            "Tenants arrive, move and leave as OSGi service-registry dynamics — never a "
            + "process restart. (R2, §4)"),
    CONT_EMBEDDED_IN_JVM(
            "A host application can boot the full store inside its own JVM for "
            + "dev/test; the only shared dependencies are Felix and the OSGi API. (R2)"),
    CONT_A_PROCESS_KEEPS_ITS_CONTAINER_CACHE_TO_ITSELF(
            "Each process keeps its container's bundle cache in a directory of its own, "
            + "removed when the process ends, so two applications on one host, or two test "
            + "runs at once, never install into each other's cache."),
    CONT_A_HOST_MAY_OWN_THE_CONTAINER(
            "An application that owns an OSGi framework — because bundles of its own belong "
            + "in the store's class space — creates it with the launch properties the store "
            + "names, and the store installs into it instead of creating a second one. A "
            + "framework missing any of them is refused at start, naming each; a bundle "
            + "already there that would take a shared package from the store's bundles, or "
            + "that does not resolve, is refused by name. Closing removes the store's bundles "
            + "and what it registered, and never stops a framework the store did not "
            + "create."),
    CONT_A_HOST_ASKS_FOR_THE_STORE(
            "An application carrying the Spring assemblies gets a store, or a worker, only "
            + "where it asks: by annotating itself, or by declaring the properties as a bean "
            + "of its own, which its environment then overrides key by key as it would any "
            + "configuration properties. A context that did neither, a test slice or a tool "
            + "sharing the classpath, starts with no container, no tenant registry and no "
            + "filter in its chain, and needs no dbo property set. One that asked for a store "
            + "and gave it no key is refused, naming the key."),
    CONT_IMPORTS_ARE_COMPUTED_OR_CHECKED(
            "Every bundle with source of its own computes its imports from its bytecode; "
            + "what is written by hand is policy — which JDK surfaces may be absent, and "
            + "what a private stack reaches for that the container does not provide — "
            + "never an inventory a new reference can drift from. The one bundle without "
            + "source, the shared HL7 stack, keeps a closed hand-written list and is "
            + "checked for it: every class it embeds is walked, and a framework-wired "
            + "package it reaches for and neither carries nor imports fails the build "
            + "rather than the first use."),
    CONT_PRIVATE_DEPENDENCIES(
            "Heavy third-party stacks (DBOS, HAPI) are embedded as private packages and "
            + "served through DBO-owned whiteboard interfaces; their types never cross "
            + "bundle boundaries."),
    CONT_FAST_COLD_START(
            "Store startup against an already-current schema is fast enough for "
            + "embedded test use; schema setup detects currency instead of replaying "
            + "changelogs."),

    // ── TEN — migrated from hand-written prose (2026-08-27) ──

    /** TODO: prove it in a test. */
    TEN_SERVED_FROM_WHAT_WAS_APPLIED("A deployment serves the declarations that were "
            + "applied, not a listing it takes itself — so a tenant stops being served "
            + "because somebody withdrew it, never because a read went wrong. A source "
            + "that cannot be read leaves the records standing, the tenants serving, and "
            + "says in the ledger that it has stopped moving. A deployment with no "
            + "managing tenant to hold records reads its source directly, because nothing "
            + "can bootstrap out of a store it has not built yet."),

    TEN_A_REFUSED_DECLARATION_IS_SAID_ONCE("A declaration this deployment refused is "
            + "reported by name with its reason, and a deployment that is not serving "
            + "something it was told to serve does not answer as though it were. The refusal "
            + "was already a card in front of a person, which is where it belongs; what it "
            + "was not is visible, because a spec that will not parse never reaches bring-up "
            + "and none of the reporting there fires — six tenants of seven reads exactly "
            + "like six. Said once per declaration and reason, since the pass runs on every "
            + "beat and a refusal repeated every few seconds is how a log stops being read; "
            + "said again when the reason changes, because somebody fixing a file works "
            + "through its problems one at a time."),

    TEN_A_STALE_INDEX_IS_REMEMBERED_UNTIL_IT_IS_REBUILT("A reindex that did not finish is "
            + "remembered against the tenant and retried until it does. The feed's events are "
            + "acknowledged before the rebuild runs — deliberately, so a broken profile is "
            + "not re-read forever — which left a failed reindex with nothing to bring it "
            + "back: the index stayed stale behind one warning, and a stale envelope does not "
            + "slow a search down, it makes it miss, which reads as nobody here. The warning "
            + "is said once rather than every round, because a log that repeats itself stops "
            + "being read, and the recovery says so when it comes."),

    SRCH_A_REINDEX_HOLDS_NO_TRANSACTION_WHILE_IT_EXTRACTS("A reindex reads a batch in one "
            + "transaction, extracts with none open, and writes in another — so how long "
            + "extraction takes cannot decide whether the reindex survives. Extracting "
            + "between two statements of an open transaction left the connection idle in "
            + "transaction, and this store sets a sixty-second guard on every tenant "
            + "database, so on a loaded node its own guard terminated its own reindex. The "
            + "write is conditional on the version the row was read at, wherever the "
            + "extraction happened — in this process, or in the database's own walk of a type "
            + "whose extractor lives there: a row rewritten meanwhile already carries an "
            + "envelope from the write that changed it, replacing it would restore the "
            + "staleness the rebuild exists to remove, and a row left alone is not counted "
            + "as rebuilt."),

    PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL("A search refused for want of a stated purpose "
            + "answers as a refusal the caller can act on, never as a fault: the request was "
            + "well formed and this store is not broken, it declined. Answering 500 told a "
            + "caller to retry and report it, under a message written to tell them to state "
            + "a purpose instead — so the one refusal the design argues hardest for was the "
            + "one a caller was least able to read."),

    PDI_AN_ID_THE_STORE_NEVER_ASSIGNED_IS_NOT_A_FAULT("A vault lookup for a record id "
            + "this store never assigned answers that it holds nothing, decided before the "
            + "database is asked. A caller's malformed id is the caller's mistake, and "
            + "handing it to the database to cast made it the server's: a tenant with a "
            + "vault answered 500 where the same request answered 400 without one, so "
            + "turning the membrane on turned a refusal into a fault — and the membrane is "
            + "supposed to be invisible to everything except what it protects."),

    TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES("A tenant publishes what it is as facts, "
            + "and an activity states which tenants it is for rather than working it out "
            + "where it runs: it declares a filter over those facts, or it applies to "
            + "every tenant on purpose. An activity whose filter a tenant does not match "
            + "is not performed for it — so provisioning that suits one kind of tenant "
            + "cannot be applied to another by omission, which is the shape the failure "
            + "took when subscription dispatching polled a record domain that a face root "
            + "does not have. A filter that cannot be parsed is refused where it is "
            + "registered, because one consulted later would match nothing in silence."),

    TEN_A_FEED_SAYS_WHAT_CHANGED_NOT_WHAT_IT_SAYS("An observer of a tenant's records or "
            + "of its trail is told that something changed — the type, the identity, the "
            + "version, when, and whether it was a deletion — and never the content. A "
            + "consumer that needs the record performs a step and reads it in the run "
            + "context, where authorisation, purpose and accountability arrive together. "
            + "Running inside the container is not an exemption: that is a fact about "
            + "trust, and a feed confers none of the three, because there is no run to "
            + "name in the trail and no reason attached to what was seen. Nor can a feed "
            + "declare its way across, since a declaration of types with no anchor is "
            + "type-level access control wearing a step's clothing and a feed has no "
            + "anchor to give. Work and identity carry their content, each because one of "
            + "the enumerated reasons already admits it: they are the machinery's own "
            + "bookkeeping, and a task describing the delivery of a task does not "
            + "terminate."),

    TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS("Configuration a declarer holds — value "
            + "sets, profiles, search parameters, whatever a loader keeps — is handed over "
            + "and applied to a tenant as one recorded pass rather than posted a resource "
            + "at a time: read, applied, and a card per declaration nobody could apply, "
            + "naming it as the declarer names it. The declarer's own name for the set is "
            + "echoed and never parsed, and nothing reaches back afterwards — whoever "
            + "declared it re-evaluates against what the pass says, so the two sides never "
            + "have to be up together."),

    TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE("What a tenant streams from another tenant "
            + "can be added to and taken away while it serves. A dependency declared today "
            + "catches up from the upstream's whole history; one no longer declared stops "
            + "delivering, and the copies it already brought stay, because they are what "
            + "this tenant answers from. A dependency on a tenant that is not up yet is a "
            + "wait rather than a teardown: nothing that was serving stops serving because "
            + "somebody named an upstream before it arrived."),

    TEN_COMING_UP_AND_KEEPING_UP_ARE_NOT_ONE_QUEUE("Bringing tenants up and keeping their "
            + "streams in step do not wait on each other. A tenant catching up with a large "
            + "dependency does not delay another tenant coming up, and a bring-up waiting on "
            + "storage somebody else provisions does not stop the deployment's streams — "
            + "neither of which announced itself when they shared a thread, because a wait "
            + "that is nobody's failure is reported by nobody. Streams run several at a "
            + "time, each drained before it gives way."),

    TEN_A_DOOR_OPENS_WHEN_ITS_TENANT_SERVES("A tenant's doors answer once it is serving "
            + "and not before. While it comes up each one says 503 with Retry-After — never a "
            + "200 that lets a client start writing into a tenant whose bring-up can still "
            + "fail, and never a 404 for a tenant that answered a moment ago. A tenant waiting "
            + "for an upstream it is brought up from — a dependency, the projection a zone is "
            + "read through, the zone it federates through — builds nothing until that "
            + "upstream serves, and then comes up, rather than going most of the way up and "
            + "back down on every scan."),

    TEN_SERVING_IS_NOT_TAKEN_BACK_BY_A_LATER_STEP("A tenant once serving is not taken back "
            + "down by a step that came after it — telling the host, offering its work to the "
            + "fleet. Such a step that fails leaves the tenant serving, reported degraded with "
            + "the reason beside it, its doors and storage the ones it came up with; the step "
            + "is tried again on every scan, and the tenant reads serving once it takes."),

    TEN_APPLYING_IS_ASKED_FOR_AND_RECORDED("Applying what is declared can be asked for, "
            + "and the ask is the whole of the interface: it opens the same pass the "
            + "deployment runs on its own and answers with what that pass did, so there is "
            + "no second entry point that applies without leaving a record. It is reached "
            + "behind a scope of its own, granted separately and usually not granted at "
            + "all, because changing what a tenant is, is not the same right as writing "
            + "records into it."),

    TEN_A_CHANGE_IS_NOT_A_RETRACTION("A change a serving tenant can take is applied to "
            + "it: what it only says about itself it takes where it stands, and what it is "
            + "made of is rebuilt in place — its database, its lanes and their cursors kept, "
            + "nothing recorded as withdrawn, and whoever streams from it wired again rather "
            + "than left reading a pool that has closed. A declaration naming a face nothing "
            + "serves is refused while the tenant is still running, because a change that "
            + "cannot work should cost nothing."),

    TEN_A_REDECLARATION_IS_NOTICED("A serving tenant declared differently from what it "
            + "was built from is noticed and classified, rather than read once at mount and "
            + "never again: what can be absorbed while it serves, what has to be rebuilt in "
            + "place, and what cannot be had at all while it serves — the last refused by "
            + "name and never half-applied. Every field of a declaration is classified, so "
            + "a change nobody thought about cannot pass as no change, and a deployment can "
            + "be asked which of its tenants are serving something other than what somebody "
            + "declared."),

    TEN_DECLARED_TOGETHER_COME_UP_TOGETHER("A consumer that declares several tenants at "
            + "once gets several tenants. A node brings them up together, bounded by what "
            + "it can carry at a time, so the wait is the slowest tenant's rather than the "
            + "sum of all of them — and a tenant that cannot come up yet is its own "
            + "trouble rather than a queue everybody behind it is stuck in, which is what "
            + "made a busy deployment indistinguishable from a broken one."),

    TEN_A_SLOW_BRING_UP_HOLDS_UP_ONLY_ITSELF("A tenant slow to come up — storage that "
            + "is late, a schema another node is still writing — holds up nobody else. A "
            + "tenant withdrawn meanwhile stops being served on the deployment's next beat, "
            + "a tenant declared meanwhile is begun as soon as the node has room for it, "
            + "rather than once every bring-up in front of it is done, and a serving tenant "
            + "declared differently is rebuilt on that beat without waiting for room at all."),

    TEN_A_DECLARATION_IS_A_RECORD("What a deployment has been told to serve is records "
            + "in the managing tenant, applied from whatever source declares them like any "
            + "other configuration — so what is declared can be asked of the store rather "
            + "than read off a node's disk, and a declaration that will not parse is a card "
            + "naming the file rather than a line in a boot log. A deployment with no "
            + "managing tenant records nothing and serves exactly as before: recording what "
            + "is declared is not a condition of honouring it."),

    TEN_READY_WHEN_ITS_CRITICAL_DEFINITIONS_ARRIVED(
            "A tenant on a face is served only once the version's structures, search "
            + "parameters, value sets and code systems have arrived from it — the four a "
            + "version is made of — and a face root declares all four or is refused. A "
            + "chain that carried structures without the code systems their bindings name "
            + "would serve a tenant that accepts any code at all."),
    TEN_STRUCTURAL_SCOPING(
            "No code path can read or write data without an explicit tenant context. "
            + "(R3)"),
    TEN_DEDICATED_DATABASE_TIER(
            "A tenant can run on a dedicated database; this tier is the design anchor. "
            + "(R5)"),
    TEN_CREDENTIAL_BLIND_PROVISIONING(
            "Tenant databases and buckets are provisioned by an external operator; "
            + "credentials exist only as platform secrets and are never readable by "
            + "tenant-manager code. (R5, §4)"),
    TEN_A_PARTNER_MANAGES_TENANTS(
            "A partner is a tenant that manages other tenants, declared when the managed "
            + "tenant is created. The relation says which tenants the partner may read at "
            + "all; within each, the partner is a declared audience saying what of each — "
            + "runs and their journey, never documents, purposes only if the managed tenant "
            + "opts in. What the partner is shown is assembled outside the store: a store "
            + "instance is one tenant's store, and no cross-tenant query is grown to serve "
            + "a support desk."),
    TEN_REGISTRY_SCOPED_ACCESS(
            "Application code obtains a tenant's data services from the service "
            + "registry and can use them without ever seeing credentials. (R5, §4)"),
    TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN(
            "Erasing a tenant is asked at a door of its own, behind a credential the deployment "
            + "gives for erasure and for nothing else: the operator's own token reads the node "
            + "and erases nothing. Every erasure states its reason, which is recorded beside who "
            + "asked; a tenant still declared, and the management tenant, are refused; and "
            + "asking again for an erasure that already happened answers the same."),
    TEN_ERASURE_BY_DROP(
            "Dropping a tenant's database and blob storage removes all its data — "
            + "including durable workflow history and feed state."),
    /** TODO: prove it in a test. */
    TEN_SHARED_TIER_ISOLATION(
            "Planned — Tenants on the shared tier are isolated by tenant-keyed schemas and "
            + "row-level security with the same API surface as the dedicated tier."),
    /** TODO: prove it in a test. */
    /**
     * The classification ran inside the pass that applied what it classified,
     * so the only way to learn a change was a rebuild was to cause one — and
     * the only way to learn it was refused was to read the refusal afterwards
     * as the record of an attempt. Worst exactly where somebody most needed
     * it before committing.
     *
     * <p>A named path rather than a parameter, because a parameter that
     * switches applying off is one whose typo applies, and the caller who
     * misspells it is the one who was trying not to cause a change.
     */
    /**
     * The authored path always answered a reference that is a question; the
     * configuration door never did, because a declaration is written through
     * the engine with the face's grain applied rather than through the face's
     * accept path. So the query was stored verbatim — a reference that reads
     * as a promise and resolves to nothing, which is worse than the logical
     * reference a consumer writes instead.
     *
     * <p>The set answers for itself by being written: applied to a fixed
     * point rather than in the order it arrived, so the order somebody
     * composed it in is not a contract discovered by whoever gets it wrong.
     */
    TEN_A_DECLARATION_NAMES_ITS_REFERENT("A declared record may name its referent by a "
            + "conditional reference, including one another declaration in the same set "
            + "creates: the set is applied to a fixed point rather than in the order it "
            + "arrived, what is stored names the referent by id, and a reference nothing can "
            + "answer fails that declaration by name rather than landing as a question."),

    TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING("What a declaration would do to the "
            + "tenants it names is answerable without doing it — hot, rebuilt in place, or "
            + "refused with what it would need — on the door that applies it and under the "
            + "same grant; nothing is applied and nothing is recorded, because a preview "
            + "does not happen to the tenant."),

    TEN_FAIRNESS_QUOTAS(
            "Planned — Per-tenant quotas and rate limits are first-class configuration, enforced "
            + "at the serving pod."),

    // ── AUTH — migrated from hand-written prose (2026-08-27) ──

    AUTH_TENANT_SCOPED_ISSUER(
            "Every tenant is its own OIDC authority with its own issuer URL, discovery "
            + "document, key set and token endpoint; relying parties trust exactly one "
            + "tenant's authority, never the store's. A token from any other tenant fails "
            + "signature verification before any claim is read."),
    AUTH_IDENTITY_AS_RECORDS(
            "Client applications, grants and signing keys are regular records in the "
            + "tenant's own store — versioned, provenance-stamped, visible to feeds, and "
            + "carried by the maintenance export: restoring a tenant restores who may "
            + "access it."),
    /** TODO: prove it in a test. */
    AUTH_PRIVATE_SURFACE(
            "Planned — The raw store surface is never publicly routed; public interaction with "
            + "dbo-held data goes through process-based surfaces. The authority exists so "
            + "authorized services reach the private surface with tenant-rooted trust."),
    AUTH_DENY_BY_DEFAULT(
            "A serving deployment without a working authority refuses to serve tenant "
            + "endpoints; disabling auth is an explicit embedded/test flag, never a "
            + "default."),
    AUTH_BEARER_LOCAL_VALIDATION(
            "The serving surface accepts OAuth2 bearer JWTs validated locally against "
            + "the tenant's own cached key set — no per-request dependency on any other "
            + "service."),
    AUTH_CREDENTIAL_FACTORS_BY_KIND(
            "A local credential holds factors named by kind (RFC 8176 `amr`): a "
            + "PIN, a password and a passkey are different kinds, setting one leaves the "
            + "others alone, and a kind is never a field named after the first case."),
    AUTH_PASSWORD_ONLY_WHERE_WE_ARE_THE_IDP(
            "A password is held only where the tenant is the identity provider for that "
            + "subject. Signing in through the hub records that the hub identifies this "
            + "person, which is the signal the rule reads — federated login used to "
            + "resolve somebody and leave no trace it had. From then on a password is "
            + "refused at every door that could set one, naming where they sign in "
            + "instead rather than answering no, and a password they already held is "
            + "retired with a stamp saying when and why: one surviving federation would "
            + "be a second way in that never reaches the identity provider. A PIN "
            + "is untouched, before and after — it serves the case federation cannot, "
            + "and taking it away would remove the fallback for the situation the rule "
            + "was written around."),
    AUTH_THE_SIGN_IN_PAGE_IS_THE_HOSTS(
            "A tenant's people sign in on the page the application draws for that tenant, "
            + "and on the store's own where it draws none. The store hands the page what it "
            + "posts and where each button goes, already built and escaped, so a page "
            + "decides how sign-in looks and never what it sends or to whom. A page that "
            + "arrives or goes while the tenant serves is drawn, or stops being drawn, at "
            + "the next sign-in."),
    AUTH_THE_PAGE_OFFERS_THE_BROKERS_A_TENANT_ACCEPTS(
            "A federated tenant's sign-in page offers exactly the brokers the tenant accepts, "
            + "or its contracted one where it accepts no list, and never every broker its "
            + "hub knows: a ceremony is billed to whoever contracted the broker. Each button "
            + "carries an opaque reference to a request the store holds, and a broker the "
            + "tenant does not accept is refused. Where there is only one broker and nobody "
            + "at the tenant holds a password, there is nothing to choose and no page."),
    AUTH_A_WRONG_PASSWORD_STAYS_ON_THE_PAGE(
            "A login and password that match nobody show the same sign-in page again with "
            + "the error, so the person corrects it where they made it. Somebody the tenant "
            + "recognises and grants nothing is still answered to the application, because "
            + "typing again would not change that answer."),
    AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT(
            "An application embedding the store accepts a bearer token on its own API "
            + "exactly where the addressed tenant's doors would: checked in-process by that "
            + "tenant's authority, with no call to the application itself and no list of "
            + "issuers to keep current. A tenant that comes up while the application runs "
            + "is accepted on its next request and one that is retracted is refused on its "
            + "next; a partner's token is accepted where the managed tenant declared the "
            + "relation; and an unknown tenant, a retracted one, another tenant's keys and "
            + "an expired token are refused alike."),
    AUTH_THE_ID_TOKEN_SAYS_WHO_SIGNED_IN_AND_WHAT_THEY_HOLD(
            "Every sign-in and every refresh mints an ID token that says who the person is "
            + "and which roles they hold at which organisation, each role against the "
            + "organisation it is held at rather than merged with roles held elsewhere. When "
            + "and how they signed in is carried unchanged through every refresh, and what "
            + "they hold is re-derived at each. The access token carries none of it: it "
            + "reaches the store's surfaces and every service it is exchanged for, which are "
            + "third parties to the person."),
    AUTH_USERINFO_ANSWERS_WHAT_THE_ID_TOKEN_SAYS(
            "UserInfo answers the claims the ID token minted with the same access token "
            + "carries, over HTTP and in-process, as JSON or as a JWT signed by the tenant's "
            + "key, and is listed in discovery. An ID token or a signed answer can be "
            + "verified in-process against the tenant's own keys."),
    AUTH_A_PERSON_READING_THEIR_OWN_IDENTITY_IS_RECORDED_AS_THEIRS(
            "On a tenant that vaults identity, the authority reads who a person is for their "
            + "own claims as the person and for self-access, and the trail records it as "
            + "theirs: once, at sign-in. A refresh or a UserInfo call reuses what sign-in "
            + "read and reads nobody's identity again."),
    AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES(
            "An application adds its own claims to a person's ID token and UserInfo from "
            + "what the authority already loaded, told which tenant and which client it is "
            + "answering, so what it adds for one application need reach no other. It "
            + "cannot say what only the authority says, a claim set over the bound is "
            + "refused at minting rather than cut short downstream, and a contributor that "
            + "fails stops the minting rather than letting a token out without its claims."),
    AUTH_SELF_SERVICE_CHANGE(
            "A signed-in subject can replace their own password by proving possession "
            + "of the current one. No ticket, no second channel, and no other factor is "
            + "touched."),
    AUTH_RECOVERY_IS_AN_OPERATOR_ACT(
            "A subject who cannot sign in is recovered by provisioning or an operator "
            + "write, never by a self-service ceremony: recovery needs a channel the "
            + "authority does not have, and acquiring one would put delivery inside the "
            + "trust root."),
    AUTH_DEACTIVATION_RETIRES_CREDENTIALS(
            "Deactivating a subject retires its credentials — every factor, at once, "
            + "and never by deletion: history and audit need the record, and a login that "
            + "vanishes cannot be told from one that never existed."),
    AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT(
            "A subject sets their own first secret by redeeming a one-time, short-lived "
            + "grant the authority mints and never delivers: the consumer owns the "
            + "address and the mail, so no delivery enters the trust root. Minting "
            + "resolves nothing, redemption burns the grant on presentation rather than "
            + "on success, and a grant authenticates nothing and cannot be exchanged for "
            + "a token."),
    AUTH_A_ZONE_IS_ITS_OWN_BROKER(
            "A zone that names no identity broker is its own: every tenant carries an "
            + "authority and a zone is a tenant, so members federate to the ceremony it "
            + "already runs. Declaring a zone for its rules does not oblige a deployment "
            + "to stand up an external identity provider first."),
    AUTH_BOOTSTRAP_SECRET_IS_CUSTODY(
            "A tenant's bootstrap credential is the secret its deployment already holds, "
            + "named per tenant, and never one the store invented and kept to itself: "
            + "custody is the operator's, so a deployment without one says what it "
            + "decided rather than being locked out of its own authority."),
    AUTH_NO_SUBJECT_ENUMERATION(
            "No authority answer distinguishes a subject that exists from one that does "
            + "not — not in what it says, not in how long it takes. The authority is the "
            + "only party that knows, which is why it must not say."),
    AUTH_SMART_SHAPED_SCOPES(
            "Authorization vocabulary is the SMART system-scope grammar, so finer "
            + "service permissions and the future read-only public capability need no new "
            + "language."),
    /** TODO: prove it in a test. */
    AUTH_PORTABLE_AUTHORITY(
            "The issuer string is per-tenant configuration and the key material lives "
            + "in the tenant database — a tenant can move deployments or present a custom "
            + "domain without re-keying."),
    AUTH_ORG_MODEL_IS_THE_AUTH_MODEL(
            "Human authorization derives from the tenant's own records — Practitioner "
            + "is the subject, an active PractitionerRole is the grant, the Organization "
            + "tree is the scope structure; there is no parallel user database to drift."),
    AUTH_FEDERATED_HUMANS(
            "Human authentication is federated to the configured identity broker; the "
            + "authority resolves the verified national identifier to a Practitioner "
            + "through the vault index and owns authorization only. Local credentials are "
            + "an embedded/dev fallback, never the production path."),
    /** TODO: prove it in a test. */
    AUTH_ROLE_GRANTS_AS_RECORDS(
            "The role-to-scope mapping is tenant-administered regular records — "
            + "auditable, feed-visible, exported; changing who may do what is a recorded "
            + "act."),
    /**
     * Converging is a read and then a write, and for a while only the write
     * existed: a grant could be widened, and a role dropped from
     * configuration was never posted and so was never touched. The verb to
     * withdraw one could not close that alone — a client cannot withdraw what
     * it has no way to learn about.
     *
     * <p>What the answer carries is decided by what a reconcile compares. The
     * organisation, because a grant at one and a tenant-wide grant are
     * different grants. The scopes, because a role that still exists with
     * more than configuration now gives it is the drift a list of role codes
     * could never show.
     */
    AUTH_GRANTS_ARE_READABLE_TO_CONVERGE("What a tenant grants is readable on the same "
            + "provisioning plane that writes it, each grant with the organisation it was "
            + "granted at and the scopes as granted; withdrawn grants are left out unless "
            + "asked for, so one is never mistaken for a role that is still present."),

    AUTH_PSEUDONYMOUS_TOKENS(
            "Human tokens carry the practitioner's record id and SMART user scopes — no "
            + "name, no national code; a captured token identifies no one."),
    AUTH_ONE_CEREMONY_MANY_TENANTS(
            "One national authentication serves every tenant authority in the "
            + "deployment through the identity hub's session — the upstream broker is "
            + "invoked once per session, not per tenant; authorization remains strictly "
            + "per-tenant."),
    AUTH_ON_BEHALF_OF(
            "Automated processes act in the name of a human via token exchange — "
            + "subject stays the practitioner, an act claim names the client, scopes "
            + "attenuate; durable workflows delegate through Delegation records that "
            + "outlive tokens and are revocable by ending their period. Every delegated "
            + "mutation is attributable to both the process and the person."),
    AUTH_PURPOSE_IS_STATED_PER_REQUEST(
            "Why an identifying read is happening is stated on the request that makes it "
            + "— a PurposeOfUse code in the Purpose-Of-Use header — and the request's word "
            + "replaces any the token carries. Every grant that mints a token may also "
            + "state one, so a person acting through a delegation states a purpose exactly "
            + "as a service does; a delegation record carries none, because a standing "
            + "grant naming a reason would keep asserting it after the reason lapsed. A "
            + "purpose is an assertion and never an authorisation: it widens nothing, it "
            + "selects the disclosing mode for the one request it rides rather than for a "
            + "credential's lifetime, and it is what the trail records. A purpose that is "
            + "not a code is refused rather than dropped, at the token endpoint and at the "
            + "door alike."),

    // ── POL — migrated from hand-written prose (2026-08-27) ──

    POL_DECLARED_AT_CONFIGURATION(
            "Audit level and write discipline are declared in the tenant's "
            + "configuration next to its FHIR version, validated at registration, and "
            + "visible in the capability statement."),
    POL_AUDIT_AS_RECORDS(
            "Audit entries are regular, pseudonymous records in the tenant's own store "
            + "— feed-visible, exported and restored with the tenant, re-identifiable "
            + "only through the vault."),
    POL_ACTOR_FROM_AUTHORITY(
            "Every audit entry names its actor from the tenant authority's token "
            + "(client and subject) — no anonymous mutations under any audited policy."),
    POL_APPEND_ONLY_DISCIPLINE(
            "Under append-only discipline the engine rejects tombstones (and per-type "
            + "in-place updates where declared); correction is supersession or "
            + "entered-in-error, never removal."),
    POL_ERASURE_COMPATIBLE(
            "Append-only discipline and the right to erasure coexist: shredding never "
            + "rewrites a record — the record remains, the person evaporates."),
    POL_DECLARATIVE_RETENTION(
            "Retention is declared per tenant and type as a floor and a ceiling — "
            + "keepAtLeast (append-only holds even against policy) and removeAfter (the "
            + "engine must remove) — composing with write discipline without conflict."),
    POL_RETENTION_SWEEP(
            "A durable scheduled sweep executes removal as the one sanctioned mutation "
            + "of history, and every removal is audited without retaining the removed "
            + "data."),
    POL_POLICY_REPLAY_ON_RESTORE(
            "Before a restored tenant serves, the machinery re-applies the shred ledger "
            + "and the retention sweep — an archive cannot resurrect what policy required "
            + "gone; archives carry removeAfter themselves."),
    POL_CUSTOM_AUDIT_EVENTS(
            "Applications contribute business-level audit events; the machinery stamps "
            + "actor and time from the validated token and its own clock, overriding "
            + "caller claims — the trail can be enriched, never impersonated or "
            + "backdated."),
    POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES(
            "One trail; the target says what an entry is about. A hop that carried work "
            + "leaves a travel entry about the task. A participant that opened a payload "
            + "leaves an access entry about the document, landing where every other "
            + "reading of it lands and naming the task execution as its occasion. The "
            + "machinery's own read to seal a payload records nothing: a read that yields "
            + "only ciphertext is not a disclosure. So who read this is answered from the "
            + "document by somebody who need not know work exists, and where did this go "
            + "from the task, and the trail can say that nobody looked."),
    POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK(
            "A run's travel and access entries each carry a link to the one before, "
            + "rooted in the task the store minted, so a participant cannot present a "
            + "journey that never started. The result that closes the run is the last "
            + "link and carries the head it commits to; the store checks the chain when "
            + "the result lands, and a completion with a gap is refused and told which "
            + "link. A travel entry names who it handed to, so a skipped hop is exposed by "
            + "the next author. Links are signed by the participant, which buys "
            + "non-forgery and non-repudiation and not omission-proofing: an intended "
            + "recipient can open a payload and never say so, and that limit is accepted. "
            + "The link lives on the entry, so the chain outlives nothing the trail does "
            + "not, and a pruned predecessor reads unchained rather than broken."),
    POL_COLLECTING_IS_A_READING(
            "Each collection an asker makes through its run's window is an access entry "
            + "about the document, landing beside every other reading of it, naming the "
            + "asker's client as actor, the run as occasion and the step's purpose — never "
            + "travel, and whatever the tenant's audit level. A version may be collected any "
            + "number of times within the window, each a reading of its own. Reading the "
            + "run's answer stays no entry, because it carries references only."),

    POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY(
            "Audit entries are exempt from the tenant's write discipline: no update, no "
            + "tombstone under any policy; retention's sweep is the only removal."),
    POL_FHIR_AUDIT_PROJECTION(
            "On a FHIR tenant the audit stream is served as AuditEvent — native records "
            + "as the truth form, rendered per personality on read, contribution via "
            + "mapped POST; write access is scope-gated."),

    // ── ZONE — migrated from hand-written prose (2026-08-27) ──

    /** TODO: prove it in a test. */
    ZONE_DECLARATIONS_AS_RECORDS(
            "A zone is a tenant whose declarations — identity brokers, identifier "
            + "domains — are regular records: versioned, audited, exported, and "
            + "streamable down the same chains as any content. Secrets are never in a "
            + "record."),
    ZONE_BROKER_CHOICE(
            "The broker set is jurisdictional, the choice organizational: the zone "
            + "declares the available national brokers; a tenant selects its contracted "
            + "one and may restrict what it accepts."),
    ZONE_SESSIONS_ACCUMULATE(
            "The per-zone hub's session records which broker performed each ceremony "
            + "and accumulates ceremonies; cross-broker reuse is the default, tenant "
            + "acceptance policy the restriction — the strictest tenant is satisfied "
            + "without invalidating anyone else's session."),
    ZONE_SUBJECT_DOMAINS(
            "Subject-resolution identifier systems come from the zone's declared "
            + "domains — the official national terminology — never from dbo code."),

    // ── VER — migrated from hand-written prose (2026-08-27) ──

    VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS(
            "A face root is a tenant that holds its version's definitions as records — "
            + "structures, search parameters, value sets, code systems, maps — loaded once "
            + "from the carried packages, the only place those packages are read. A "
            + "definition is held once however many times the root boots, the packages are "
            + "refused if a definition is built on a base they do not carry, and what the "
            + "root holds is findable by canonical url like any other record. It exists so "
            + "that a version can be subscribed to like a zone rather than loaded into a "
            + "node."),
    VER_AN_EXPRESSION_THAT_YIELDS_A_VALUE_IS_COMPILED(
            "The compiler expresses a path that yields a value or a collection — a search "
            + "expression, a rule's operand, a map's source — not only one that answers true "
            + "or false. A choice narrowed by type is the key that type spells; a reference "
            + "tested by type is the type its own spelling names; a branch about another "
            + "type is passed over; and what cannot be selected is refused naming the part "
            + "that stopped it. Measured by compiling every search expression the carried "
            + "versions publish, per parameter and base, rather than by classifying them."),
    VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES(
            "A structure arriving at a tenant by any path — a feed, the face, a restore — "
            + "is expanded once into element rows the database checks against: what may "
            + "stand at each element, how often, what it must equal or contain and what it "
            + "is bound to, located by a jsonpath with the choice keys and slice members "
            + "already resolved. Bringing a tenant up reads those rows; nothing expands a "
            + "definition per boot or per write, and the rows are rebuilt from the record "
            + "by a reindex like any other projection."),
    VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME(
            "An element the database cannot locate — a slice told apart by following a "
            + "reference, a count that is no number — is refused when the definition "
            + "arrives, naming the definition and the element. A definition is never held "
            + "with an element nothing can check, because a checker holding no row for an "
            + "element enforces nothing about it and says so to nobody."),
    VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES(
            "Every rule an element carries is compiled once, when its definition arrives, into "
            + "a path the database runs, and held as a row with its key, its severity and the "
            + "expression it came from. It is read by the toolchain's own parser, because the "
            + "claim being made is that the two agree and compiling from the tree the toolchain "
            + "interprets is the only version of that claim anybody can check."),
    VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE(
            "A broken rule is reported by its own key, against the element instance it is about, "
            + "from the path compiled when the definition arrived — nothing is parsed or "
            + "interpreted at a write. Severity is the rule's own: a warning is advice and "
            + "refuses nothing, as a binding weaker than required already is. A rule that cannot "
            + "be run against a particular document is reported by nobody rather than as broken, "
            + "because a document is not wrong for being a shape a rule could not be run "
            + "against."),
    VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME(
            "A rule this store cannot express is held saying so — naming the definition, the "
            + "element, the rule's own key and the part that stopped it — and never silently "
            + "dropped, because a rule nobody holds is a rule nobody checks and nobody knows "
            + "nobody checks. It never refuses the definition that carries it: the published "
            + "versions contain such rules, and refusing them would refuse the specification."),
    VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION(
            "Everything a version publishes is put to both checkers, and what they disagree "
            + "about is recorded per resource type as a baseline that may fall and may not "
            + "rise. The corpus is the specification's own conformance resources — deep, "
            + "sliced, bound and referenced documents of real types — because the instance "
            + "examples ship in a package a store has no use for. The whole case for the "
            + "database answering at all is that it answers the same, so the measurement is "
            + "kept where a change to either side has to face it."),
    VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT(
            "On a write the database is asked what it makes of the document, against the same "
            + "definitions the toolchain used, and the answer changes nothing WHERE THE TYPE "
            + "HAS NOT DECLARED OTHERWISE: the verdict a caller receives is the toolchain's. "
            + "Where a type declares the database and this tenant holds the rows to answer "
            + "with, the database is asked FIRST and the toolchain is not run at all — so "
            + "there is no second answer to compare, and the tally counts only the writes "
            + "both answered. What is kept is a tally — the two agreed, "
            + "one of them found something the other did not, or this tenant holds no expanded "
            + "rows to compare against — by resource type and never by document, since a "
            + "document here is a person. A comparison that fails is counted and never reaches "
            + "the write."),
    VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE(
            "The database answers, from the rows a definition was expanded into, what a "
            + "toolchain answered from an object graph: how often an element may occur — "
            + "counted inside the parent it occurs in — what it must equal or contain, "
            + "whether a coded value is in the value set its binding names, and whether a "
            + "reference points at a record this store holds. The last two are joins, to the "
            + "terminology and to the records, which is why they are answered here at all. "
            + "What nothing here can judge is reported by nobody — a code from a system this "
            + "tenant does not hold, a reference to another server or to something contained "
            + "in the document — because unresolvable is not invalid. The checks read rows "
            + "and name no FHIR version, so one set of them serves every face. They "
            + "reach as far as the rows go and no further: a definition is expanded "
            + "from the profile's own snapshot, and a snapshot names an element of a "
            + "complex type without saying what that type holds — so a datatype's "
            + "insides are answered where a profile constrains them and are silent "
            + "where none does."),
    VAL_A_THIRD_ANSWERER_READS_THE_INDEX(
            "A third answerer checks a document against the definition index, in the serving "
            + "process, with no worker context and no round trip — and what it answers is held "
            + "against the database's own answer over everything the version publishes. It is "
            + "not a second specification: the checks that read rows are the specification and "
            + "this one is measured against them, because three answers nobody compares would "
            + "be worse than two that are. How often an element may occur is counted inside the "
            + "parent it occurs in, so the walk enters a backbone where the resource defines it "
            + "and a datatype's own structure where it does not — one contact holding two names "
            + "is wrong and two contacts holding one each is not. A contained resource is not "
            + "followed: the element says only Resource, and what a document may contain is a "
            + "question about the tenant's declaration rather than about cardinality. And "
            + "silence is not evidence — the walk reports how far it descended, because a "
            + "checker that never descended faults nothing and a correct corpus reads the same "
            + "either way."),
    VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS(
            "A tenant's definitions are read into flat arrays over one interned dictionary — "
            + "path, parent, min, max, type codes, binding and its strength, and the "
            + "invariants — from the ROWS a definition was expanded into, and never from the "
            + "packages a version publishes. The rows are the source because they are the "
            + "only place all three of what this has to hold arrive together: a version's own "
            + "structures, a tenant's own profiles, and whatever a face image carried. A "
            + "second expansion out of the packages would be a second specification with "
            + "nothing comparing the two. What is read is the closure of the types the tenant "
            + "declared rather than the version: composition is followed and reference is "
            + "not, so an element typed HumanName reaches HumanName, and one typed "
            + "Reference(Condition) reaches Reference and stops — Condition enters only where "
            + "the tenant declares it. What the index does not hold is the prose a model "
            + "built for authoring carries, the short and the definition and the comment, "
            + "because a checker never reads it and declining to hold it is most of what the "
            + "form is."),
    TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE(
            "A face is cut once per release into an image of its definitions schema, and a "
            + "tenant coming up on that face is brought up from the image rather than "
            + "reading the whole of what the face publishes through a chain and expanding "
            + "it again. An image is brought up FROM and never merged into: loading one "
            + "over rows that are already there would double what a face holds or lose "
            + "half of it, with nothing to say so. What the image carries is the face — "
            + "the definitions, their history and everything derived from them — and not "
            + "the tenant's own relationship to a feed, since a tenant that inherited "
            + "another's cursors would stand at a position it never reached."),
    VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE(
            "An image is cut in one consistent read and its manifest is written last, so a "
            + "cut that died partway through produces something without a manifest rather "
            + "than something that looks whole. An image with no manifest is refused, "
            + "because there is nothing to check it against and its rows would load "
            + "perfectly well."),
    VER_AN_IMAGE_FROM_ANOTHER_RELEASE_IS_REFUSED(
            "An image says what it was cut from — the release, the face, the shape its "
            + "definition rows were taken apart into, and the fingerprint of the SQL that "
            + "reads them — and one that disagrees with this release on any of those is "
            + "refused by name, nothing loaded. Refused rather than repaired: the tenant "
            + "comes up the way tenants came up before there were images. The check exists "
            + "because a wrong image fails nowhere — the rows load, the tenant serves, and "
            + "it answers from a specification or an expander that is not this one."),
    ZONE_AN_UNSERVABLE_ZONE_IS_SAID_AT_BRING_UP(
            "A tenant whose zone did not survive the trip to its face does not come up, and "
            + "says which definition was lost and what it was built on. Refused rather than "
            + "degraded: the tenant would otherwise serve the part of the zone that "
            + "survived, which looks exactly like serving the zone. A zone is a set of "
            + "rules somebody is relying on being applied, and most of one is not a smaller "
            + "promise but a different one nobody agreed to. A tenant on the zone's own "
            + "face is unaffected, because nothing was converted and nothing can have been "
            + "lost."),
    ZONE_WHAT_CONVERSION_CANNOT_CARRY_IS_REFUSED_BY_NAME(
            "A definition converted to another face is judged on that face rather than "
            + "trusted because it converted. Converting downward loses what the older "
            + "version cannot say, and a structure built on a resource that version never "
            + "had comes out well-formed and standing on nothing — it loads, and nothing "
            + "can be validated against it. So the projection, the one tenant holding both "
            + "the converted definitions and the face they were converted into, names "
            + "every definition whose base that face does not carry, and says which base "
            + "it lost. Named rather than counted: a count says a zone is partly "
            + "unservable and leaves somebody to find out which part."),
    ZONE_A_ZONE_IS_DECLARED_BY_THE_TENANT_THAT_IS_ONE(
            "A tenant is a jurisdiction because its own declaration says so, never because "
            + "somebody else named it. A member naming a tenant that does not declare "
            + "itself a zone is refused by name, and the refusal says where the "
            + "declaration belongs. Without it a one-word typo was load-bearing and "
            + "silent: an ordinary hospital named as a zone had a hub built over its "
            + "database and its ceremony became the one every member federates to, and the "
            + "deployment came up green. Being a zone and being in one are not exclusive, "
            + "so this is a property rather than a kind — a jurisdiction holds ordinary "
            + "records too. What it ends is the answer to `which tenants are zones` being "
            + "obtainable only by reading every other file and taking the union of what "
            + "they point at."),
    ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION(
            "A zone's definitions and records reach the tenants of a face it was not "
            + "written in through one projection per zone per face: a tenant that takes "
            + "the zone and stands on the target face, so the conversion happens once "
            + "rather than once per tenant. A zone serving a face it was written in has "
            + "no projection, because there is nothing to convert and one would be a "
            + "hop, a database and a second copy for nothing. Nobody declares them — "
            + "they follow from a zone's version and the faces of the tenants that asked "
            + "for it — and a tenant's declaration still names the zone, since which "
            + "projection serves it follows from its own face and is not a tenant's to "
            + "know."),
    SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE(
            "The database builds a document's envelope from the compiled parameters and "
            + "the document already in hand, by the same typed rules the JVM used: three "
            + "forms per coded value, a string folded and kept as written, a date at the "
            + "moment its span opens, a reference split into what it points at. Proven by "
            + "building both and comparing them over everything the version publishes, per "
            + "type, as a number that may rise and may not fall — because what a document "
            + "can be found by is the whole of what a search answers, and a difference is "
            + "a document that quietly stops being findable, which reads as an empty "
            + "result rather than as a fault."),
    /** The clinical user story holds the count per type at zero, over the
     * documents the r4 root carries. Serving a type from the database's
     * envelope is the step after this one and is not taken yet — what is
     * proven is that taking it would lose nothing. */
    SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED(
            "The envelope, the exclusive claims and the reference edges come off one walk "
            + "of the document in the database, and nothing the engine found is missing "
            + "from them — compared over the documents a face carries, per type, before a "
            + "single type is served from them. A search answers from the envelope, so a "
            + "difference is not a wrong answer but a missing one: the record is simply "
            + "not found, and an empty result is indistinguishable from there being "
            + "nothing to find. Which is why this comes before use rather than after it. "
            + "Losing nothing rather than matching byte for byte, because the envelope is "
            + "asked by containment: a value held twice and a value held once are the "
            + "same index, and each side duplicates differently for reasons of its own. "
            + "The claims and the edges are equal and not merely contained — an exclusive "
            + "claim nobody made would refuse somebody else's write, and an edge nobody "
            + "declared would answer a search that should find nothing."),
    SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES(
            "Every way a tenant can be asked after a record — the version's own parameters "
            + "and whatever the tenant has authored — is compiled when the definition "
            + "arrives and held as a row: where the values are, which of them count, and "
            + "what to make of what comes back. The same move the elements and the "
            + "invariants get, for the same reason: the answer never changes while the "
            + "definition stands, and what will run it is a database. One that does not "
            + "compile is refused where the author is standing, naming what stopped it — "
            + "evaluable is the weaker question, and an expression can evaluate perfectly "
            + "and still use a construct that selects nothing here, which is a parameter "
            + "that is accepted, indexed against nothing, and answers no search."),
    VER_CONVERSION_RUNS_BOTH_WAYS(
            "A converter exists for each direction between the versions this store "
            + "carries, registered by the version it converts FROM, so a zone written in "
            + "one version can serve tenants on an older face as well as a newer one. "
            + "What a downward hop costs is counted rather than assumed: every definition "
            + "a version publishes is carried across and back, and how much comes home "
            + "unchanged is recorded per type and may not fall. Coming home unchanged "
            + "proves a definition converted; not coming home does not prove it broke, "
            + "because an older version may simply have no way to say what a newer one "
            + "constrained — which is settled where the converted definition is expanded "
            + "on the target face, the one place the question can be answered."),
    VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN(
            "Every definition a tenant holds, its history, and every row derived from one "
            + "— the elements it was expanded into, the invariants compiled off it, the "
            + "shape stamps, the concepts and value sets a vocabulary was imported into — "
            + "are in one schema of their own, apart from the tenant's records. So what a "
            + "face gave a tenant is a thing the database can name and hand over, rather "
            + "than a filter by type inside tables shared with somebody's patients. The "
            + "face's own functions read what a definition says from that schema and "
            + "nowhere else, which is what makes a dump of it the whole of what the face "
            + "gave rather than most of it; reaching the RECORDS is the one thing they do "
            + "outside it, because a reference points at one. A record is never in the "
            + "schema and a definition never outside it: it is what a face is cut from and "
            + "handed to every tenant on that face, so a profile left among the records "
            + "makes the image short, and a patient among the definitions puts somebody's "
            + "record into everybody's copy."),
    FEED_DEFINITIONS_MOVE_ON_A_FEED_OF_THEIR_OWN(
            "Definitions are a domain with a feed and a cursor of their own. A record "
            + "never appears on that feed and a definition never appears on the record "
            + "feed, so a subscriber taking a face does not read the root's clinical "
            + "traffic on the way to the next profile, and the position a face is cut at "
            + "is a position records cannot move past."),
    TEN_A_TYPE_DECLARES_ITS_DOMAIN(
            "Which domain a type's rows live in is part of its registration and is "
            + "checked when a tenant comes up. A definition registered among the records, "
            + "or a record among the definitions, is refused by name — because nothing "
            + "about a misplaced type fails on its own, and what breaks is an image taken "
            + "later and handed out before anybody looks."),
    VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE(
            "The functions a tenant's database answers with are installed into its own "
            + "schema by the dbo release that carries them, and arrive no other way — never "
            + "through a chain, a restore or a feed, because a function that could be "
            + "replicated would be a way to run code on a tenant by writing to a stream. They "
            + "are plain SQL with no server extension, so they run wherever the store runs, "
            + "and a bring-up whose release carries the SQL already in place installs "
            + "nothing."),
    VER_DEFINITIONS_INDEXED_WITHOUT_THE_TOOLCHAIN(
            "A definition — structure, search parameter, value set, code system, map — is "
            + "indexed from its JSON along the version's own search parameters, with no "
            + "worker context, and the index is the one the toolchain would have written: "
            + "identical over every definition every carried face publishes. It exists "
            + "because the toolchain needs the version's definitions to parse one, and a "
            + "definition arriving at a tenant is exactly what the tenant does not hold "
            + "yet."),
    VER_VERSION_AGNOSTIC_CORE(
            "The engine has no knowledge of any FHIR version; all version meaning lives "
            + "in personality bundles. (R6, §1)"),
    VER_CONCURRENT_VERSIONS(
            "Tenants (and domains within a tenant) on different FHIR versions run "
            + "concurrently in one container. (R6)"),
    VER_PERSONALITY_OWNS_MEANING(
            "Parsing, validation, search-parameter extraction and subscription "
            + "evaluation are personality responsibilities, per version."),
    VER_SPECIFIED_VALIDATION(
            "Profile-resolution and validation semantics are specified by DBO — a "
            + "malformed or versioned canonical reference can never silently disable "
            + "validation."),
    /**
     * What closed the gap between the two halves of one request.
     *
     * <p>The parser drops what it cannot place before the validator is
     * handed a tree, and the bytes are kept as written so that what was
     * written is what is read. Both are wanted on their own; together they
     * let a document carry a field nothing had checked, nothing could search,
     * and every later reader was handed as part of the record.
     */
    VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED(
            "An authored write carrying content this face cannot read is refused by name, "
            + "at the same seam a violated binding is — an element the version does not "
            + "define is a mistake rather than an extension point, and FHIR already has "
            + "the extension point. Asking first gives the same answer: the finding is "
            + "made once and both the write and $validate read it. What arrived as "
            + "somebody else's publication is held and warned instead, like any other "
            + "imperfect arrival. A TYPE MAY DECLARE OTHERWISE — `unknown: kept` — for the "
            + "tenant taking a dialect from a sender it cannot change; what it buys is the "
            + "document and what it costs is an element that is stored, returned and "
            + "answerable by nothing, said once per type rather than per document so that "
            + "accepting it is a thing a deployment knows about its feed rather than a "
            + "thing a reader discovers. It is declared per type and never a default, "
            + "because a tenant that tolerates a dialect in one feed has no reason to "
            + "tolerate one everywhere."),
    VER_VALIDATION_WITHOUT_WRITING(
            "A caller can ask whether a resource would be accepted without writing it "
            + "(`[Type]/$validate`), and the answer is the write path's own: what it "
            + "accepts a write accepts, what it rejects a write rejects. Issues carry the "
            + "locations a refusal carries, so a caller is told what to fix. The verdict "
            + "is the resource's shape — state a write settles (an identity already "
            + "claimed, a version moved on) is not promised."),
    VER_ONE_READ_PER_REQUEST(
            "Accepting a write reads its payload once, however many parts of the write "
            + "ask about it — the type, the verdict and the searchable envelope come from "
            + "one read. A payload rewritten on its way into the engine is read as it now "
            + "stands, so what is indexed is what is stored."),
    VER_BALLOT_RECORDED_PER_VERSION(
            "A stored version records the exact version it was authored under — a "
            + "ballot by its full spelling, never the release it anticipates — so a later "
            + "version has something to convert from and a reader is never told a guess."),
    VER_DEFINITIONS_TRAVEL_WITH_THE_FACE(
            "A face brings the definitions it validates and extracts against. Bringing "
            + "a tenant up fetches nothing over the network and needs no writable cache "
            + "outside the store's own state."),
    VER_BALLOT_SERVED_AS_AUTHORED(
            "A version still at ballot promises no normalized truth form and no "
            + "conversion to or from another version: what an author wrote is what a "
            + "reader receives. Normalising under a ballot's understanding would bake it "
            + "into bytes that are never rewritten, and the next ballot moving an element "
            + "would lose what it moved."),
    VER_TRANSITION_BY_CONVERTERS(
            "Moving a tenant between FHIR versions is converters plus reindex, not a "
            + "data migration ceremony."),

    // ── SRCH — migrated from hand-written prose (2026-08-27) ──

    /**
     * It answered nothing, which is the worst of the three possible wrongs.
     * The token compiler split on the system separator and never on the
     * comma, so several values were looked for as one literal that nothing
     * carries — neither honoured nor refused, an ordinary-looking empty page.
     * Strict search catches an unsupported PARAMETER; an unsupported value
     * syntax fell between it and the compiler.
     *
     * <p>The engine could not state it either: equality predicates are
     * conjunctive, so adding them in a loop asks for an object carrying every
     * value at once — wrong in a different way and just as quiet. Hence a
     * predicate of its own rather than a fix in the compiler.
     */
    SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM("Several values for one search parameter are the "
            + "union, as the comma has always meant, with a backslash escaping one that "
            + "belongs to the value; where the union cannot be expressed — between two "
            + "ranges rather than two values — it is refused by name rather than answered "
            + "with an empty page."),

    SRCH_TIER1_PARITY(
            "Every search feature a production healthcare platform actually issues "
            + "works identically ([inventory](../evidence/search-usage-inventory.md))."),
    SRCH_STRICT_BY_DEFAULT(
            "An unsupported search parameter is rejected, never silently ignored."),
    SRCH_HONEST_CAPABILITY(
            "The CapabilityStatement is generated from what the server actually serves "
            + "— the configured types, the interactions their declared handling permits, "
            + "the conditional writes their identity class allows, the history their "
            + "durability keeps, the search parameters accepted, and the operations "
            + "registered by the facades that were wired. An operation is declared "
            + "because it is routable: the router and the statement read one list, so "
            + "neither a served-but-undeclared operation nor a declared-but-unanswered "
            + "one is expressible."),
    SRCH_TYPED_ORDERING(
            "Sorting and range filtering are typed — numeric, date and token semantics "
            + "are correct, with matching indexes. (D3)"),
    /** TODO: prove it in a test. */
    SRCH_DECLARED_INDEXES(
            "Indexing (including side tables for hard parameters) is declared by the "
            + "personality as part of its search contract, from day one."),
    SRCH_CUSTOM_PARAMETERS(
            "A tenant or module can register a custom search parameter; extraction, "
            + "reindex and the new index follow automatically — over the rows already "
            + "stored as well as the ones that come after, because a parameter that "
            + "answered only about the latter would omit the tenant's history while "
            + "looking healthy. An expression the store cannot evaluate is refused at "
            + "the write, where somebody is present to fix it, and nothing is advertised "
            + "or accepted until the reindex behind it has finished."),

    // ── IDN — identification ──

    IDN_CLAIM_STRENGTH_BOUNDS_THE_CONCLUSION(
            "What a claim can conclude follows how well it is held. One cryptographically "
            + "presented claim matching a single subject resolves without anybody looking; "
            + "a number read off a document never resolves anybody by itself; claims "
            + "pointing at different people destroy certainty rather than choosing between "
            + "them; and no match at all is an ordinary answer — the person before their "
            + "first visit — rather than an error. A revoked document resolves nobody, "
            + "because it is in somebody else's hands, while a superseded one still finds "
            + "the person whose records refer to it. A claim naming no issuing system is "
            + "refused: the same digits are two people in two countries."),

    IDN_A_DECISION_IS_EVIDENCE(
            "A person's conclusion about who somebody is, is kept as evidence rather than "
            + "applied as a fact. It names who decided, when, and what they were looking "
            + "at; one that names nobody is refused, because it could never be questioned. "
            + "Undecided is a state a record lives in rather than a failure. A candidate "
            + "somebody already declined comes back marked rather than hidden — hiding it "
            + "would make a wrong decision permanent and invisible — and no machine "
            + "silently reverses it. Decisions are append-only and scoped to the claims "
            + "they were about: revising one means recording a new one."),

    IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE(
            "Attaching an identity to a subject can be undone, and undoing it takes the "
            + "identity without touching the care: a wrong binding put one person's records "
            + "in another's, so withdrawal must always be available and must leave the "
            + "clinical data alone. What is withdrawn stays answerable — that somebody was "
            + "identified, and that it was undone, are both facts a regulator may ask "
            + "about — so events are append-only and a mistaken withdrawal is as "
            + "recoverable as a mistaken binding. A binding names who made it and why, and "
            + "one subject's bindings say nothing about another's."),

    IDN_ANONYMITY_IS_DECLARED_NOT_INFERRED(
            "Anonymous on purpose is something a subject says, not something absence "
            + "implies. Two unbound subjects are otherwise identical — one expects to be "
            + "identified and the other must not be — and an intention cannot be stated by "
            + "an absence, so the declaration is positive, states its basis, and is refused "
            + "without one. While it stands, binding is refused rather than discouraged; "
            + "declaring it over a standing identity is refused too, because the "
            + "identification has to be withdrawn first rather than shadowed. Withdrawal "
            + "stays available throughout, and a person may lift their own declaration."),

    IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO(
            "What an identification is worth is the weaker of how somebody authenticated "
            + "now and how well the identification itself was made. A national eID "
            + "presented today does not upgrade one made last year from a photocopy, and a "
            + "weak assertion does not inherit a strong binding. Re-identifying to a higher "
            + "standard raises it and the history keeps both; withdrawing leaves nothing to "
            + "inherit; and an identification that established nothing is refused rather "
            + "than recorded at no assurance. It is per identity, not per subject: two "
            + "identities on one subject say nothing about each other."),

    // The recital of the other six identification promises is gone: this one
    // said what they say, in their words, and a promise that restates its
    // neighbours has no status of its own to lose. What is left is the part
    // only this promise makes — that the surface exists, and that its scope
    // is not the resource grammar's.
    IDN_IDENTIFICATION_IS_REACHABLE(
            "A tenant identifies somebody through a door of its own, reachable as a "
            + "surface rather than assembled by each caller out of the promises below. "
            + "The door carries its own scope, outside the resource grammar: a grant "
            + "over the store's resources does not reach the act that de-anonymises "
            + "somebody."),

    IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED(
            "What may leave and what this particular recipient may see are different "
            + "questions, and a tenant answers the second by declaring an audience: which "
            + "types it is answered about at all, and what a read of one of them reveals. "
            + "A type outside the declaration is absent rather than refused, because a "
            + "refusal naming it would tell the recipient it exists. The mode follows the "
            + "declaration rather than the request — a recipient that could ask for more "
            + "would make the declaration advice — and an audience nobody declared sees "
            + "nothing, because a typo in a serving surface and a partner who was removed "
            + "both want silence. Naming no audience is the tenant working with its own "
            + "records, and nothing about it changes."),

    IDN_THE_ASKER_IS_A_DECLARED_AUDIENCE(
            "The application that asked for a run collects what the run was given and "
            + "produced as an audience the step names, one the tenant declared: its types "
            + "bound what is collectable and its mode is what a collection reveals, and "
            + "the request cannot raise either. A step naming an audience the tenant never "
            + "declared is refused when the declaration is read, and a step naming none "
            + "leaves its asker the run's answer alone — references, never content."),

    IDN_A_STEP_STATES_ITS_PURPOSE(
            "A collection reveals a person whole only with two keys: the purpose the step "
            + "declared, and the same code stated by the collecting request at the moment "
            + "of reading. A step whose audience reveals a person whole and that states no "
            + "purpose is refused when it is declared; a request stating no purpose or "
            + "another one is answered in the strict mode, and neither key alone reveals "
            + "more than that."),

    // ── FEED — migrated from hand-written prose (2026-08-27) ──

    FEED_ONE_PRIMITIVE(
            "Pagination, subscription delivery, content streams and replica sync are all "
            + "the same primitive: an ordered, replayable sequence with an opaque durable "
            + "cursor."),
    FEED_KEYSET_CURSORS(
            "Cursors are keyset positions, never offsets; a page is stable under "
            + "concurrent writes."),
    FEED_PUSH_ACK_RESUME(
            "Push consumers acknowledge with the cursor; any interrupted stream resumes "
            + "from the last acknowledged position."),
    FEED_IDEMPOTENT_DELIVERY(
            "Delivery is at-least-once with idempotent apply by identity and version."),
    FEED_NAMED_CONSUMERS(
            "Every durable consumer holds a named cursor in the store; progress, lag "
            + "and replay are uniformly observable."),
    /** TODO: prove it in a test. */
    FEED_LEAN_WIRE_OPTION(
            "Planned — Between DBO-speaking parties, feeds stream lean frames; FHIR Bundles are "
            + "assembled only at the FHIR surface."),

    // ── EVT — migrated from hand-written prose (2026-08-27) ──

    EVT_TRANSACTIONAL_OUTBOX(
            "Every change event originates as an outbox row committed with the write. "
            + "(R8, §6)"),
    /**
     * What was turned on, kept apart from the topic promise beside it. Both
     * deliver now, and they stay two sentences: folding them together would
     * let a criteria test stand behind a topic claim, which is the
     * callerless-seam defect arriving through a citation rather than through
     * code.
     */
    EVT_A_TENANT_DELIVERS("A tenant serving a face that composes notifications mounts its own "
            + "dispatcher: a subscription it holds is matched against what changes and the "
            + "subscriber is posted to, with the name of what changed rather than the record "
            + "itself where the channel asked for no payload."),

    /**
     * Narrowed to what is served. It said "backported to the R4 personality"
     * as well, and that half is not reachable: an R4 topic is platform
     * configuration rather than a record, so there is nowhere for a tenant to
     * declare one. Leaving the wider sentence standing on a proof of the R5
     * shape would be a citation covering ground it never walked.
     */
    EVT_FHIR_SUBSCRIPTIONS(
            "Topic-based FHIR Subscriptions are served where the version declares topics as "
            + "records: a topic, a subscription filtered within what that topic allows, and a "
            + "notification naming the subscription, the topic and the event — carrying the "
            + "focus by name where the subscription asked for id-only. (R8)"),
    /** TODO: prove it from a mounted tenant (#262). Retries, backoff and
     * dead-lettering are exercised against an engine a test built. A tenant
     * delivers now, so what is unproven is narrower than it was and sharper:
     * nothing fails a delivery from a tenant and watches it recover. */
    EVT_DURABLE_DELIVERY(
            "Planned — Subscription delivery is durable, tenant-scoped and replayable, with "
            + "retries, backoff and dead-lettering. (R8, §9)"),
    /** TODO: build it, then prove it (#265). There is an engine in a container
     * now, one per tenant — but nothing exposes it, so a consumer sharing the
     * JVM still takes a loopback HTTP hop to hear about a change in its own
     * process. The surface does not exist in any form. */
    EVT_IN_PROCESS_SURFACE(
            "Planned — Co-located consumers get the same topics with identical semantics through "
            + "the in-process/OSGi surface. (R8)"),

    // ── WF — migrated from hand-written prose (2026-08-27) ──

    WF_POSTGRES_SUBSTRATE(
            "Durable tasks, streams and inter-instance communication run on the "
            + "DBOS/Postgres substrate; no external broker. (R4)"),
    WF_TWO_PLANES(
            "Records live in the tenant plane, structurally isolated. The shared platform "
            + "plane carries coordination and the copies work needs in flight — manifests "
            + "readable, because routing is what they are for, and payloads sealed to the "
            + "participant meant to open them. Isolation of a record is structural; of a "
            + "copy in flight, cryptographic."),
    WF_CONTENT_FREE_PLATFORM_PLANE(
            "The platform plane never holds tenant credentials, and never holds resource "
            + "content in a form readable in that plane. A sealed payload satisfies this; "
            + "the plaintext form would not, however briefly."),
    /** TODO: prove it in a test. */
    WF_PLATFORM_COORDINATED_HOPS(
            "Planned — Every cross-plane or cross-tenant hop is coordinated by the platform; no "
            + "direct tenant-to-tenant connection exists."),
    WF_HOPS_AUDITED(
            "Every hop leaves a travel entry about the task — who handed to whom — and a "
            + "travel entry is not a reading: audit of the journey is structural, not "
            + "per-integration, and it never says anybody looked at the content."),

    // ── SCAL — migrated from hand-written prose (2026-08-27) ──

    /** TODO: prove it in a test. */
    SCAL_DURABLE_ASSIGNMENT(
            "Planned — The tenant→pod assignment is durable state with version-driven takeover."),
    /** TODO: prove it in a test. */
    SCAL_SINGLE_WRITER_TENANT(
            "Planned — A tenant's serving pod is its single writer, making local caching and "
            + "local subscription state correct by construction."),
    /** TODO: prove it in a test. */
    SCAL_TRANSPARENT_ROUTING(
            "Planned — Callers look up a tenant's service in the registry; local instance or "
            + "remote proxy is indistinguishable."),
    /** TODO: prove it in a test. */
    SCAL_TWO_HOP_LOCALITY(
            "Planned — Requests enter at the closest public node (Kubernetes locality), then "
            + "route to the serving pod (tenant assignment)."),
    /** TODO: prove it in a test. */
    SCAL_NO_SHARED_STATE_BROKER(
            "Planned — The architecture requires no Redis-class shared-state service."),

    // ── TERM — migrated from hand-written prose (2026-08-27) ──

    TERM_NATIVE_FORM(
            "Terminology lives in a normalized, query-optimized form; the FHIR resource "
            + "form is a wire projection assembled on demand."),
    TERM_BULK_LOAD(
            "Loading a large CodeSystem is a native bulk operation — no chunking "
            + "workarounds, no parameter-cap ceilings."),
    TERM_EVERY_TENANT_ANSWERS(
            "Every served tenant answers `$lookup`, `$expand` and `$validate-code` from "
            + "its own store's native form, whichever FHIR version it speaks; no tenant "
            + "is a second-class reader. A terminology write reaches that form rather "
            + "than being stored whole — a resource that is present and answers nothing "
            + "is worse than one that is absent."),
    TERM_OPERATIONS_FROM_NATIVE_FORM(
            "`$expand`, `$lookup` and `validate-code` are served from the normalized "
            + "form at tenant-local speed."),
    TERM_BINDINGS_ANSWERED_FROM_RECORDS(
            "A binding's value set and code system are answered from the tenant's records "
            + "— the version's own, published by its root and taken apart into the native "
            + "form on arrival, and the tenant's own — never from a carried package. A code "
            + "outside a required binding is refused by name; a code from a system the tenant "
            + "does not hold is unresolvable, never invalid."),
    TERM_VALIDATION_USES_TENANT_TERMINOLOGY(
            "Validation resolves coded values against the tenant's own terminology "
            + "where the carried definitions are silent: a code from a system the tenant "
            + "holds either exists in it or the write is refused, value-set membership "
            + "respects the binding's declared strength, and a system nobody holds is "
            + "reported as unresolvable — a coverage fact, never an invalidity."),

    // ── SYNC — migrated from hand-written prose (2026-08-27) ──

    SYNC_DECLARED_ONLY(
            "Cross-tenant content synchronization happens only for declared "
            + "dependencies; nothing syncs undeclared."),
    /** TODO: prove it in a test. */
    SYNC_ANY_TYPE(
            "Any resource type can be declared as a cross-tenant dependency; each type "
            + "defines its grain — for terminology, the CodeSystem together with its "
            + "related ValueSets."),
    SYNC_TERMINOLOGY_GRAIN_SURVIVES(
            "A streamed terminology dependency rebuilds the receiving tenant's native "
            + "form: the source sends the whole CodeSystem even though it stores a shell, "
            + "and the dependent takes it apart into its own concepts. After catch-up the "
            + "dependent answers `$lookup` and `$expand` locally, which is the only proof "
            + "that the grain survived the hop — a copy's stored payload never contains a "
            + "concept at either end."),
    SYNC_CONVERT_ON_APPLY(
            "Streamed objects are converted at apply into the receiving tenant's FHIR "
            + "version and object shape by the registered converter chains; an "
            + "unconvertible object dead-letters visibly and degrades the dependency, "
            + "never silently skips."),
    SYNC_PROVENANCE_COPIES(
            "Streamed copies are read-only and provenance-tagged with source tenant and "
            + "version; updates and retirements propagate through the same stream."),
    SYNC_LOCAL_SHADOWING(
            "A tenant's own object with the same base identity overrides the streamed "
            + "copy — version-neutrally, across FHIR versions and business versions; "
            + "removing the override falls back to the live upstream version."),
    SYNC_A_STREAM_APPLIES_EACH_CHANGE_ONCE(
            "A stream read by two callers at once — a tenant draining its face while it "
            + "comes up, and the round that keeps every stream in step — applies each change "
            + "it carries once: one reader at a time per stream, the next reading on from "
            + "where the last acked. Other streams, the same tenant's included, run beside "
            + "it."),
    SYNC_DIRECT_UPSTREAM_ONLY(
            "A tenant declares dependencies only against its direct upstream; chains "
            + "compose hop by hop."),
    SYNC_SPEC_DECLARED(
            "A tenant's content dependencies are part of its tenant spec "
            + "(configuration); the runtime wires declared streams at bring-up and "
            + "removes them when undeclared."),
    SYNC_FULL_HISTORY_CATCH_UP(
            "A newly declared dependency catches up from the upstream's full history; "
            + "pre-existing content arrives the same way live changes do."),
    SYNC_A_PLACE_READS_ONLY_WHAT_THE_TENANT_DID_NOT_AUTHOR(
            "A second place of a tenant reads, over the lane, the tenant's definitions and the "
            + "records of the types it takes from upstream, and nothing else. A type the "
            + "tenant authors is not on that feed whatever the place asks for, because it "
            + "travels as work; a type stored in parts arrives whole; and a credential that "
            + "holds no place reads nothing at all."),
    SYNC_A_PLACE_READS_ON_FROM_WHERE_IT_ACKNOWLEDGED(
            "The tenant keeps each place's position, under the participant's name and per "
            + "feed, and moves it forward only. A place reads from where it last acknowledged, "
            + "two places never share a position, and a place can neither rewind its "
            + "position nor read from one of its own choosing."),
    SYNC_A_PLACE_COMES_UP_FROM_ITS_ORIGIN(
            "A tenant served as a second place of a tenant elsewhere comes up from that "
            + "tenant's feeds alone, read over the lane: its face, the content its declaration "
            + "takes from upstream and its own definitions, already composed where it came "
            + "from. Its declaration is the origin's own, unchanged, and none of the upstreams "
            + "it names runs beside it. What the origin authors does not arrive, and what "
            + "arrived is the place's to serve and not to change."),
    SYNC_A_PLACE_IS_DECLARED_AS_ITS_ORIGIN_IS(
            "A site serves a place of each tenant elsewhere that it reaches, declared as that "
            + "tenant is declared there, and keeps each declaration it reads. An origin that "
            + "cannot be reached, or no longer answers the site, leaves its place served from "
            + "the declaration last kept; a place stops being served only when that kept "
            + "declaration is removed on the site; and a place with nothing kept and no origin "
            + "to ask makes the read fail rather than answer that the site serves nothing."),
    SYNC_A_PLACE_TAKES_ITS_FACE_FROM_A_ROOT_BESIDE_IT(
            "A site runs a face root of its own beside each place, from its own release and "
            + "under the code the place's declaration names, unless the deployment declares "
            + "that root itself. The place takes its face from that root as any subscriber "
            + "takes one, and reads from its origin only the definitions that are not the "
            + "face, so the face is never carried across the link."),
    SYNC_A_PLACE_SERVES_WHAT_IT_HOLDS_WHILE_ITS_ORIGIN_IS_AWAY(
            "A place that cannot read its origin, because the link is down or synchronisation "
            + "is off, keeps serving what it holds, a restart included, and says once that it "
            + "cannot read rather than on every round. When it can read again it carries on "
            + "from where it last acknowledged. A place that has never received anything has "
            + "nothing to serve, and waits for its origin as a tenant waits for an upstream."),

    // ── VAL — migrated from hand-written prose (2026-08-27) ──

    VAL_BINDING_STRENGTH_IS_THE_ANSWER(
            "A coded value is checked against the terminology the store holds, and the "
            + "answer follows the binding's strength: required violated is a refusal, "
            + "weaker bindings are advice a caller is given rather than refused for, and "
            + "everything the face had to say reaches the outcome rather than only what "
            + "would refuse."),
    VAL_UNRESOLVABLE_IS_NOT_INVALID(
            "A code from a system the store does not hold is reported as unresolvable, "
            + "never as invalid: one says this store's content is incomplete and the "
            + "other says the caller's data is wrong, and they are fixed by different "
            + "people."),

    // ── OPS — migrated from hand-written prose (2026-08-27) ──

    /**
     * Binary content a tenant holds lives in that tenant's own database and
     * comes back as the bytes that were written, so erasure-by-drop takes it
     * with everything else the tenant held rather than by reaching a second
     * system that can be forgotten.
     *
     * <p>The fallback tier of {@link #OPS_TENANT_BLOB_STORAGE}, and the one
     * every deployment has. That promise stays PLANNED because its object
     * store tier is not built; this one is what is kept today, said narrowly
     * enough to be true.
     */
    OPS_TENANT_BLOBS_ARE_TENANT_DATA(
            "Binary content a tenant holds is kept in that tenant's own database and "
            + "returned byte for byte, needing no credential and no provisioning of its "
            + "own; erasure-by-drop removes it with the tenant, because it is in what "
            + "gets dropped rather than in a second place something has to reach."),

    /** TODO: prove it in a test. The object store tier is not built. */
    OPS_TENANT_BLOB_STORAGE(
            "Planned — Binary content lives in per-tenant blob storage provisioned "
            + "credential-blind; erasure-by-drop extends to it; small deployments fall "
            + "back to Postgres behind the same interface."),
    OPS_NUMBERS_LEAVE_THE_NODE(
            "A deployment points the telemetry seam at its collector by configuration, "
            + "never by code, and the node's numbers arrive there in the published protocol "
            + "— counts as sums, levels as gauges, durations as histograms, labelled from "
            + "the seam's own closed vocabulary. Reporting is not a dependency of serving: a "
            + "collector that is absent, slow or refusing costs the caller nothing and is "
            + "said once, and a node with no endpoint counts and sends nowhere."),
    OPS_FLEET_IS_ACTED_ON_THROUGH_THE_LANE("The process that reads a deployment can "
            + "also act on it, and only through the doors a participant uses: it holds a "
            + "supervisory credential per tenant, granted separately from the one it reads "
            + "with and usually not granted at all, and posts the tenant's own lane verb — "
            + "so every rule about the act is the tenant's and is met on the way in. Looking "
            + "must not carry the authority to overturn work, so a reader given no "
            + "supervisory credential is read-only by construction, and where the reader is "
            + "a service its act surface is not mounted at all unless the deployment named a "
            + "second token for it. An act says which node carried it, and one that did not "
            + "happen says why rather than passing quietly."),

    OPS_RUNTIME_SAYS_WHAT_IT_SERVES(
            "A runtime can be asked which tenants it is serving, and what it is doing "
            + "about the ones it is not: serving, coming up, failed to come up — one "
            + "state per tenant it has been told about. The answer comes from runtime "
            + "state, never from re-reading the declarations, so a caller comparing the "
            + "two can find a disagreement rather than confirming its own writes. "
            + "Cross-tenant, so no tenant credential buys it."),
    OPS_FLEET_IS_READ_FROM_OUTSIDE(
            "A deployment is read from one process outside every container, over the "
            + "doors its nodes and tenants already serve: a node is asked what it serves "
            + "and what it has installed under the deployment's own token, and a tenant "
            + "is asked about its work under a credential its own authority minted, so "
            + "the reader holds one credential per tenant and is never handed a surface "
            + "that crosses them. Every answer is labelled with the node it came from, "
            + "nothing is copied, and a node that does not answer or a tenant the reader "
            + "holds no credential for is in the reading as such rather than missing "
            + "from it."),
    /** TODO: prove it in a test. */
    OPS_MIGRATION_AS_DEPLOYMENT(
            "Planned — Schema and engine upgrades ride rolling deployment: the highest-version "
            + "node leads, migrates, and older nodes passivate. (D5)"),

    // ── MNT — migrated from hand-written prose (2026-08-27) ──

    MNT_BACKUP_IS_EXPORT(
            "Backup and export are one mechanism, restore and import another single "
            + "one; every backup is restorable by the everyday import path."),
    MNT_PORTABLE_STATE_EXPORT(
            "The latest-state export is idempotent, store-independent FHIR (with blob "
            + "content, hash-verified) — importable into a fresh tenant, the same tenant, "
            + "or any other FHIR store. It travels as Bulk Data: NDJSON per type whose "
            + "resources carry their own id and version, beside the manifest that spec "
            + "defines — same digests the archive was attested over, so a stranger "
            + "checking the export and a party checking the signatures cannot get "
            + "different answers."),
    MNT_HISTORY_BY_SCHEMA(
            "Version history, audit and consumer state live in their own database "
            + "schemas, so the high-fidelity history element is a schema-scoped dump, "
            + "restorable byte-exact."),
    MNT_OWNER_KEY_ENCRYPTION(
            "An export bundle is encrypted so that only the tenant owner's master key "
            + "can open it; the platform operates backups it cannot read, and restore "
            + "requires the owner."),
    MNT_SNAPSHOT_CONSISTENT(
            "The state element is cut at a single consistent snapshot; incremental "
            + "export is the feed from that snapshot's cursor."),
    MNT_ARCHIVE_ROOT_OVER_CONTENTS(
            "An archive's attested root is computed over the manifest's per-entry "
            + "digests rather than over the archive's bytes, so re-packing, "
            + "re-compressing or reordering does not invalidate what was attested."),
    MNT_BOTH_PARTIES_ATTEST(
            "An archive carries two detached signatures over that root — the vendor's "
            + "and the tenant's — and the tenant countersigns without resealing, so "
            + "neither party can produce an attested archive alone."),
    MNT_IMPORT_REFUSES_UNATTESTED(
            "Objects enter a store from an archive by one path only: the root "
            + "recomputes and both signatures verify, or nothing is written. A refusal "
            + "names what was wrong with the archive rather than failing part-way through "
            + "it."),
    MNT_ATTESTATION_READS_AS_FHIR(
            "An archive's attestation renders as a `Provenance` carrying FHIR's "
            + "`Signature`, so a customer's own tooling can check what it was handed "
            + "without learning this store's JSON. A view rendered by the face, never the "
            + "truth form — an archive of a non-FHIR domain is attested the same way and "
            + "has no Provenance."),
    MNT_ACCEPTED_ROOT_RECORDED(
            "A destination records the root it accepted and the two keys that signed "
            + "it, in the tenant's own audit trail, so what was imported and what both "
            + "parties said it was stays answerable without the archive."),

    // ── PRM — migrated from hand-written prose (2026-08-27) ──

    PRM_NAME_IS_THE_CODE(
            "A promise is declared exactly once, as an enum constant; its code derives "
            + "from the constant's name and its catalogue's namespace, so a citation "
            + "cannot drift from a declaration — there is no string to mistype and no "
            + "generator to trust."),
    PRM_GAP_IS_FIRST_CLASS(
            "Unstated ground is declared as a gap with plain text; a gap registers, "
            + "carries a stable code, and counts against coverage until promoted to a "
            + "named promise.") {
        @Override
        public String assurance() {
            return "Proven by CodeAndGapTest#gapCodesAreStableAndDistinct and #aGapNeedsItsText "
                    + "in the promise module, which cannot cite this constant: the framework "
                    + "has no dependency on any product's catalogue, and acquiring one to "
                    + "prove itself would invert the design it exists to enforce.";
        }
    },
    PRM_A_STORY_IS_CITED_NOT_CLAIMED(
            "A user story is a constant beside the promises, and promises declare the "
            + "stories they serve; a story's legs are projected from those declarations "
            + "rather than written by hand, so a story cannot cite a promise that does "
            + "not exist, cannot claim a leg nothing promises, and reads unproven while "
            + "every leg it rests on is only planned. A story claims no evidence: coverage "
            + "arrives only through promises citing it."),
    PRM_REGISTERED_AT_COMPILE_TIME(
            "An annotated catalogue is registered during its own component's "
            + "compilation — no classpath is swept, and a registration regenerated on "
            + "every compile cannot drift or be lost."),
    PRM_CATALOGUE_READ_WHOLE(
            "The registry reads a catalogue's constants whole — proven, planned and gap "
            + "alike — never as a side effect of what happened to be class-loaded."),
    PRM_DOWN_LINKS_ONLY(
            "A classification declares the promises that fulfil it; a promise never "
            + "names its classifications; the inverse is derived. One direction, one "
            + "truth.") {
        @Override
        public String assurance() {
            return "Proven by ModelTest#downLinksOnly in the promise module, which cannot "
                    + "cite this constant without the framework depending on a product.";
        }
    },
    PRM_AREAS_MERGE_BY_CODE(
            "Composition merges same-code areas across catalogues and refuses two with "
            + "conflicting prose rather than picking one.") {
        @Override
        public String assurance() {
            return "Proven by ModelTest#areasMergeByCode and #conflictingAreasRefused in the "
                    + "promise module, over two fixture catalogues. This one could not move "
                    + "here even if the cycle were solved: a single product has no second "
                    + "catalogue to merge with, and no conflicting prose to refuse.";
        }
    },
    PRM_CITATION_IS_TYPED(
            "A test cites promises through its product's own enum-typed annotation, "
            + "recognised by meta-annotation — a mistyped citation is a compile error, "
            + "and the framework never learns a product's types.") {
        @Override
        public String assurance() {
            return "Proven by CitationIndexTest#citationsAreIndexedAtCompileTime in the "
                    + "promise module, where an identically-shaped decoy annotation without "
                    + "@Cites is ignored — the point being that the meta-annotation alone "
                    + "decides, which a test citing through @Proving could not show.";
        }
    },
    PRM_PROOFS_INDEXED_AT_COMPILE_TIME(
            "Citation sites are indexed during the product's own compilation; a renamed "
            + "or deleted proof site cannot leave a stale citation behind."),
    PRM_STATUS_IS_DERIVED(
            "A promise's status is computed — cited is proven, named-uncited is "
            + "planned, assurance is declared on the constant, a gap is a gap — never "
            + "asserted at a proof site."),
    PRM_COVERAGE_IS_A_FOLD(
            "A classification's coverage is the fold of its declared promises' "
            + "statuses, gaps included; an area's is the fold of its classifications."),
    PRM_PROJECTION_IS_GENERATED(
            "The catalogue's prose form is generated from the composed model, never a "
            + "second source; a hand-edit or a stale projection fails the build."),
    /** TODO: prove it in a test. */
    PRM_COVERAGE_ON_THE_RESULTS_PAGE(
            "Every CI run's results page leads with the composed promise coverage "
            + "report."),

    // ── SCIM — migrated from hand-written prose (2026-08-27) ──

    SCIM_DECLARED_PER_TENANT(
            "A tenant serves SCIM 2.0 only when its spec declares it (the block naming "
            + "the externalId system); absent the block, the endpoints do not exist."),
    SCIM_USER_IS_THE_PERSON(
            "A SCIM User is the human: the externalId claimed and identifying data "
            + "authored on the Person, with a linked Practitioner capacity ensured on "
            + "create — the same linkage the authority walks at token time."),
    SCIM_ENUMERATION_STAYS_INSIDE(
            "The by-system enumeration answering the user list is a vault method inside "
            + "this server; no store API, face or FHIR search gains it, and an "
            + "enumeration-shaped search stays refused at the front door."),
    SCIM_DIRECTORY_CREDENTIAL(
            "The SCIM client's scope admits the SCIM surface and nothing else; its "
            + "token is refused by the FHIR surface and a store token is refused by SCIM."),
    SCIM_DEPROVISION_IS_A_STATE(
            "Deactivation sets active=false on the person and the capacity; it is never "
            + "erasure — that remains the vault's own ceremony with its own audit shape."),
    SCIM_EVERY_OP_IS_A_DISCLOSURE(
            "Every SCIM operation runs with the client as caller and an administrative "
            + "purpose stated, so it lands in the trail as one recorded provisioning "
            + "disclosure."),
    SCIM_GROUPS_READ_ONLY(
            "Groups render from active role grants and refuse writes permanently — who "
            + "works here is the identity provider's call; who is an admin here is not.");

    private final String text;

    DboPromises(String text) {
        this.text = text;
    }

    @Override
    public String text() {
        return text;
    }
}
