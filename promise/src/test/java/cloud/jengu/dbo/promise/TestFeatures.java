package cloud.jengu.dbo.promise;

import java.util.List;

/** Fixture: one classification carrying every status the fold must count. */
@Catalogue(namespace = "FEAT-TEST")
enum TestFeatures implements Feature {

    COVERED_GROUND("the ground the fold walks",
            List.of(TestPromises.PLAIN_PROMISE,
                    TestPromises.REVIEWED_PROMISE,
                    TestPromises.QUIET_PROMISE,
                    Promise.gap("the corner nobody has stated")));

    private final String title;
    private final List<Promise> promises;

    TestFeatures(String title, List<Promise> promises) {
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
