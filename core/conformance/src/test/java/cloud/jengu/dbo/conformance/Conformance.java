package cloud.jengu.dbo.conformance;

import java.util.ArrayList;
import java.util.List;

/**
 * One statement the FHIR specification makes about a server, and what this
 * server actually does about it.
 *
 * <p>The vocabulary is deliberately three-valued. {@link Level#SUPPORTED} and
 * {@link Level#FAILED} are the obvious two; {@link Level#OUT_OF_SCOPE} is the
 * one that makes the report honest. dbo implements a declared subset — search
 * is tier 1 by design — so a rule it does not follow is usually a boundary it
 * has drawn rather than a defect it has. A report that could only say pass or
 * fail would have to lie about one of them.
 */
public final class Conformance {

    /** What the server does about one rule. */
    public enum Level {
        /** The rule is followed, and this run proved it. */
        SUPPORTED("✅ supported"),
        /** The rule is not followed and that is a defect. */
        FAILED("❌ failed"),
        /** Deliberately not implemented; the CapabilityStatement says so too. */
        OUT_OF_SCOPE("⚪ out of scope");

        final String label;

        Level(String label) {
            this.label = label;
        }
    }

    /** Which part of the specification a rule comes from. */
    public enum Area {
        INSTANCE("Instance interactions"),
        TYPE("Type interactions"),
        SYSTEM("System interactions"),
        SEARCH("Search"),
        ERRORS("Error signalling"),
        CONTENT("Content and negotiation");

        final String title;

        Area(String title) {
            this.title = title;
        }
    }

    /** A rule, its outcome, and what was actually observed. */
    public record Result(Area area, String rule, String reference, Level level, String observed) {}

    private final List<Result> results = new ArrayList<>();

    /**
     * Runs one check. A check returns the observed detail and throws to fail —
     * an assertion error and an unexpected exception mean the same thing here,
     * because both mean the server did not do what the specification says.
     */
    public void check(Area area, String rule, String reference, Check body) {
        try {
            String observed = body.run();
            results.add(new Result(area, rule, reference, Level.SUPPORTED, observed));
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            results.add(new Result(area, rule, reference, Level.FAILED, message));
        }
    }

    /**
     * Records a rule dbo does not implement on purpose.
     *
     * @param because why it is out of scope, in the same words the
     *                specification or the capability statement would use
     */
    public void outOfScope(Area area, String rule, String reference, String because) {
        results.add(new Result(area, rule, reference, Level.OUT_OF_SCOPE, because));
    }

    public List<Result> results() {
        return List.copyOf(results);
    }

    public long count(Level level) {
        return results.stream().filter(r -> r.level() == level).count();
    }

    @FunctionalInterface
    public interface Check {
        String run() throws Exception;
    }
}
