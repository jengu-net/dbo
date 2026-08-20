package cloud.jengu.dbo.core.process;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * The steps this container knows how to talk about (#71).
 *
 * <p><b>Installed, not listed.</b> A module contributes its steps by being
 * installed — the same rule faces already follow (#54) — so nothing maintains a
 * central catalogue that can disagree with what is deployed. In the container it
 * is backed by the service registry; on a plain classpath it is what declared
 * itself.
 *
 * <p><b>A step referenced but not installed is refused by name.</b> Silently
 * doing nothing is the failure this exists to prevent: a run naming a step
 * nobody contributed should say which one, not proceed as if the step had been
 * a no-op.
 */
public interface Steps {

    /** What a module contributes by being installed. */
    interface Catalogue {
        Set<StepDeclaration> steps();
    }

    Optional<StepDeclaration> byId(String id);

    /** Every id known here, for a refusal that says what would have worked. */
    Set<String> ids();

    /**
     * The step, or a refusal naming it and what is installed.
     *
     * <p>"not declared" and "declared elsewhere" have different fixes, and a
     * caller told only no cannot tell them apart.
     */
    default StepDeclaration require(String id) {
        return byId(id).orElseThrow(() -> new UnknownStep(id, ids()));
    }

    /** A fixed set — what a test or a single-assembly runtime has. */
    static Steps of(StepDeclaration... declared) {
        Map<String, StepDeclaration> byId = new TreeMap<>();
        for (StepDeclaration step : declared) {
            if (byId.putIfAbsent(step.id().toString(), step) != null) {
                throw new IllegalArgumentException("two declarations of "
                        + step.id() + " — which one a run means would depend on ordering");
            }
        }
        Map<String, StepDeclaration> fixed = Map.copyOf(byId);
        return new Steps() {
            @Override
            public Optional<StepDeclaration> byId(String id) {
                return Optional.ofNullable(fixed.get(id));
            }

            @Override
            public Set<String> ids() {
                return new java.util.TreeSet<>(fixed.keySet());
            }
        };
    }

    /**
     * What the classpath declares, for a runtime with no service registry — a
     * test, the bench, a single-jar assembly.
     */
    static Steps installed() {
        Map<String, StepDeclaration> byId = new LinkedHashMap<>();
        for (Catalogue catalogue : ServiceLoader.load(Catalogue.class)) {
            for (StepDeclaration step : catalogue.steps()) {
                if (byId.putIfAbsent(step.id().toString(), step) != null) {
                    throw new IllegalArgumentException("two modules declare "
                            + step.id() + " — a step id is globally stable, so this is a "
                            + "collision rather than an override");
                }
            }
        }
        return of(byId.values().toArray(new StepDeclaration[0]));
    }

    /** A step nobody contributed, said as one. */
    class UnknownStep extends RuntimeException {
        public UnknownStep(String id, Set<String> installed) {
            super("no step '" + id + "' is declared here; installed: " + installed);
        }
    }
}
