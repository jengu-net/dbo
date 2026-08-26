package cloud.jengu.dbo.promise;

import java.util.List;

/**
 * Fixture: areas conventionally share the {@code AREA} namespace so a
 * same-named area declared by two catalogues composes into one.
 */
@Catalogue(namespace = "AREA")
enum TestAreas implements Area {

    SHARED_CONCERN("one concern"),
    LONELY_CONCERN("only declared here");

    private final String title;

    TestAreas(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<Classified> covers() {
        return List.of(TestStories.FIRST_CASE);
    }
}
