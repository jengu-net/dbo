package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Feature;
import cloud.jengu.dbo.promise.Promise;

import java.util.List;

/** The functional views the pilot classifies. */
@Catalogue(namespace = "FEAT-DBO")
public enum DboFeatures implements Feature {

    SHAPE_VERSIONING("The store knows the shape that wrote an object: stamped at accept, "
            + "queryable, per-version in history, carried on the sync wire.",
            List.of(DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
                    DboPromises.SHAPE_STAMP_IS_DERIVED,
                    DboPromises.SHAPE_SERVED_BESIDE_THE_CLAIM,
                    DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP,
                    DboPromises.SHAPE_QUERYABLE_BY_VERSION,
                    DboPromises.SHAPE_STOCK_COUNTED,
                    DboPromises.SHAPE_UNPARSEABLE_VERSION_REFUSED,
                    DboPromises.SHAPE_RESHAPED_IN_PLACE,
                    DboPromises.SHAPE_RESHAPE_RESUMABLE,
                    DboPromises.SHAPE_REFUSED_OBJECT_LEFT_BEHIND,
                    // The gap this feature declared while the catalogue was
                    // being written, now stated: #133 answered it, so it is a
                    // named promise rather than a hole.
                    DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK,
                    DboPromises.SHAPE_NEWER_DATA_REFUSED,
                    DboPromises.SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER,
                    DboPromises.SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING,
                    DboPromises.SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE)),

    EXACT_IDENTIFIER_RESOLUTION("A known identifier finds the record that claims it, "
            + "without the membrane learning to talk.",
            List.of(DboPromises.PDI_EXACT_RESOLUTION)),

    WORK_ARRIVES_WHOLE("Work is the manifest: the step declares what it consumes as "
            + "named slots, the run fills them at creation, the projection and the lane "
            + "carry them — a runner never reaches into the store for what the work is "
            + "about.",
            List.of(DboPromises.PROC_STEP_DECLARES_ITS_SLOTS,
                    DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS,
                    DboPromises.PROC_TASK_CARRIES_THE_INPUTS,
                    DboPromises.PROC_INPUTS_ARRIVE_WITH_THE_WORK)),

    WORK_SAYS_WHERE_IT_IS("A long-running step is visible between claim and outcome: "
            + "milestones ride the checkpoint the way events ride a tracing span, and "
            + "completeness is derived from the step's own declared order.",
            List.of(DboPromises.PROC_MILESTONES_ARE_DECLARED,
                    DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE,
                    DboPromises.PROC_TASK_SAYS_WHERE_THE_WORK_IS)),

    THE_CATALOGUE_LEARNS("The catalogue is built up, not ported: installed modules "
            + "contribute by being installed, and linked participants introduce the "
            + "steps they bring — one collision rule across both doors, and nothing "
            + "granted by walking through either.",
            List.of(DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
                    DboPromises.PROC_ONE_ID_ONE_DEFINITION,
                    DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING)),

    WORK_REACHES_ONLY_ITS_HOLDER("A participant sees and takes the work of the steps it "
            + "holds, and no other — bounded by its own credential on one side and by "
            + "what the step admits on the other.",
            List.of(DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
                    DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED)),

    // ── migrated from hand-written prose (2026-08-27) ──────────────────

    THE_RUNNER_CARRIES_WORK("An embeddable runner performs a declared step, releases "
            + "what it fails, and re-declares itself with its own vitals — the whole "
            + "surface a step needs to be run by something.",
            List.of(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
                    DboPromises.PROC_FAILURE_IS_RELEASED,
                    DboPromises.PROC_RUNNER_SIGNS_ITS_VITALS)),

    THE_CATALOGUE_IS_THE_STORES_OWN("Process and step definitions are DBO's own "
            + "vocabulary, declared once and referenced everywhere: shape validation, "
            + "the run's own version, and the tenant's mandatory list all read the same "
            + "declaration.",
            List.of(DboPromises.PROC_CATALOGUE_IN_STORE,
                    DboPromises.PROC_STEP_DECLARES_ITSELF,
                    DboPromises.PROC_RUN_NAMES_THE_STEP_VERSION,
                    DboPromises.PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS,
                    DboPromises.PROC_STEP_SHAPE_VALIDATION,
                    DboPromises.PROC_DOMAIN_CODE_FILTER)),

    A_REPORT_OBEYS_THE_DECLARATION("Reporting is not an arbitrary write: it lands "
            + "through the step's declared actions, and a closed run reopens only "
            + "through a declared act of its own.",
            List.of(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS,
                    DboPromises.PROC_CLOSED_CAN_BE_REOPENED)),

    A_RUN_IS_A_COMPLETE_ACCOUNT("A run is a first-class, queryable record of what "
            + "happened: who holds it, what it produced, why it escalated, how it "
            + "closes, and what it is one of.",
            List.of(DboPromises.PROC_RUN_HAS_A_RECORD,
                    DboPromises.PROC_RUN_SAYS_WHO_HOLDS_IT,
                    DboPromises.PROC_RUN_TALLY_AND_ITEM_OUTCOMES,
                    DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS,
                    DboPromises.PROC_CLOSE_BY_RE_EVALUATION,
                    DboPromises.PROC_RUN_KINDS,
                    DboPromises.PROC_ONE_PARENT_NEVER_ACROSS_A_BOUNDARY,
                    DboPromises.PROC_CORRELATION_TRAVELS_OPAQUE,
                    DboPromises.PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT)),

    RESOLUTION_IS_DETERMINISTIC_AND_DECLARED("Which executor takes a step is walked, "
            + "never raced, over a chain whose overrides and switches are themselves "
            + "declared configuration — so a decision is reproducible and its backlog "
            + "is a fact rather than an opinion.",
            List.of(DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC,
                    DboPromises.PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE,
                    DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
                    DboPromises.PROC_AUTOMATION_IS_DECLARED,
                    DboPromises.PROC_FALL_THROUGH_IS_COUNTABLE,
                    DboPromises.PROC_EXECUTOR_DECLARES_ITSELF,
                    DboPromises.PROC_PRESENCE_IS_DERIVED)),

    A_PEER_CONVERGES_SAFELY("Two appliances of one tenant reconcile without a lease: "
            + "applies are replay- and reorder-safe, a stale cursor is refused by its "
            + "epoch, and what arrives is filed under its own appliance, driven by the "
            + "work that named it and withdrawn when that work closes.",
            List.of(DboPromises.PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE,
                    DboPromises.PROC_LANE_EPOCH,
                    DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY,
                    DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE)),

    CONTENT_ONLY_CHANGES_UNDER_A_RUN("A declared type's content changes only inside a "
            + "run, which is what turns the run into the manifest: every version it "
            + "produced is on the record, so another appliance asks for what it is "
            + "missing instead of comparing stores.",
            List.of(DboPromises.PROC_CONTENT_CHANGES_INSIDE_WORK,
                    DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED)),

    THE_NETWORK_AND_ITS_TRACE_ARE_READABLE("What is running, where, and what it did "
            + "are answerable questions: the network map for presence, the trace join "
            + "for provenance.",
            List.of(DboPromises.PROC_NETWORK_MAP, DboPromises.PROC_TRACE_JOIN));

    private final String title;
    private final List<Promise> promises;

    DboFeatures(String title, List<Promise> promises) {
        this.title = title;
        this.promises = promises;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<Promise> promises() {
        return promises;
    }
}
