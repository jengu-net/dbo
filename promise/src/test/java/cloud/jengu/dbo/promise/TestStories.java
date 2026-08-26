package cloud.jengu.dbo.promise;

import java.util.List;

/** Fixture: a classification catalogue declaring named promises and a gap. */
@Catalogue(namespace = "US-TEST")
enum TestStories implements Story {

    FIRST_CASE("somebody does the plain thing",
            List.of(TestPromises.PLAIN_PROMISE,
                    Promise.gap("nobody has stated what happens on the second try")));

    private final String title;
    private final List<Promise> promises;

    TestStories(String title, List<Promise> promises) {
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
