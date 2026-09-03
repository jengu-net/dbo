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

    EDGE_ROUNDTRIP("Work leaves the tenant that authored it and comes back: a step declared "
            + "by whoever performs it, a task authored on the surface, claimed over a lane by "
            + "one shared service that reads a payload only when it has to.",
            List.of(
                    // A step is declared by whoever performs it, and arrives
                    // in a tenant nobody installed it in.
                    DboPromises.PROC_STEP_DECLARES_ITSELF,
                    DboPromises.PROC_STEP_DECLARES_ITS_SLOTS,
                    DboPromises.PROC_MILESTONES_ARE_DECLARED,
                    DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
                    DboPromises.PROC_ONE_ID_ONE_DEFINITION,
                    DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING,
                    // Work is authored on the tenant's own surface, never on
                    // the lane, and becomes a record.
                    DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE,
                    DboPromises.PROC_TASK_CARRIES_THE_INPUTS,
                    DboPromises.PROC_TASK_SAYS_WHERE_THE_WORK_IS,
                    DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS,
                    DboPromises.PROC_RUN_HAS_A_RECORD,
                    DboPromises.PROC_RUN_KINDS,
                    DboPromises.PROC_STEP_SHAPE_VALIDATION,
                    DboPromises.PROC_CONTENT_CHANGES_INSIDE_WORK,
                    // One service holds a lane per tenant and cannot tell
                    // where the store is.
                    DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                    DboPromises.PROC_LANE_IS_A_TENANT_SERVICE,
                    DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
                    DboPromises.PROC_A_LANE_OVER_THE_STREAM,
                    DboPromises.WF_POSTGRES_SUBSTRATE,
                    DboPromises.WF_TWO_PLANES,
                    DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE,
                    // What it may take is the intersection of its credential
                    // and what the step admits.
                    DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
                    DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED,
                    DboPromises.PROC_EXECUTOR_DECLARES_ITSELF,
                    DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC,
                    DboPromises.PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE,
                    DboPromises.PROC_AUTOMATION_IS_DECLARED,
                    DboPromises.PROC_FALL_THROUGH_IS_COUNTABLE,
                    DboPromises.PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS,
                    // Performing it, and saying so honestly.
                    DboPromises.PROC_INPUTS_ARRIVE_WITH_THE_WORK,
                    DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE,
                    DboPromises.PROC_RUN_SAYS_WHO_HOLDS_IT,
                    DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
                    DboPromises.PROC_RUN_NAMES_THE_STEP_VERSION,
                    DboPromises.PROC_RUN_TALLY_AND_ITEM_OUTCOMES,
                    DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS,
                    DboPromises.PROC_FAILURE_IS_RELEASED,
                    DboPromises.PROC_DONE_MEANS_DONE,
                    DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED,
                    DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS,
                    DboPromises.PROC_CLOSE_BY_RE_EVALUATION,
                    DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED,
                    // Carried without being read, and the trail telling the
                    // two apart afterwards.
                    DboPromises.PROC_WORK_TRAVELS_SEALED,
                    DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                    DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                    DboPromises.PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT,
                    DboPromises.PROC_CORRELATION_TRAVELS_OPAQUE,
                    DboPromises.PROC_TRACE_RIDES_THE_LANE,
                    DboPromises.PROC_TRACE_JOIN,
                    DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                    DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                    DboPromises.WF_HOPS_AUDITED)),

    FLEET_HEALTH("An operator reads a whole deployment from outside every container — what "
            + "each node serves and knows how to do, who is present, what sits behind them — "
            + "and steers it through the same door a participant would use.",
            List.of(
                    // What a node will say about itself, under the
                    // deployment's own token rather than a tenant's.
                    DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES,
                    DboPromises.PROC_A_NODE_ANSWERS_ITS_CATALOGUE,
                    DboPromises.PROC_NETWORK_MAP,
                    // One process outside every container, reading and acting.
                    DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE,
                    DboPromises.OPS_FLEET_IS_ACTED_ON_THROUGH_THE_LANE,
                    // Who is out there, derived rather than declared.
                    DboPromises.PROC_RUNNER_DECLARES_ITS_VITALS,
                    DboPromises.PROC_PRESENCE_IS_DERIVED,
                    DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS,
                    DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB,
                    DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT,
                    // Trends, which are a different question from state.
                    DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT,
                    DboPromises.PROC_REPORTING_RUNS_WHERE_NOTHING_COLLECTS,
                    DboPromises.OPS_NUMBERS_LEAVE_THE_NODE,
                    // And undoing a judgement, which is its own authority.
                    DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
                    DboPromises.PROC_CLOSED_CAN_BE_REOPENED,
                    DboPromises.TEN_A_PARTNER_MANAGES_TENANTS)),

    TENANT_OPENING("A tenant is stood up inside somebody else's JVM, with a database and an "
            + "authority of its own, and the people who will work in it get in.",
            List.of(
                    // The store arrives as a library in a JVM the builder owns,
                    // and the tenant's services appear when its spec does.
                    DboPromises.CONT_EMBEDDED_IN_JVM,
                    DboPromises.CONT_FRAMEWORK_FREE_CORE,
                    DboPromises.CONT_PRIVATE_DEPENDENCIES,
                    DboPromises.CONT_IMPORTS_ARE_COMPUTED_OR_CHECKED,
                    DboPromises.CONT_DYNAMIC_TENANT_SERVICES,
                    DboPromises.CONT_FAST_COLD_START,
                    // What a tenant is: a database, provisioned without this
                    // code ever holding the credential, reachable only as itself.
                    DboPromises.TEN_DEDICATED_DATABASE_TIER,
                    DboPromises.TEN_CREDENTIAL_BLIND_PROVISIONING,
                    DboPromises.TEN_STRUCTURAL_SCOPING,
                    DboPromises.TEN_REGISTRY_SCOPED_ACCESS,
                    DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    // Its own authority, and nothing reachable without it.
                    DboPromises.AUTH_TENANT_SCOPED_ISSUER,
                    DboPromises.AUTH_PORTABLE_AUTHORITY,
                    DboPromises.AUTH_IDENTITY_AS_RECORDS,
                    DboPromises.AUTH_DENY_BY_DEFAULT,
                    DboPromises.AUTH_BEARER_LOCAL_VALIDATION,
                    DboPromises.AUTH_SMART_SHAPED_SCOPES,
                    DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS,
                    // The staff directory drives who exists.
                    DboPromises.SCIM_DECLARED_PER_TENANT,
                    DboPromises.SCIM_DIRECTORY_CREDENTIAL,
                    DboPromises.SCIM_USER_IS_THE_PERSON,
                    DboPromises.SCIM_ENUMERATION_STAYS_INSIDE,
                    DboPromises.SCIM_EVERY_OP_IS_A_DISCLOSURE,
                    DboPromises.SCIM_GROUPS_READ_ONLY,
                    // And what each of them may do, as records rather than code.
                    DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                    DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS,
                    DboPromises.AUTH_CREDENTIAL_FACTORS_BY_KIND,
                    DboPromises.AUTH_PASSWORD_ONLY_WHERE_WE_ARE_THE_IDP,
                    DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT,
                    DboPromises.AUTH_SELF_SERVICE_CHANGE,
                    DboPromises.AUTH_RECOVERY_IS_AN_OPERATOR_ACT,
                    DboPromises.AUTH_DEACTIVATION_RETIRES_CREDENTIALS)),

    CLINICAL_RECORD("Care is recorded in the tenant opened before it, and can be found again "
            + "and accounted for: one patient however many times they arrive, meaning checked "
            + "against the clinic's own terminology, and a trail nobody can edit.",
            List.of(
                    // What a record is, and what makes two writes one patient.
                    DboPromises.CORE_PAYLOAD_IS_TRUTH,
                    DboPromises.CORE_DECLARED_TRUTH_FORM,
                    DboPromises.CORE_DECLARED_IDENTITY,
                    DboPromises.CORE_EXTERNAL_IDENTIFIERS,
                    DboPromises.CORE_NO_IMPLICIT_MERGE,
                    DboPromises.CORE_CONDITIONAL_UPSERT,
                    DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS,
                    DboPromises.CORE_READ_YOUR_WRITES,
                    DboPromises.CORE_VERSIONED_HISTORY,
                    DboPromises.CORE_PARAMETERIZED_SQL,
                    DboPromises.CORE_SIBLING_MODELS,
                    // A visit arrives as one document and lands whole, or not at all.
                    DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE,
                    DboPromises.CORE_CONDITIONAL_REFERENCES,
                    DboPromises.CORE_REFERENCE_EDGES,
                    DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY,
                    // The face decides what the bytes mean; the engine does not.
                    DboPromises.VER_VERSION_AGNOSTIC_CORE,
                    DboPromises.VER_PERSONALITY_OWNS_MEANING,
                    DboPromises.VER_SPECIFIED_VALIDATION,
                    DboPromises.VER_VALIDATION_WITHOUT_WRITING,
                    DboPromises.VER_ONE_READ_PER_REQUEST,
                    // A coded value is checked against this clinic's terminology.
                    DboPromises.TERM_NATIVE_FORM,
                    DboPromises.TERM_BULK_LOAD,
                    DboPromises.TERM_EVERY_TENANT_ANSWERS,
                    DboPromises.TERM_OPERATIONS_FROM_NATIVE_FORM,
                    DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY,
                    DboPromises.VAL_BINDING_STRENGTH_IS_THE_ANSWER,
                    DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID,
                    // Finding it again, with the store saying what it can do.
                    DboPromises.SRCH_TIER1_PARITY,
                    DboPromises.SRCH_STRICT_BY_DEFAULT,
                    DboPromises.SRCH_HONEST_CAPABILITY,
                    DboPromises.SRCH_TYPED_ORDERING,
                    DboPromises.SRCH_DECLARED_INDEXES,
                    DboPromises.SRCH_CUSTOM_PARAMETERS,
                    // And accounting for all of it afterwards.
                    DboPromises.POL_DECLARED_AT_CONFIGURATION,
                    DboPromises.POL_AUDIT_AS_RECORDS,
                    DboPromises.POL_ACTOR_FROM_AUTHORITY,
                    DboPromises.POL_APPEND_ONLY_DISCIPLINE,
                    DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY,
                    DboPromises.POL_CUSTOM_AUDIT_EVENTS,
                    DboPromises.POL_FHIR_AUDIT_PROJECTION,
                    DboPromises.POL_DECLARATIVE_RETENTION,
                    DboPromises.POL_RETENTION_SWEEP,
                    // One change feed under everything that watches.
                    DboPromises.FEED_ONE_PRIMITIVE,
                    DboPromises.FEED_KEYSET_CURSORS,
                    DboPromises.FEED_NAMED_CONSUMERS,
                    DboPromises.EVT_TRANSACTIONAL_OUTBOX,
                    DboPromises.EVT_FHIR_SUBSCRIPTIONS,
                    DboPromises.EVT_DURABLE_DELIVERY,
                    DboPromises.EVT_IN_PROCESS_SURFACE)),

    PERSON_RIGHTS("What a person can ask for about the care recorded in the story before "
            + "this one, and what erasure means when identifying data was encrypted under a "
            + "key of their own before it ever reached the engine.",
            List.of(
                    // The membrane the record was written through.
                    DboPromises.PDI_STRUCTURAL_VAULT,
                    DboPromises.PDI_BLIND_OPERATIONS,
                    DboPromises.PDI_EXACT_RESOLUTION,
                    // Who they are, decided rather than guessed.
                    DboPromises.IDN_IDENTIFICATION_IS_REACHABLE,
                    DboPromises.IDN_A_DECISION_IS_EVIDENCE,
                    DboPromises.IDN_CLAIM_STRENGTH_BOUNDS_THE_CONCLUSION,
                    DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO,
                    DboPromises.IDN_ANONYMITY_IS_DECLARED_NOT_INFERRED,
                    DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE,
                    DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED,
                    // How a human gets in, and what their token says about them.
                    DboPromises.AUTH_FEDERATED_HUMANS,
                    DboPromises.AUTH_PSEUDONYMOUS_TOKENS,
                    DboPromises.AUTH_ON_BEHALF_OF,
                    DboPromises.AUTH_NO_SUBJECT_ENUMERATION,
                    // And what being forgotten actually does.
                    DboPromises.PDI_RIGHTS_AS_OPERATIONS,
                    DboPromises.PDI_ERASURE_IS_A_RUN,
                    DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT,
                    DboPromises.PDI_CRYPTO_SHREDDING,
                    DboPromises.PDI_UNFINDABLE_AFTER_ERASURE,
                    DboPromises.PDI_SHRED_LEDGER,
                    DboPromises.POL_ERASURE_COMPATIBLE,
                    DboPromises.SCIM_DEPROVISION_IS_A_STATE,
                    DboPromises.TEN_ERASURE_BY_DROP)),

    TWO_PLACES("One tenant in two places, and content that belongs somewhere else: canonical "
            + "definitions travelling by type because none of them is about anybody, and "
            + "patient data travelling by work because all of it is.",
            List.of(
                    // Declared content from an upstream this tenant does not run.
                    DboPromises.SYNC_SPEC_DECLARED,
                    DboPromises.SYNC_DECLARED_ONLY,
                    DboPromises.SYNC_DIRECT_UPSTREAM_ONLY,
                    DboPromises.SYNC_ANY_TYPE,
                    DboPromises.SYNC_FULL_HISTORY_CATCH_UP,
                    DboPromises.SYNC_PROVENANCE_COPIES,
                    DboPromises.SYNC_LOCAL_SHADOWING,
                    DboPromises.SYNC_CONVERT_ON_APPLY,
                    DboPromises.SYNC_TERMINOLOGY_GRAIN_SURVIVES,
                    DboPromises.ZONE_DECLARATIONS_AS_RECORDS,
                    DboPromises.ZONE_SUBJECT_DOMAINS,
                    DboPromises.ZONE_BROKER_CHOICE,
                    DboPromises.ZONE_SESSIONS_ACCUMULATE,
                    // And the appliance: the other bound, deliberately different.
                    DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS,
                    DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY,
                    DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE,
                    DboPromises.PROC_AUDIT_REPLICATES_AS_RECORDED,
                    DboPromises.PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE,
                    DboPromises.PROC_LANE_EPOCH,
                    DboPromises.FEED_PUSH_ACK_RESUME,
                    DboPromises.FEED_IDEMPOTENT_DELIVERY)),

    STANDARD_MOVES("Data outlives the shapes it was written under: what an object was "
            + "validated under is a fact of the accept event, and everything a migration "
            + "needs follows from recording it.",
            List.of(
                    // The one decision the rest follows from.
                    DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
                    DboPromises.SHAPE_STAMP_IS_DERIVED,
                    DboPromises.SHAPE_SERVED_BESIDE_THE_CLAIM,
                    DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP,
                    DboPromises.SHAPE_UNPARSEABLE_VERSION_REFUSED,
                    DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK,
                    // What it makes possible: counting, finding, converting.
                    DboPromises.SHAPE_STOCK_COUNTED,
                    DboPromises.SHAPE_QUERYABLE_BY_VERSION,
                    DboPromises.SHAPE_RESHAPED_IN_PLACE,
                    DboPromises.SHAPE_RESHAPE_RESUMABLE,
                    DboPromises.SHAPE_REFUSED_OBJECT_LEFT_BEHIND,
                    DboPromises.SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING,
                    DboPromises.SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE,
                    // And what it refuses rather than half-reads.
                    DboPromises.SHAPE_NEWER_DATA_REFUSED,
                    DboPromises.SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER,
                    // The version underneath the shapes, moving too.
                    DboPromises.VER_CONCURRENT_VERSIONS,
                    DboPromises.VER_TRANSITION_BY_CONVERTERS,
                    DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE,
                    DboPromises.VER_BALLOT_RECORDED_PER_VERSION,
                    DboPromises.VER_BALLOT_SERVED_AS_AUTHORED,
                    DboPromises.CORE_UPGRADE_ON_READ,
                    DboPromises.CORE_IDENTITY_SURVIVES_CONVERSION)),

    VENDOR_CHANGE("A provider leaves and takes everything with them, in a sealed archive the "
            + "party operating the store cannot read and somebody else can verify without "
            + "asking anybody.",
            List.of(
                    // One mechanism, so the escape route runs nightly.
                    DboPromises.MNT_BACKUP_IS_EXPORT,
                    DboPromises.MNT_SNAPSHOT_CONSISTENT,
                    DboPromises.MNT_OWNER_KEY_ENCRYPTION,
                    DboPromises.MNT_PORTABLE_STATE_EXPORT,
                    DboPromises.MNT_HISTORY_BY_SCHEMA,
                    // Signed by both parties, and checkable by neither's tools.
                    DboPromises.MNT_ARCHIVE_ROOT_OVER_CONTENTS,
                    DboPromises.MNT_BOTH_PARTIES_ATTEST,
                    DboPromises.MNT_IMPORT_REFUSES_UNATTESTED,
                    DboPromises.MNT_ATTESTATION_READS_AS_FHIR,
                    DboPromises.MNT_ACCEPTED_ROOT_RECORDED,
                    // And what the receiving store rebuilds on the way in.
                    DboPromises.CORE_REINDEX_IS_AN_OPERATION,
                    DboPromises.POL_POLICY_REPLAY_ON_RESTORE));

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
