package cloud.jengu.dbo.tenant.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What stands of one tenant's register on this node, for an application
 * running beside the store that adapts what it does to what the tenant
 * agreed to.
 *
 * <p>Registered per tenant, with a {@code tenant} property, as the tenant's
 * store is: it appears when the tenant serves and is retracted when it goes.
 *
 * <p><b>Read here, never kept beside it.</b> At a place of the tenant this
 * reads the copy of the origin's authorisation the place holds, so an
 * application there acts on the tenant's own record while the line to the
 * origin is down, rather than on a second record of consent it keeps itself.
 */
public interface RegisterStanding {

    /** How far what a purpose was agreed to covers the rows serving it now. */
    enum State {
        /** Agreed, and covering every row serving it. */
        AUTHORISED,
        /** Agreed, and a row serving it now opens what the agreement did not name. */
        PARTLY,
        /** Not agreed. */
        NOT
    }

    /**
     * What stands now.
     *
     * @param purposes     each purpose a row of the register serves, by code
     * @param steps        the steps every row of which stands
     * @param authorisedAt when the authorisation standing was given, or null where the
     *                     tenant never authorised at its door and its declaration is read
     * @param heardAt      at a place, when it last read its origin to the end; null at
     *                     the origin, and at a place that has not since it started
     */
    record Standing(Map<String, State> purposes, Set<String> steps, Instant authorisedAt,
            Instant heardAt) {

        public Standing {
            purposes = Map.copyOf(purposes);
            steps = Set.copyOf(steps);
        }

        /** Whether the purpose is agreed and covers every row serving it. */
        public boolean permits(String purpose) {
            return purposes.get(purpose) == State.AUTHORISED;
        }
    }

    /** What stands now, read on the spot. */
    Standing now();

    /**
     * Tells the listener whenever what stands changes, whatever changed it: an
     * authorisation or a withdrawal, one arriving at a place from its origin,
     * or a declaration that leaves a row standing no more. Told the whole of
     * what stands each time, never a difference; not told that a place heard
     * its origin again with nothing changed.
     *
     * <p>Called on the store's own round, so a listener returns promptly and
     * hands anything slow to a thread of its own.
     *
     * @return closing it stops the telling
     */
    AutoCloseable whenChanged(Consumer<Standing> listener);
}
