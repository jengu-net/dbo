package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Promise;
import cloud.jengu.dbo.promise.Story;

import java.util.List;

/**
 * The store's user stories, as constants beside the promises they rest on.
 *
 * <p>A story is a scene told from the builder's side, and its joins — the
 * promises each leg of the journey depends on — are declared here rather
 * than written into the story by hand. So a story cannot cite a promise that
 * does not exist (it would not compile), cannot claim a leg nothing promises,
 * and reads unproven while every leg it rests on is only planned: coverage
 * is the fold of its promises' statuses, exactly as a feature's is. The
 * markdown at {@code docs/arc42-003-context/user-stories/<code>.md} tells
 * the scene; its joins table is projected from this constant.
 *
 * <p>A story claims no evidence. What it declares is what it leans on; what
 * is proven is what the promises' own citations say.
 */
@Catalogue(namespace = "US-DBO")
public enum DboStories implements Story {

    EDGE_ROUNDTRIP("One synchronisation service serves every tenant's devices, and reads a "
            + "payload only when it has to.",
            List.of(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
                    DboPromises.TEN_STRUCTURAL_SCOPING,
                    DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                    DboPromises.PROC_TASK_CARRIES_THE_INPUTS,
                    DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED,
                    DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
                    DboPromises.PROC_FAILURE_IS_RELEASED,
                    DboPromises.POL_AUDIT_AS_RECORDS,
                    DboPromises.POL_ACTOR_FROM_AUTHORITY,
                    DboPromises.PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT,
                    DboPromises.POL_CUSTOM_AUDIT_EVENTS,
                    DboPromises.CORE_VERSIONED_HISTORY,
                    DboPromises.PROC_WORK_TRAVELS_SEALED,
                    DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                    DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                    DboPromises.WF_HOPS_AUDITED,
                    DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                    DboPromises.PROC_A_LANE_OVER_THE_STREAM,
                    DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                    DboPromises.PROC_DONE_MEANS_DONE,
                    DboPromises.WF_TWO_PLANES,
                    DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE)),

    FLEET_HEALTH("A tree of participants reporting one hop each, presence derived rather "
            + "than declared, read from outside the container.",
            List.of(DboPromises.PROC_STEP_DECLARES_ITSELF,
                    DboPromises.PROC_RUNNER_DECLARES_ITS_VITALS,
                    DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS,
                    DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB,
                    DboPromises.PROC_PRESENCE_IS_DERIVED,
                    DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT)),

    PARTNER_TRACKING("A partner follows work through the tenants it manages, without being "
            + "able to read it.",
            List.of(DboPromises.PROC_RUN_HAS_A_RECORD,
                    DboPromises.PROC_RUN_SAYS_WHO_HOLDS_IT,
                    DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
                    DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE,
                    DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED,
                    DboPromises.PROC_FAILURE_IS_RELEASED,
                    DboPromises.PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT,
                    DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED,
                    DboPromises.POL_AUDIT_AS_RECORDS,
                    DboPromises.POL_ACTOR_FROM_AUTHORITY,
                    DboPromises.POL_CUSTOM_AUDIT_EVENTS,
                    DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                    DboPromises.WF_HOPS_AUDITED,
                    DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                    DboPromises.TEN_STRUCTURAL_SCOPING)),

    TRAIL_ANSWERS("Answering did anybody read this with nobody rather than no record: travel "
            + "and access as different kinds, and a chain that makes absence mean something.",
            List.of(DboPromises.POL_AUDIT_AS_RECORDS,
                    DboPromises.POL_ACTOR_FROM_AUTHORITY,
                    DboPromises.POL_CUSTOM_AUDIT_EVENTS,
                    DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY,
                    DboPromises.CORE_VERSIONED_HISTORY,
                    DboPromises.PROC_FAILURE_IS_RELEASED,
                    DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
                    DboPromises.POL_POLICY_REPLAY_ON_RESTORE,
                    DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                    DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK));

    private final String title;
    private final List<Promise> promises;

    DboStories(String title, List<Promise> promises) {
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
