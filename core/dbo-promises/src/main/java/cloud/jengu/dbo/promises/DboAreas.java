package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Area;
import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Classified;

import java.util.List;

/**
 * The pilot's areas. The {@code AREA} namespace is shared by convention so a
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
                    DboFeatures.WORK_REACHES_ONLY_ITS_HOLDER));

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
