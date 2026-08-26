package cloud.jengu.dbo.promise;

import java.util.List;

/** Fixture: the same area code as {@link TestAreas#LONELY_CONCERN}, told differently. */
@Catalogue(namespace = "AREA")
enum TestAreasConflicting implements Area {

    LONELY_CONCERN;

    @Override
    public String title() {
        return "the same code told differently";
    }

    @Override
    public List<Classified> covers() {
        return List.of();
    }
}
