package cloud.jengu.dbo.core.process;

/**
 * What a step is called, everywhere.
 *
 * <p>{@code <module>.<process>.<step>} — opaque, globally stable, and a string
 * a run can name before anything has declared it. Cross-module references then
 * need no compile-time coupling: a module refers to another's step by writing
 * its id, and a step referenced but not installed is refused <b>by name</b>
 * rather than silently doing nothing.
 *
 * <p>Stable is the load-bearing word. Runs record the id they ran under, so a
 * catalogue that renames a step breaks retroactively — which is why the scheme
 * was fixed with the record rather than with the catalogue.
 */
public record StepId(String module, String process, String step) {

    public StepId {
        require(module, "module");
        require(process, "process");
        require(step, "step");
    }

    private static void require(String part, String what) {
        if (part == null || !part.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("a step id's " + what
                    + " is lowercase, and this one is: " + part);
        }
    }

    /** The whole id as it is written and stored. */
    @Override
    public String toString() {
        return module + "." + process + "." + step;
    }

    /** The process half, which is what a run's process field carries. */
    public String processId() {
        return module + "." + process;
    }

    public static StepId of(String id) {
        String[] parts = id == null ? new String[0] : id.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "a step id is <module>.<process>.<step>, and this one is: " + id);
        }
        return new StepId(parts[0], parts[1], parts[2]);
    }
}
