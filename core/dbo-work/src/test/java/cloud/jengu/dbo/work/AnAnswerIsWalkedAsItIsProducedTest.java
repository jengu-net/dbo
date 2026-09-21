package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Answered;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An answer is walked as it is produced, so asking for twenty of a million
 * costs one page.
 *
 * <p>Held here rather than beside the class: {@code dbo-core} has zero
 * runtime dependencies on purpose and no test task, and this module already
 * compiles against it.
 */
class AnAnswerIsWalkedAsItIsProducedTest {

    /** A store that hands back pages and counts how many were asked for. */
    private static final class Pages {

        private final List<List<String>> pages;
        private final boolean lastPageCarriesACursor;
        final List<String> asked = new ArrayList<>();

        Pages(List<List<String>> pages, boolean lastPageCarriesACursor) {
            this.pages = pages;
            this.lastPageCarriesACursor = lastPageCarriesACursor;
        }

        FeedChunk<String> page(Criteria unused, String cursor) {
            int at = cursor == null ? 0 : Integer.parseInt(cursor);
            asked.add(String.valueOf(at));
            boolean last = at == pages.size() - 1;
            String next = last && !lastPageCarriesACursor ? null : String.valueOf(at + 1);
            return new FeedChunk<>(pages.get(at), next, last);
        }
    }

    private static Stream<String> over(Pages pages) {
        return Answered.pagedBy(pages::page, Criteria.of("Anything"));
    }

    @Test
    @DisplayName("nothing is fetched until somebody asks for the first member")
    void nothingIsFetchedUntilAsked() {
        Pages pages = new Pages(List.of(List.of("a", "b"), List.of("c")), false);

        Stream<String> answer = over(pages);

        assertEquals(List.of(), pages.asked,
                "a page was fetched by building the stream, so a caller that decided not to "
                        + "read it has already paid for it");
        assertEquals("a", answer.findFirst().orElseThrow());
        assertEquals(List.of("0"), pages.asked, "more than the first page was read to answer "
                + "for the first member");
    }

    @Test
    @DisplayName("asking for a few of many costs the pages those few are on")
    void aFewOfManyCostsAFewPages() {
        List<List<String>> many = new ArrayList<>();
        for (int page = 0; page < 500; page++) {
            many.add(List.of("row-" + page + "-a", "row-" + page + "-b"));
        }
        Pages pages = new Pages(many, false);

        List<String> firstThree = over(pages).limit(3).toList();

        assertEquals(3, firstThree.size());
        assertEquals(2, pages.asked.size(),
                "a limit of three over a thousand rows read " + pages.asked.size()
                        + " pages, so the stream is not lazy and the cursor is decoration");
    }

    @Test
    @DisplayName("an empty page with more behind it does not end the answer")
    void anEmptyPageIsNotTheEnd() {
        // The assertion this test exists for. Stopping when a page comes back
        // empty is the obvious implementation and it ends an answer early and
        // silently, which is the worst way for a query to be wrong: the caller
        // gets a shorter list and no error.
        Pages pages = new Pages(List.of(List.of("a"), List.of(), List.of("b")), false);

        assertEquals(List.of("a", "b"), over(pages).toList(),
                "the answer stopped at an empty page while the store had more behind it");
    }

    @Test
    @DisplayName("and a drained page ends it even while it still carries a cursor")
    void drainedEndsIt() {
        // The other half: some stores hand back a cursor on the last page.
        // Following it forever is a stream that never ends.
        Pages pages = new Pages(List.of(List.of("a"), List.of("b")), true);

        assertEquals(List.of("a", "b"), over(pages).toList(),
                "the answer did not stop where the store said it had drained");
        assertTrue(pages.asked.size() <= 2,
                "pages were fetched past the end: " + pages.asked);
    }
}
