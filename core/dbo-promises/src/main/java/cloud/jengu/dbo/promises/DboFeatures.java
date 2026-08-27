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
                    DboPromises.PROC_TASK_SAYS_WHERE_THE_WORK_IS));

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
