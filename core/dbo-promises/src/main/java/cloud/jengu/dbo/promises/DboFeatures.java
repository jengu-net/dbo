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
                    // Real, not staged: found while writing this catalogue.
                    Promise.gap("nobody has stated what happens when a pack withdraws or "
                            + "re-numbers a profile version that stamped data still "
                            + "carries"))),

    EXACT_IDENTIFIER_RESOLUTION("A known identifier finds the record that claims it, "
            + "without the membrane learning to talk.",
            List.of(DboPromises.PDI_EXACT_RESOLUTION));

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
