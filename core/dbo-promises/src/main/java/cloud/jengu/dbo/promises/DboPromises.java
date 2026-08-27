package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Promise;

/**
 * The store's promise catalogue — the pilot carries the SHAPE and PDI areas
 * (#140); the remaining areas migrate table by table as the model proves
 * itself. The constant's name IS the code, prefixed by the namespace, so a
 * citation cannot drift from a declaration.
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

    // ── PROC — distributed work; the first slice beyond the pilot (#149) ──

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

    PROC_RUNNER_SIGNS_ITS_VITALS("The runner re-declares each service with an extensible "
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
    PROC_RUN_HAS_A_RECORD("Every run of a step is a record in a tenant's own store — a "
            + "registered type, so it is envelope-queryable, versioned, carried by the "
            + "backup and dropped with the tenant. A run in a private table has none of "
            + "those, and cannot be seen or acted on."),

    /** TODO: prove it in a test. Holder.java's javadoc cites this REQ, but a javadoc
     * citation is not a proof site — no test isolates "holder is the field read first"
     * as its own claim distinct from the rendering tests. */
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

    PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY("A record travels to an appliance because a "
            + "piece of work names it, and is removed when no open run there still "
            + "names it. Work-driven arrival without work-driven expiry is a bench "
            + "accumulating a register one task at a time."),

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

    /** TODO: prove it in a test. The console (#75) is what would answer this; nothing
     * yet scans bundles for process/step declarations and accumulates them across nodes. */
    PROC_NETWORK_MAP("The network answers which processes are known and running, "
            + "where and in which version — scanned from bundles and accumulated "
            + "across nodes."),

    PROC_TRACE_JOIN("From any process instance, the steps and the exact resource "
            + "diffs and audit records they produced are navigable.");

    private final String text;

    DboPromises(String text) {
        this.text = text;
    }

    @Override
    public String text() {
        return text;
    }
}
