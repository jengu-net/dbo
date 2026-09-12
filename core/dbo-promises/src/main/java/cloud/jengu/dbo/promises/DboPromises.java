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
            + "parameters, so every database the store provisions is pinned not to, an "
            + "isolated tenant refuses to come up on a database that would, and the pin is "
            + "checked at every bring-up rather than assumed."),

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
            + "work is — the holder, and when a milestone is recorded the step's own word "
            + "for it with its derived position — in every version the face serves."),

    PROC_STEPS_ARRIVE_BY_INTRODUCTION("A linked participant introduces the step "
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

    PROC_INTRODUCTION_GRANTS_NOTHING("A step introduced over the link grants its "
            + "introducer nothing: the declaration binds the introducer exactly as it "
            + "binds anybody, and what it may take stays the intersection of its scopes "
            + "and what the step admits."),

    PROC_CLAIM_IS_THE_INTERSECTION("What a participant may claim is the intersection of "
            + "what its credential covers and what the step admits: the lane narrows the "
            + "work it offers and refuses a claim outside the entitlement, and the store "
            + "refuses an executor at a scope the step never opened itself to. A step "
            + "cannot grant its executor more than the executor already holds."),

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

    PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS("A host that reaches the store over HTTP "
            + "obtains the same lane as one that holds the store in-process: the tenant "
            + "serves the participation verbs on its own private surface, guarded by its "
            + "own authority, and a runner cannot tell the two apart. The entitlement is "
            + "derived from the credential and never asked for by the caller, and a "
            + "credential bounded to steps may work only as itself."),

    // ── PROC continued — migrated from hand-written prose (2026-08-27).
    // Six carry no verified citation yet; each says so and names what a
    // proof would have to show, per the pilot's own rule that unstated
    // ground is a gap, not a silent PLANNED.

    PROC_STEP_SERVICE_EMBEDDABLE("One embeddable runner registers step services and needs "
            + "only the participation lane — no orchestrator, no transport, no access to "
            + "the tenant's dbo — so the same bundle runs inside the platform's container, "
            + "on a separate machine, or in a pod scaled per step, stateless over the "
            + "tenants whose lanes it is handed."),

    PROC_FAILURE_IS_RELEASED("A failing or throwing step service releases the run with the "
            + "reason — never closed, never lost — and a later cycle may take it again."),

    PROC_RUNNER_DECLARES_ITS_VITALS("The runner re-declares each service with an extensible "
            + "metadata block, replaced never accumulated; presence stays derived from the "
            + "cursor, and vitals annotate it."),

    /** TODO: prove it in a test. No single test walks catalogue → CodeSystem/PlanDefinition
     * → "never hand-edited" end to end; the projection generator itself has no negative
     * test that a hand-edit would be overwritten or refused. */
    PROC_CATALOGUE_IN_STORE("Process and step definitions (with profiles, planes and "
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

    PROC_CLOSED_CAN_BE_REOPENED("A closed run can be reopened — a deliberate, "
            + "recorded act through the step's declared reopen action — making the run "
            + "claimable again with the reason on the record, instead of a second run "
            + "invented to disagree with the first."),

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
    PROC_DOMAIN_CODE_FILTER("Every process and step carries a free-string process-domain "
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

    PROC_RUN_SAYS_WHO_HOLDS_IT("A run's load-bearing field is who holds it now: "
            + "automation running, automation with a retry scheduled, a person, or "
            + "nobody. Every other field answers a question somebody asks after that "
            + "one."),

    PROC_RUN_TALLY_AND_ITEM_OUTCOMES("A run over N items where K fail records one run "
            + "with a tally and K item outcomes, and does not abandon the remaining "
            + "N minus K."),

    PROC_ESCALATION_BY_FAILURE_CLASS("A record that is wrong reaches a person; a store "
            + "that is unavailable is a retry and nobody's card. Only record-class "
            + "failures make work, or the queue becomes a graveyard and stops being "
            + "read."),

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
    PROC_ONE_PARENT_NEVER_ACROSS_A_BOUNDARY("A run has at most one parent, and "
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

    PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT("A run's envelope carries holder, "
            + "step, state and counts — never item references or messages. The "
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

    PROC_FALL_THROUGH_IS_COUNTABLE("Work no executor took is held by a person and "
            + "counted per step and per zone. That number is the automation backlog "
            + "stated as a fact rather than an opinion."),

    PROC_EXECUTOR_DECLARES_ITSELF("A participant announces process, step, scope, "
            + "version and provider as a record in the tenant's store, and resolution "
            + "walks those declarations rather than the bundles installed in one "
            + "container. A candidate that can only come from a local bundle makes a "
            + "tenant a single machine."),

    PROC_A_TRACKABLE_MAY_ROUTE_OTHERS("Something whose state is worth knowing is one "
            + "record at any depth, and a connected worker may route others: it reports the "
            + "state of what sits behind it, to arbitrary depth, normalised per trackable so "
            + "the rule exists once rather than once per router. Presence stays derived where "
            + "there is a cursor and is attested where there is not, the attestation naming "
            + "the worker that saw it rather than the parent it sits behind. The store "
            + "imposes no freshness rule on what a router reports: it has no path of its own "
            + "to ask, and one threshold across a serial line and a socket would be wrong for "
            + "both."),

    PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB("A participant reports what it can reach "
            + "the way it reports what it can do: a verb of the participation lane, beside "
            + "declare. The observer is stamped from the lane's own participant rather than "
            + "carried on the wire, so a router cannot attest as somebody else. Vitals do "
            + "not carry it, because vitals ride a declaration and a declaration is keyed "
            + "per step — a router declaring two steps would carry one fleet twice, and "
            + "withdrawing either would drop half of it."),

    PROC_PRESENCE_IS_DERIVED("A participant is present while its named feed cursor "
            + "moves; a declaration whose consumer is behind and unmoving is "
            + "declared-but-not-present, skipped by resolution and shown as such. No "
            + "heartbeat and no lease — and a caught-up participant's cursor does not "
            + "move either, so silence with nothing waiting is not absence."),

    PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE("What a peer sends applies once however "
            + "often it is sent, and a batch arriving behind a newer one does not put "
            + "the older version back. The comparison is the source version, so neither "
            + "property depends on the transport being careful."),

    PROC_LANE_EPOCH("A lane carries an epoch, and a peer resuming a cursor issued by "
            + "another lane instance is refused rather than replayed — an appliance "
            + "restored from a copy looks healthy while resuming a position that no "
            + "longer means anything."),

    PROC_LANE_IS_A_TENANT_SERVICE("A tenant's replication lane stands in the service "
            + "registry beside its store, so a bundle in the same framework takes the "
            + "one the tenant's own door serves rather than assembling a second set of "
            + "cursors for the same peer or re-entering over loopback with a credential. "
            + "It stands there for every tenant: a lane needs no authority, because the "
            + "registry never asks who is calling."),

    // ── sealed work — decided in review, nothing built; see docs/tasks ──

    PROC_WORK_TRAVELS_SEALED(
            "Work travels in two parts. The manifest — tenant, step, the task, and "
            + "references to the documents named — is readable, because routing on it "
            + "is its job. The payload — the documents themselves — is sealed in the "
            + "carrier form under a data key of its own, wrapped once per participant "
            + "meant to open it and to nobody who merely carries it. A sealed payload is "
            + "a copy in flight and not the record: the store keeps the original, and the "
            + "copy is bounded by the work that caused it."),
    PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT(
            "A participant generates its keypair before it is enrolled and offers the "
            + "public half as part of enrolling; the private half never crosses. Payload "
            + "data keys are wrapped to that key, so what a participant may open is "
            + "decided by what it holds rather than by what it is told."),
    PROC_THE_ROUTER_HOLDS_THE_CLAIM(
            "The thing that can reach the store is the participant, and it holds the "
            + "claim. An instrument behind a router is routed because it cannot reach the "
            + "lane, so the router claims, forwards, waits and reports — holding a claim on "
            + "work it cannot read — while the instrument holds the key and does the work. "
            + "Participant versus routee is a fact about the attachment, not the device."),
    PROC_DONE_MEANS_DONE(
            "A participant does not report done before the work is done. A run closes on "
            + "what is reported and the store has no view below that seam, so an early "
            + "report is a true-looking record of something that has not happened. A "
            + "participant with durable execution underneath waits for it; a router waits "
            + "for its edge; a wedged one lets the claim lapse and the run reads released."),
    PROC_A_LANE_OVER_THE_STREAM(
            "A lane runs over the store's own stream, full duplex, beside in-process and "
            + "HTTP: work goes out and travel, access and result events come home as they "
            + "happen on the same channel. It serves exactly the verbs the other two do, "
            + "and a runner cannot tell which it holds."),
    PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT(
            "A routee missing from a router's report is something the router said, not a "
            + "gap — distinguishable from a quiet router because the cursor moved. A "
            + "departed routee is kept with its last attestation and marked no longer "
            + "reported, so gone reads as last seen by X at T, absent from X's report at "
            + "T+1. No freshness rule comes with it."),

    PROC_THE_LANE_HAS_TWO_BOUNDS(
            "What moves between two appliances of one tenant has two bounds, deliberately "
            + "different: declarations by type — the tenant's own definitions, none of it "
            + "about anybody — which travel as every version of the types asked for since "
            + "the peer's position, filed under their source, read-only there, shadowed by a "
            + "local override and never revoked by work; and patient data by work, which "
            + "arrives with a task and leaves with it. What a run produced travels with the "
            + "run as a copy that outlives it. A type the lane does not admit is refused by "
            + "name, never quietly left out."),
    PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY("A record travels to an appliance because a "
            + "piece of work names it, and is removed when no open run there still "
            + "names it. Work-driven arrival without work-driven expiry is a bench "
            + "accumulating a register one task at a time."),

    PROC_AUDIT_REPLICATES_AS_RECORDED("An appliance's audit entries reach its peer as "
            + "that appliance recorded them — original actor, original time, and the "
            + "appliance named — and the arrival writes no second trail. Direct writes to "
            + "the audit type stay refused for every caller; the replication lane is "
            + "admitted through one narrow port that can express no other write, and a "
            + "re-delivered entry lands exactly once under the source's own identity."),

    PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE("A run arriving from another appliance "
            + "of the same tenant is stored under that appliance, beside the local run "
            + "of the same key rather than on top of it."),

    PROC_CONTENT_CHANGES_INSIDE_WORK("A type may declare that every change to it "
            + "belongs to a run; a write with no run in scope is refused, naming the "
            + "rule. A change that belongs to nothing is visible and unexplainable — "
            + "history has it and audit names who, and nobody can say what it was "
            + "for."),

    PROC_A_RUN_NAMES_WHAT_IT_PRODUCED("A run records the versions it produced, "
            + "individually up to a cap and as a per-type high-water mark past it, and "
            + "says which of the two it is. Reading runs in order then reads the "
            + "content changes in order, so another appliance asks for what it is "
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
            + "installed in it and the steps a linked participant introduced, each with the "
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
    TEN_ERASURE_BY_DROP(
            "Dropping a tenant's database and blob storage removes all its data — "
            + "including durable workflow history and feed state."),
    /** TODO: prove it in a test. */
    TEN_SHARED_TIER_ISOLATION(
            "Tenants on the shared tier are isolated by tenant-keyed schemas and "
            + "row-level security with the same API surface as the dedicated tier."),
    /** TODO: prove it in a test. */
    TEN_FAIRNESS_QUOTAS(
            "Per-tenant quotas and rate limits are first-class configuration, enforced "
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
            "The raw store surface is never publicly routed; public interaction with "
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
            "A local credential holds factors named by kind (RFC 8176 `amr`): a bench "
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
            + "be a second way in that never reaches the identity provider. A bench PIN "
            + "is untouched, before and after — it serves the case federation cannot, "
            + "and taking it away would remove the fallback for the situation the rule "
            + "was written around."),
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
            + "definitions the toolchain used, and the answer changes nothing: the verdict a "
            + "caller receives is the toolchain's. What is kept is a tally — the two agreed, "
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
            + "and name no FHIR version, so one set of them serves every face."),
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

    IDN_IDENTIFICATION_IS_REACHABLE(
            "A tenant identifies somebody through a door of its own: claims are presented "
            + "and resolve to candidates rather than to an answer, a claim nobody verified "
            + "is evidence for a person to weigh and never a match to act on, no candidate "
            + "at all is an ordinary answer rather than a failure, a decision is recorded "
            + "and shown to whoever meets the same near-match next, a binding says how "
            + "strongly it was made and can be withdrawn without touching the care, and a "
            + "subject who declared anonymity is refused rather than bound. The door "
            + "carries its own scope, outside the resource grammar: a grant over the "
            + "store's resources does not reach the act that de-anonymises somebody."),

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

    // ── FEED — migrated from hand-written prose (2026-08-27) ──

    FEED_ONE_PRIMITIVE(
            "Pagination, subscription delivery, content streams and edge sync are all "
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
            "Between DBO-speaking parties, feeds stream lean frames; FHIR Bundles are "
            + "assembled only at the FHIR surface."),

    // ── EVT — migrated from hand-written prose (2026-08-27) ──

    EVT_TRANSACTIONAL_OUTBOX(
            "Every change event originates as an outbox row committed with the write. "
            + "(R8, §6)"),
    EVT_FHIR_SUBSCRIPTIONS(
            "Topic-based FHIR Subscriptions (R5/R6 style, backported to the R4 "
            + "personality) are a core capability. (R8)"),
    EVT_DURABLE_DELIVERY(
            "Subscription delivery is durable, tenant-scoped and replayable, with "
            + "retries, backoff and dead-lettering. (R8, §9)"),
    EVT_IN_PROCESS_SURFACE(
            "Co-located consumers get the same topics with identical semantics through "
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
            "Every cross-plane or cross-tenant hop is coordinated by the platform; no "
            + "direct tenant-to-tenant connection exists."),
    WF_HOPS_AUDITED(
            "Every hop leaves a travel entry about the task — who handed to whom — and a "
            + "travel entry is not a reading: audit of the journey is structural, not "
            + "per-integration, and it never says anybody looked at the content."),

    // ── SCAL — migrated from hand-written prose (2026-08-27) ──

    /** TODO: prove it in a test. */
    SCAL_DURABLE_ASSIGNMENT(
            "The tenant→pod assignment is durable state with version-driven takeover."),
    /** TODO: prove it in a test. */
    SCAL_SINGLE_WRITER_TENANT(
            "A tenant's serving pod is its single writer, making local caching and "
            + "local subscription state correct by construction."),
    /** TODO: prove it in a test. */
    SCAL_TRANSPARENT_ROUTING(
            "Callers look up a tenant's service in the registry; local instance or "
            + "remote proxy is indistinguishable."),
    /** TODO: prove it in a test. */
    SCAL_TWO_HOP_LOCALITY(
            "Requests enter at the closest public node (Kubernetes locality), then "
            + "route to the serving pod (tenant assignment)."),
    /** TODO: prove it in a test. */
    SCAL_NO_SHARED_STATE_BROKER(
            "The architecture requires no Redis-class shared-state service."),

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

    /** TODO: prove it in a test. */
    OPS_TENANT_BLOB_STORAGE(
            "Binary content lives in per-tenant blob storage provisioned "
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
            "Schema and engine upgrades ride rolling deployment: the highest-version "
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
