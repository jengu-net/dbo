package cloud.jengu.dbo.core.api;

import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A question's answer, walked as it is produced.
 *
 * <p>The store already writes a page as it is produced — memory is one member
 * rather than one page, and a reader sees the first byte before the last row
 * is read. A caller that asked for a list would put that back: everything
 * held, before anybody has said how much of it they want.
 *
 * <p>So an answer is a {@link Stream}, and the cursor keeps doing the work it
 * was already doing. What that buys beyond memory is the store's own promise
 * about paging: a record written between two fetches is not handed over
 * twice, because the position is a cursor rather than an offset counted from
 * the start of a result set that has since grown.
 *
 * <p><b>It is a resource.</b> One walked away from leaves the page it was in
 * the middle of, so a caller closes it, and the vocabulary above this should
 * make that hard to forget.
 */
public final class Answered {

    private Answered() {
    }

    /**
     * A stream over everything a cursor reaches, a page at a time.
     *
     * <p>The first page is fetched when the first element is asked for and
     * not before, which is what makes {@code limit(20)} on a million-row
     * question cost one page. Nothing is fetched twice and nothing is held
     * after it has been handed on.
     *
     * @param page what the store does with a criteria and a cursor
     */
    public static <T> Stream<T> pagedBy(BiFunction<Criteria, String, FeedChunk<T>> page,
            Criteria criteria) {
        Iterator<T> members = new Iterator<>() {

            private Iterator<T> inThisPage = java.util.Collections.emptyIterator();
            private String cursor;
            private boolean drained;

            @Override
            public boolean hasNext() {
                while (!inThisPage.hasNext() && !drained) {
                    FeedChunk<T> chunk = page.apply(criteria, cursor);
                    inThisPage = chunk.items().iterator();
                    // Drained is the store's word for it, and the cursor
                    // going null is not: a page can come back empty with
                    // more behind it, and stopping on that would end an
                    // answer early and silently, which is the worst way for
                    // a query to be wrong.
                    drained = chunk.drained() || chunk.nextCursor() == null;
                    cursor = chunk.nextCursor();
                }
                return inThisPage.hasNext();
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return inThisPage.next();
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(members, Spliterator.ORDERED), false);
    }
}
