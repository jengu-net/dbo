package cloud.jengu.dbo.runner.transport;

import java.util.Optional;

/**
 * Whether the tenant serves places, and what a place reads.
 *
 * <p>Empty where the tenant is not serving yet, or serves no place at all;
 * the verb is then refused rather than answered with an empty feed, because
 * an empty feed and an unserved one look identical from the far side.
 */
@FunctionalInterface
public interface Places {
    Optional<Place> place();
}
