package cloud.jengu.dbo.embedded;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What one part of a host tells the container.
 *
 * <p>A host may be assembled from more than one piece — an application that
 * both serves tenants and performs their work is the standing case — and the
 * container is where each piece puts its configuration. There is exactly one
 * container, by design: two would each hold a copy of every bundle.
 *
 * <p><b>So the properties are collected rather than owned.</b> Before this,
 * each half built the runtime from its own properties class under a condition
 * that it did not already exist, which meant the half that lost a race had its
 * configuration read, converted and dropped — measured as a worker polling at
 * the default two seconds while its configuration said 500 milliseconds.
 * Nothing failed, which is what made it worse than a refusal: a dial nobody
 * can trust without timing it reads exactly like a dial that works.
 *
 * <p>Named in this module because this module owns the container and names no
 * framework. A binding declares one of these per piece of configuration it
 * carries; whichever piece constructs the runtime applies all of them.
 */
public interface FrameworkContribution {

    /** This piece's framework properties. */
    Map<String, String> properties();

    /**
     * Every contribution, as one map.
     *
     * <p><b>A disagreement is refused, not resolved.</b> Two pieces of one
     * host setting one property to two values have no correct answer available
     * here — silently preferring either is how a deployment acquires a value
     * nobody wrote down — so the property, both values and the fact that a
     * human has to choose are said at once.
     */
    static Map<String, String> merged(Collection<? extends FrameworkContribution> pieces) {
        Map<String, String> all = new LinkedHashMap<>();
        for (FrameworkContribution piece : pieces) {
            piece.properties().forEach((name, value) -> {
                String already = all.get(name);
                if (already != null && !Objects.equals(already, value)) {
                    throw new IllegalStateException("this host sets " + name + " twice and "
                            + "disagrees with itself: '" + already + "' and '" + value + "'. One "
                            + "container takes one value, and which of the two it should be is "
                            + "not something this can decide");
                }
                all.put(name, value);
            });
        }
        return all;
    }
}
