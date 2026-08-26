package cloud.jengu.dbo.promise;

import java.util.List;

/**
 * Fixture: a second catalogue declaring the same area with the same title —
 * the cross-catalogue merge case. Areas conventionally share the
 * {@code AREA} namespace so a same-named area composes into one.
 */
@Catalogue(namespace = "AREA")
enum TestAreasElsewhere implements Area {

    SHARED_CONCERN;

    @Override
    public String title() {
        return "one concern";
    }

    @Override
    public List<Classified> covers() {
        return List.of();
    }
}
