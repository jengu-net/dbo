package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Promise;
import cloud.jengu.dbo.promise.Quality;

import java.util.List;

/**
 * The twelve quality goals, each declaring the promises that fulfil it.
 *
 * <p>The goals are stated in the introduction and judged in the quality tree,
 * and until this enum existed the mapping between them was a table somebody
 * kept in step by hand. It did not stay in step: the twelfth goal had no row
 * at all, and nothing could notice, because a hand-kept list has no reader
 * that fails.
 *
 * <p>A quality states no behaviour of its own. Every promise below is declared
 * in {@link DboPromises} and proven — or not — by the tests that cite it, so a
 * goal's coverage is folded from those statuses rather than asserted here. A
 * goal that loses its last proof now shows as a goal that lost it.
 *
 * <p><b>One goal declares a hole rather than promises.</b> Performance asks
 * for a number on stated hardware and none has been taken, so it carries a
 * gap: named ground nobody has made true, counting against that goal's own
 * coverage instead of letting the promises about the shape performance comes
 * from read as though the figures existed.
 *
 * <p>The promises are chosen rather than swept in by area. An area is where a
 * promise lives; a quality is what would stop being true without it, which is
 * a smaller and more useful set than everything filed under the same prefix.
 */
@Catalogue(namespace = "QUAL-DBO")
public enum DboQualities implements Quality {

    TOTAL_TENANT_ISOLATION("Total tenant isolation: no cross-tenant surface exists, a "
            + "tenant's data sits in its own database, and erasure is a drop.",
            List.of(DboPromises.TEN_STRUCTURAL_SCOPING,
                    DboPromises.TEN_DEDICATED_DATABASE_TIER,
                    DboPromises.TEN_SHARED_TIER_ISOLATION,
                    DboPromises.TEN_ERASURE_BY_DROP,
                    DboPromises.AUTH_PRIVATE_SURFACE,
                    DboPromises.AUTH_NO_SUBJECT_ENUMERATION,
                    DboPromises.SCIM_ENUMERATION_STAYS_INSIDE)),

    THE_TENANT_OWNS_THE_BOX("The tenant owns the box: its own authority issues its users' "
            + "tokens, and the operator cannot read what it holds.",
            List.of(DboPromises.AUTH_TENANT_SCOPED_ISSUER,
                    DboPromises.AUTH_PORTABLE_AUTHORITY,
                    DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS,
                    DboPromises.TEN_CREDENTIAL_BLIND_PROVISIONING,
                    DboPromises.MNT_OWNER_KEY_ENCRYPTION,
                    DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)),

    PROCESS_AS_A_STORAGE_CONCERN("Process as a storage concern: durable work, subscriptions "
            + "and feeds run on the same substrate as the data, with no broker and no cache "
            + "tier.",
            List.of(DboPromises.WF_POSTGRES_SUBSTRATE,
                    DboPromises.WF_TWO_PLANES,
                    DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE,
                    DboPromises.EVT_TRANSACTIONAL_OUTBOX,
                    DboPromises.EVT_DURABLE_DELIVERY,
                    DboPromises.EVT_A_TENANT_DELIVERS,
                    DboPromises.FEED_ONE_PRIMITIVE,
                    DboPromises.FEED_KEYSET_CURSORS,
                    DboPromises.SCAL_NO_SHARED_STATE_BROKER)),

    DECLARED_HANDLING("Declared handling: a type states what kind of data it is and the "
            + "engine enforces it rather than trusting the caller.",
            List.of(DboPromises.POL_DECLARED_AT_CONFIGURATION,
                    DboPromises.POL_DECLARATIVE_RETENTION,
                    DboPromises.POL_RETENTION_SWEEP,
                    DboPromises.POL_ERASURE_COMPATIBLE,
                    DboPromises.CORE_DECLARED_TRUTH_FORM,
                    DboPromises.CORE_DECLARED_IDENTITY,
                    DboPromises.TEN_A_TYPE_DECLARES_ITS_DOMAIN)),

    ONE_API("One API: configuration, identity and authorization are ordinary records on the "
            + "same surface as the data.",
            List.of(DboPromises.AUTH_IDENTITY_AS_RECORDS,
                    DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS,
                    DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                    DboPromises.SCIM_DECLARED_PER_TENANT,
                    DboPromises.SCIM_USER_IS_THE_PERSON,
                    DboPromises.TEN_A_DECLARATION_IS_A_RECORD,
                    DboPromises.POL_AUDIT_AS_RECORDS)),

    JURISDICTION_AS_CONFIGURATION("Jurisdiction as configuration: zones, brokers and "
            + "identifier systems are records and terminology rather than code.",
            List.of(DboPromises.ZONE_DECLARATIONS_AS_RECORDS,
                    DboPromises.ZONE_BROKER_CHOICE,
                    DboPromises.ZONE_SUBJECT_DOMAINS,
                    DboPromises.ZONE_AN_UNSERVABLE_ZONE_IS_SAID_AT_BRING_UP,
                    DboPromises.AUTH_A_ZONE_IS_ITS_OWN_BROKER,
                    DboPromises.TERM_EVERY_TENANT_ANSWERS,
                    DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS)),

    PERSONAL_DATA_UNDER_STRUCTURAL_CONTROL("Personal data under structural control: "
            + "identifying elements are encrypted in place and erasure destroys a key.",
            List.of(DboPromises.PDI_STRUCTURAL_VAULT,
                    DboPromises.PDI_CRYPTO_SHREDDING,
                    DboPromises.PDI_BLIND_OPERATIONS,
                    DboPromises.PDI_UNFINDABLE_AFTER_ERASURE,
                    DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE,
                    DboPromises.PDI_SHRED_LEDGER,
                    DboPromises.PDI_RIGHTS_AS_OPERATIONS,
                    DboPromises.PDI_ERASURE_IS_A_RUN)),

    PORTABILITY("Portability: one sealed archive is backup, restore, migration and export.",
            List.of(DboPromises.MNT_BACKUP_IS_EXPORT,
                    DboPromises.MNT_PORTABLE_STATE_EXPORT,
                    DboPromises.MNT_SNAPSHOT_CONSISTENT,
                    DboPromises.MNT_BOTH_PARTIES_ATTEST,
                    DboPromises.MNT_IMPORT_REFUSES_UNATTESTED,
                    DboPromises.MNT_ACCEPTED_ROOT_RECORDED,
                    DboPromises.OPS_MIGRATION_AS_DEPLOYMENT)),

    FHIR_VERSION_PLURALITY("FHIR-version plurality: several versions are served at once, and "
            + "the engine holds no version knowledge.",
            List.of(DboPromises.VER_CONCURRENT_VERSIONS,
                    DboPromises.VER_VERSION_AGNOSTIC_CORE,
                    DboPromises.VER_PERSONALITY_OWNS_MEANING,
                    DboPromises.VER_TRANSITION_BY_CONVERTERS,
                    DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE,
                    DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
                    DboPromises.SHAPE_QUERYABLE_BY_VERSION,
                    DboPromises.SHAPE_NEWER_DATA_REFUSED)),

    PERFORMANCE_AS_A_FIRST_CLASS_PROPERTY("Performance as a first-class property: measured "
            + "on reference hardware, on the production serving path.",
            List.of(DboPromises.SCAL_DURABLE_ASSIGNMENT,
                    DboPromises.SCAL_SINGLE_WRITER_TENANT,
                    DboPromises.SCAL_TRANSPARENT_ROUTING,
                    DboPromises.SCAL_TWO_HOP_LOCALITY,
                    DboPromises.CONT_FAST_COLD_START,
                    // The goal asks for a number, and no number has been taken.
                    // Named here rather than left to the tree's prose, because
                    // a goal whose defining figures are missing should count
                    // against its own coverage rather than read as covered by
                    // the promises describing the shape performance is expected
                    // to come from.
                    Promise.gap("Throughput and latency on the production serving path, "
                            + "on stated reference hardware, against a stated workload — "
                            + "so a reader can compare rather than take the shape on "
                            + "trust."))),

    EMBEDDABILITY("Embeddability: the store boots inside a host's own JVM, and the suite "
            + "runs against it.",
            List.of(DboPromises.CONT_EMBEDDED_IN_JVM,
                    DboPromises.CONT_FRAMEWORK_FREE_CORE,
                    DboPromises.CONT_PRIVATE_DEPENDENCIES,
                    DboPromises.CONT_IMPORTS_ARE_COMPUTED_OR_CHECKED,
                    DboPromises.CONT_DYNAMIC_TENANT_SERVICES)),

    OPERATIONAL_HONESTY("Operational honesty: an unrecognised search parameter is refused "
            + "rather than answered more broadly, the CapabilityStatement is generated from "
            + "what is actually served, and every boundary crossing leaves an entry.",
            List.of(DboPromises.SRCH_STRICT_BY_DEFAULT,
                    DboPromises.SRCH_HONEST_CAPABILITY,
                    DboPromises.SRCH_TIER1_PARITY,
                    DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                    DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY,
                    DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                    DboPromises.WF_HOPS_AUDITED,
                    DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES));

    private final String title;
    private final List<Promise> promises;

    DboQualities(String title, List<Promise> promises) {
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
