package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Asking;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A screen's questions, asked of the records rather than of the work.
 *
 * <p>Scoped to records this class wrote and identified by a value nothing
 * else uses, because a shared world holds whatever every other class put in
 * it — and because that is the honest shape of the question anyway. A product
 * asks about a ward, a batch or a day.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskingAboutRecordsIT {

    /** Nothing else in the suite writes this, so what it finds is its own. */
    private static final String MINE = "asking-records-" + java.util.UUID.randomUUID();

    /** A system of this class's own, so the code below collides with nobody. */
    private static final String SYSTEM = "urn:asking:test";

    static SharedTenants.Tenant tenant;
    static Asking asking;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        asking = Asking.at(tenant.engine());

        for (String state : List.of("final", "final", "preliminary")) {
            tenant.store().create("""
                    {"resourceType":"Observation","status":"%s",
                     "code":{"coding":[{"system":"%s","code":"%s"}]},
                     "subject":{"display":"nobody"}}""".formatted(state, SYSTEM, MINE));
        }
    }

    @Test
    @DisplayName("a screen counts without fetching, and the count is the question it asked")
    void countingWithoutFetching() {
        Asking.Records mine = asking.records("Observation").whereCoded("code", SYSTEM, MINE);

        assertEquals(3, mine.count(), "the three this class wrote are not there");
        assertEquals(2, mine.whereCoded("status", null, "final").count(),
                "narrowing by a second path did not narrow");
        assertEquals(3, mine.count(),
                "narrowing a question changed the question it was narrowed from");
    }

    @Test
    @DisplayName("and walks them newest first, without holding them")
    void walkingNewestFirst() {
        try (Stream<StoredObject> mine = asking.records("Observation")
                .whereCoded("code", SYSTEM, MINE)
                .newestFirst()
                .stream()) {
            List<StoredObject> found = mine.toList();
            assertEquals(3, found.size(), "the walk did not find what the count did");
        }

        // Taking one of three costs one page and does not walk the rest.
        try (Stream<StoredObject> first = asking.records("Observation")
                .whereCoded("code", SYSTEM, MINE).stream()) {
            assertEquals(1, first.limit(1).count());
        }
    }

    @Test
    @DisplayName("a question nobody declared is not answered more broadly")
    void anUndeclaredNarrowingIsNotIgnored() {
        // The store's own refusal, reached through this vocabulary: a path
        // nothing extracts is a question the store cannot answer, and
        // answering it as "everything" would be the wrong answer that looks
        // right.
        Asking.Records nonsense =
                asking.records("Observation").where("favourite-colour", "blue");

        assertThrows(RuntimeException.class, nonsense::count,
                "an undeclared path was answered instead of refused");
    }

    @Test
    @DisplayName("the joins are declared, refuse by name, and say what is decided first")
    void theJoinsRefuseByName() {
        Asking.Records mine = asking.records("Observation").whereCoded("code", SYSTEM, MINE);

        UnsupportedOperationException include =
                assertThrows(UnsupportedOperationException.class, () -> mine.including("subject"));
        assertTrue(include.getMessage().contains("declared and not built")
                        && include.getMessage().contains("subject"),
                "the refusal did not name what it refused or why: " + include.getMessage());

        UnsupportedOperationException reverse = assertThrows(UnsupportedOperationException.class,
                () -> mine.havingAny("Observation", "subject"));
        assertTrue(reverse.getMessage().contains("scheduled rather than missing"),
                "the reverse direction refused as though it were an oversight: "
                        + reverse.getMessage());
    }
}
