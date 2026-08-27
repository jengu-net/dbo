package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Area;
import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Classified;

import java.util.List;

/**
 * The store's areas. The {@code AREA} namespace is shared by convention so a
 * same-named area declared by another product's catalogue composes into one.
 */
@Catalogue(namespace = "AREA")
public enum DboAreas implements Area {

    GDPR("The regulation, paragraph by paragraph, as constraints this store fulfils.",
            List.of(DboConstraints.GDPR_ERASURE,
                    DboConstraints.GDPR_BY_DESIGN,
                    DboConstraints.GDPR_SUBJECT_RIGHTS)),

    DATA_VERSIONING("Data survives deployment: shapes version, stamps say which, and "
            + "stored data moves between versions without ceremony.",
            List.of(DboFeatures.SHAPE_VERSIONING)),

    DISTRIBUTED_WORK("Work travels to whoever does it — pulled, claimed, reported — and "
            + "arrives whole: the documents it is about travel because the work names "
            + "them.",
            List.of(DboFeatures.WORK_ARRIVES_WHOLE, DboFeatures.WORK_SAYS_WHERE_IT_IS,
                    DboFeatures.THE_CATALOGUE_LEARNS,
                    DboFeatures.WORK_REACHES_ONLY_ITS_HOLDER,
                    DboFeatures.THE_RUNNER_CARRIES_WORK,
                    DboFeatures.THE_CATALOGUE_IS_THE_STORES_OWN,
                    DboFeatures.A_REPORT_OBEYS_THE_DECLARATION,
                    DboFeatures.A_RUN_IS_A_COMPLETE_ACCOUNT,
                    DboFeatures.RESOLUTION_IS_DETERMINISTIC_AND_DECLARED,
                    DboFeatures.A_PEER_CONVERGES_SAFELY,
                    DboFeatures.CONTENT_ONLY_CHANGES_UNDER_A_RUN,
                    DboFeatures.THE_NETWORK_AND_ITS_TRACE_ARE_READABLE)),

    CORE("The object engine — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.CORE_MIGRATED)),

    CONT("Container & embedding — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.CONT_MIGRATED)),

    TEN("Tenancy & isolation — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.TEN_MIGRATED)),

    AUTH("Tenant authority & surface protection — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.AUTH_MIGRATED)),

    POL("Tenant policies — audit & write discipline — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.POL_MIGRATED)),

    ZONE("Jurisdiction overlay — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.ZONE_MIGRATED)),

    VER("Version plurality across personalities — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.VER_MIGRATED)),

    SRCH("Search — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.SRCH_MIGRATED)),

    FEED("Feeds, pagination & synchronization — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.FEED_MIGRATED)),

    EVT("Eventing & subscriptions — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.EVT_MIGRATED)),

    WF("Durable work & planes — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.WF_MIGRATED)),

    SCAL("Scaling & routing — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.SCAL_MIGRATED)),

    TERM("Terminology — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.TERM_MIGRATED)),

    SYNC("Canonical content dependencies — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.SYNC_MIGRATED)),

    VAL("Coded-value validation — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.VAL_MIGRATED)),

    OPS("Operations — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.OPS_MIGRATED)),

    MNT("Maintenance — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.MNT_MIGRATED)),

    PRM("Promise — requirements as code — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.PRM_MIGRATED)),

    SCIM("Staff provisioning surface — migrated whole from hand-written prose (2026-08-27); each promise carries its own proof status.",
            List.of(DboFeatures.SCIM_MIGRATED));

    private final String title;
    private final List<Classified> covers;

    DboAreas(String title, List<Classified> covers) {
        this.title = title;
        this.covers = covers;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<Classified> covers() {
        return covers;
    }
}
