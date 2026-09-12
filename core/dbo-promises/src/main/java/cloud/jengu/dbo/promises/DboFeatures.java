package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Feature;
import cloud.jengu.dbo.promise.Promise;

import java.util.List;

/** The functional views the catalogue classifies. */
@Catalogue(namespace = "FEAT-DBO")
public enum DboFeatures implements Feature {

    SHAPE_VERSIONING("The store knows the shape that wrote an object: stamped at accept, "
            + "queryable, per-version in history, carried on the sync wire.",
            List.of(DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
                    DboPromises.SHAPE_STAMP_IS_DERIVED,
                    DboPromises.SHAPE_SERVED_BESIDE_THE_CLAIM,
                    DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP,
                    DboPromises.SHAPE_QUERYABLE_BY_VERSION,
                    DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED, DboPromises.SHAPE_STOCK_COUNTED,
                    DboPromises.SHAPE_UNPARSEABLE_VERSION_REFUSED,
                    DboPromises.SHAPE_RESHAPED_IN_PLACE,
                    DboPromises.SHAPE_RESHAPE_RESUMABLE,
                    DboPromises.SHAPE_REFUSED_OBJECT_LEFT_BEHIND,
                    // The gap this feature declared while the catalogue was
                    // being written, since answered, so it is a named promise
                    // rather than a hole.
                    DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK,
                    DboPromises.SHAPE_NEWER_DATA_REFUSED,
                    DboPromises.SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER,
                    DboPromises.SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING,
                    DboPromises.SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE)),

    EXACT_IDENTIFIER_RESOLUTION("A known identifier finds the record that claims it, "
            + "without the membrane learning to talk.",
            List.of(DboPromises.PDI_EXACT_RESOLUTION)),

    AN_ERASURE_CAN_BE_ASKED_FOR_AND_SHOWN("A consumer can ask for a person's erasure and is "
            + "answered with a run it can show afterwards — what was found, how far it got, "
            + "and that asking again changed nothing.",
            List.of(DboPromises.PDI_ERASURE_IS_A_RUN,
                    DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT)),

    WORK_ARRIVES_WHOLE("Work is the manifest: the step declares what it consumes as "
            + "named slots, the run fills them at creation, the projection and the lane "
            + "carry them — a runner never reaches into the store for what the work is "
            + "about.",
            List.of(DboPromises.PROC_STEP_DECLARES_ITS_SLOTS,
                    DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS,
                    DboPromises.PROC_TASK_CARRIES_THE_INPUTS,
                    DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE,
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
                    DboPromises.PROC_RUNNER_DECLARES_ITS_VITALS)),

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
                    DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP,
                    DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE,
                    DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED,
                    DboPromises.PROC_RUN_KINDS,
                    DboPromises.PROC_ONE_PARENT_NEVER_ACROSS_A_BOUNDARY,
                    DboPromises.PROC_CORRELATION_TRAVELS_OPAQUE,
                    DboPromises.PROC_TRACE_RIDES_THE_LANE,
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
                    DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS,
                    DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY,
                    DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE)),

    CONTENT_ONLY_CHANGES_UNDER_A_RUN("A declared type's content changes only inside a "
            + "run, which is what turns the run into the manifest: every version it "
            + "produced is on the record, so another appliance asks for what it is "
            + "missing instead of comparing stores.",
            List.of(DboPromises.PROC_CONTENT_CHANGES_INSIDE_WORK,
                    DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED)),

    THE_NETWORK_AND_ITS_TRACE_ARE_READABLE("What is known, where, and what it did are "
            + "answerable questions: a node answers for itself today, a deployment answers "
            + "for its nodes when the inventory exists, and the trace join answers for "
            + "provenance.",
            List.of(DboPromises.PROC_A_NODE_ANSWERS_ITS_CATALOGUE,
                    DboPromises.PROC_NETWORK_MAP, DboPromises.PROC_TRACE_JOIN,
                    DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT,
                    DboPromises.OPS_NUMBERS_LEAVE_THE_NODE,
                    DboPromises.PROC_REPORTING_RUNS_WHERE_NOTHING_COLLECTS)),

    CORE_MIGRATED("the object engine — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.CORE_PAYLOAD_IS_TRUTH, DboPromises.CORE_DECLARED_TRUTH_FORM, DboPromises.CORE_REINDEX_IS_AN_OPERATION, DboPromises.CORE_EXTERNAL_IDENTIFIERS, DboPromises.CORE_REFERENCE_EDGES, DboPromises.CORE_VERSIONED_HISTORY, DboPromises.CORE_READ_YOUR_WRITES, DboPromises.CORE_UPGRADE_ON_READ, DboPromises.CORE_PARAMETERIZED_SQL, DboPromises.CORE_SIBLING_MODELS, DboPromises.CORE_A_SEPARABLE_DOMAIN_HAS_A_SCHEMA_OF_ITS_OWN, DboPromises.CORE_DECLARED_IDENTITY, DboPromises.CORE_IDENTITY_SURVIVES_CONVERSION, DboPromises.CORE_NO_IMPLICIT_MERGE, DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS, DboPromises.CORE_CONDITIONAL_REFERENCES, DboPromises.CORE_CONDITIONAL_UPSERT, DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE, DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY)),

    CONT_MIGRATED("container & embedding — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.CONT_FRAMEWORK_FREE_CORE, DboPromises.CONT_DYNAMIC_TENANT_SERVICES, DboPromises.CONT_EMBEDDED_IN_JVM, DboPromises.CONT_IMPORTS_ARE_COMPUTED_OR_CHECKED,
                    DboPromises.CONT_PRIVATE_DEPENDENCIES, DboPromises.CONT_FAST_COLD_START)),

    TEN_MIGRATED("tenancy & isolation — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS, DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE, DboPromises.TEN_COMING_UP_AND_KEEPING_UP_ARE_NOT_ONE_QUEUE, DboPromises.TEN_APPLYING_IS_ASKED_FOR_AND_RECORDED, DboPromises.TEN_A_CHANGE_IS_NOT_A_RETRACTION, DboPromises.TEN_A_REDECLARATION_IS_NOTICED, DboPromises.TEN_DECLARED_TOGETHER_COME_UP_TOGETHER, DboPromises.TEN_A_DECLARATION_IS_A_RECORD, DboPromises.TEN_SERVED_FROM_WHAT_WAS_APPLIED, DboPromises.TEN_STRUCTURAL_SCOPING, DboPromises.TEN_READY_WHEN_ITS_CRITICAL_DEFINITIONS_ARRIVED, DboPromises.TEN_DEDICATED_DATABASE_TIER, DboPromises.TEN_CREDENTIAL_BLIND_PROVISIONING, DboPromises.TEN_REGISTRY_SCOPED_ACCESS, DboPromises.TEN_ERASURE_BY_DROP, DboPromises.TEN_SHARED_TIER_ISOLATION, DboPromises.TEN_FAIRNESS_QUOTAS)),

    AUTH_MIGRATED("tenant authority & surface protection — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.AUTH_TENANT_SCOPED_ISSUER, DboPromises.AUTH_IDENTITY_AS_RECORDS, DboPromises.AUTH_PRIVATE_SURFACE, DboPromises.AUTH_DENY_BY_DEFAULT, DboPromises.AUTH_BEARER_LOCAL_VALIDATION, DboPromises.AUTH_CREDENTIAL_FACTORS_BY_KIND, DboPromises.AUTH_PASSWORD_ONLY_WHERE_WE_ARE_THE_IDP, DboPromises.AUTH_SELF_SERVICE_CHANGE, DboPromises.AUTH_RECOVERY_IS_AN_OPERATOR_ACT, DboPromises.AUTH_DEACTIVATION_RETIRES_CREDENTIALS, DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT, DboPromises.AUTH_NO_SUBJECT_ENUMERATION, DboPromises.AUTH_SMART_SHAPED_SCOPES, DboPromises.AUTH_PORTABLE_AUTHORITY, DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL, DboPromises.AUTH_FEDERATED_HUMANS, DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS, DboPromises.AUTH_PSEUDONYMOUS_TOKENS, DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS, DboPromises.AUTH_ON_BEHALF_OF)),

    POL_MIGRATED("tenant policies — audit & write discipline — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.POL_DECLARED_AT_CONFIGURATION, DboPromises.POL_AUDIT_AS_RECORDS, DboPromises.POL_ACTOR_FROM_AUTHORITY, DboPromises.POL_APPEND_ONLY_DISCIPLINE, DboPromises.POL_ERASURE_COMPATIBLE, DboPromises.POL_DECLARATIVE_RETENTION, DboPromises.POL_RETENTION_SWEEP, DboPromises.POL_POLICY_REPLAY_ON_RESTORE, DboPromises.POL_CUSTOM_AUDIT_EVENTS, DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY, DboPromises.POL_FHIR_AUDIT_PROJECTION)),

    ZONE_MIGRATED("jurisdiction overlay — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.ZONE_DECLARATIONS_AS_RECORDS, DboPromises.ZONE_BROKER_CHOICE, DboPromises.ZONE_SESSIONS_ACCUMULATE, DboPromises.ZONE_SUBJECT_DOMAINS)),

    VER_MIGRATED("version plurality across personalities — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.VER_DEFINITIONS_INDEXED_WITHOUT_THE_TOOLCHAIN,
                    DboPromises.VER_AN_EXPRESSION_THAT_YIELDS_A_VALUE_IS_COMPILED,
                    DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES,
                    DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE, DboPromises.VER_CONVERSION_RUNS_BOTH_WAYS, DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION, DboPromises.ZONE_WHAT_CONVERSION_CANNOT_CARRY_IS_REFUSED_BY_NAME, DboPromises.ZONE_AN_UNSERVABLE_ZONE_IS_SAID_AT_BRING_UP, DboPromises.VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN, DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE, DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE, DboPromises.VER_AN_IMAGE_FROM_ANOTHER_RELEASE_IS_REFUSED, DboPromises.FEED_DEFINITIONS_MOVE_ON_A_FEED_OF_THEIR_OWN, DboPromises.TEN_A_TYPE_DECLARES_ITS_DOMAIN,
                    DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                    DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT,
                    DboPromises.VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION,
                    DboPromises.VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES,
                    DboPromises.VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE,
                    DboPromises.VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME,
                    DboPromises.VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME,
                    DboPromises.VER_VERSION_AGNOSTIC_CORE, DboPromises.VER_CONCURRENT_VERSIONS, DboPromises.VER_PERSONALITY_OWNS_MEANING, DboPromises.VER_SPECIFIED_VALIDATION, DboPromises.VER_VALIDATION_WITHOUT_WRITING, DboPromises.VER_ONE_READ_PER_REQUEST, DboPromises.VER_BALLOT_RECORDED_PER_VERSION, DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE, DboPromises.VER_BALLOT_SERVED_AS_AUTHORED, DboPromises.VER_TRANSITION_BY_CONVERTERS)),

    SRCH_MIGRATED("search — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.SRCH_TIER1_PARITY, DboPromises.SRCH_STRICT_BY_DEFAULT, DboPromises.SRCH_HONEST_CAPABILITY, DboPromises.SRCH_TYPED_ORDERING, DboPromises.SRCH_DECLARED_INDEXES, DboPromises.SRCH_CUSTOM_PARAMETERS)),

    FEED_MIGRATED("feeds, pagination & synchronization — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.FEED_ONE_PRIMITIVE, DboPromises.FEED_KEYSET_CURSORS, DboPromises.FEED_PUSH_ACK_RESUME, DboPromises.FEED_IDEMPOTENT_DELIVERY, DboPromises.FEED_NAMED_CONSUMERS, DboPromises.FEED_LEAN_WIRE_OPTION)),

    EVT_MIGRATED("eventing & subscriptions — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.EVT_TRANSACTIONAL_OUTBOX, DboPromises.EVT_FHIR_SUBSCRIPTIONS, DboPromises.EVT_DURABLE_DELIVERY, DboPromises.EVT_IN_PROCESS_SURFACE)),

    WF_MIGRATED("durable work & planes — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.WF_POSTGRES_SUBSTRATE, DboPromises.WF_TWO_PLANES, DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE, DboPromises.WF_PLATFORM_COORDINATED_HOPS, DboPromises.WF_HOPS_AUDITED)),

    SEALED_WORK("One runner fleet carries every tenant's work and cannot read most of it: "
            + "the payload is sealed to the participant meant to open it, opening and "
            + "carrying are different records, and the run's trail is chained from the task "
            + "so its completeness can be checked. Decided in review; nothing built.",
            List.of(DboPromises.PROC_WORK_TRAVELS_SEALED,
                    DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                    DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                    DboPromises.PROC_DONE_MEANS_DONE,
                    DboPromises.PROC_A_LANE_OVER_THE_STREAM,
                    DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT,
                    DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                    DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                    DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    DboPromises.WF_TWO_PLANES,
                    DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE,
                    DboPromises.WF_HOPS_AUDITED)),

    SCAL_MIGRATED("scaling & routing — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.SCAL_DURABLE_ASSIGNMENT, DboPromises.SCAL_SINGLE_WRITER_TENANT, DboPromises.SCAL_TRANSPARENT_ROUTING, DboPromises.SCAL_TWO_HOP_LOCALITY, DboPromises.SCAL_NO_SHARED_STATE_BROKER)),

    TERM_MIGRATED("terminology — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.TERM_NATIVE_FORM, DboPromises.TERM_BULK_LOAD, DboPromises.TERM_EVERY_TENANT_ANSWERS, DboPromises.TERM_OPERATIONS_FROM_NATIVE_FORM, DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY, DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS)),

    SYNC_MIGRATED("canonical content dependencies — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.SYNC_DECLARED_ONLY, DboPromises.SYNC_ANY_TYPE, DboPromises.SYNC_TERMINOLOGY_GRAIN_SURVIVES, DboPromises.SYNC_CONVERT_ON_APPLY, DboPromises.SYNC_PROVENANCE_COPIES, DboPromises.SYNC_LOCAL_SHADOWING, DboPromises.SYNC_DIRECT_UPSTREAM_ONLY, DboPromises.SYNC_SPEC_DECLARED, DboPromises.SYNC_FULL_HISTORY_CATCH_UP)),

    VAL_MIGRATED("coded-value validation — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.VAL_BINDING_STRENGTH_IS_THE_ANSWER, DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID)),

    OPS_MIGRATED("operations — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.OPS_TENANT_BLOB_STORAGE, DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES, DboPromises.OPS_MIGRATION_AS_DEPLOYMENT)),

    MNT_MIGRATED("maintenance — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.MNT_BACKUP_IS_EXPORT, DboPromises.MNT_PORTABLE_STATE_EXPORT, DboPromises.MNT_HISTORY_BY_SCHEMA, DboPromises.MNT_OWNER_KEY_ENCRYPTION, DboPromises.MNT_SNAPSHOT_CONSISTENT, DboPromises.MNT_ARCHIVE_ROOT_OVER_CONTENTS, DboPromises.MNT_BOTH_PARTIES_ATTEST, DboPromises.MNT_IMPORT_REFUSES_UNATTESTED, DboPromises.MNT_ATTESTATION_READS_AS_FHIR, DboPromises.MNT_ACCEPTED_ROOT_RECORDED)),

    PRM_MIGRATED("promise — requirements as code — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.PRM_NAME_IS_THE_CODE, DboPromises.PRM_GAP_IS_FIRST_CLASS, DboPromises.PRM_REGISTERED_AT_COMPILE_TIME, DboPromises.PRM_CATALOGUE_READ_WHOLE, DboPromises.PRM_DOWN_LINKS_ONLY, DboPromises.PRM_AREAS_MERGE_BY_CODE, DboPromises.PRM_CITATION_IS_TYPED, DboPromises.PRM_PROOFS_INDEXED_AT_COMPILE_TIME, DboPromises.PRM_STATUS_IS_DERIVED, DboPromises.PRM_COVERAGE_IS_A_FOLD, DboPromises.PRM_PROJECTION_IS_GENERATED, DboPromises.PRM_COVERAGE_ON_THE_RESULTS_PAGE,
                    DboPromises.PRM_A_STORY_IS_CITED_NOT_CLAIMED)),

    SCIM_MIGRATED("staff provisioning surface — migrated whole from hand-written prose; each promise below carries its own proof status.",
            List.of(DboPromises.SCIM_DECLARED_PER_TENANT, DboPromises.SCIM_USER_IS_THE_PERSON, DboPromises.SCIM_ENUMERATION_STAYS_INSIDE, DboPromises.SCIM_DIRECTORY_CREDENTIAL, DboPromises.SCIM_DEPROVISION_IS_A_STATE, DboPromises.SCIM_EVERY_OP_IS_A_DISCLOSURE, DboPromises.SCIM_GROUPS_READ_ONLY));

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
