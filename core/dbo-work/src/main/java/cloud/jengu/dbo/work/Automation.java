package cloud.jengu.dbo.work;

/**
 * Whether a step is automated here at all — declared configuration on the same
 * chain, not a code path (ADR 0059).
 *
 * <p>A zone switching automation off is a decision somebody made, and it is as
 * visible and as auditable as a terminology overlay. A step that is simply
 * never reached is neither.
 */
public record Automation(String process, String step, Scope scope, boolean on) {

    public static Automation off(String process, String step, Scope scope) {
        return new Automation(process, step, scope, false);
    }

    public static Automation on(String process, String step, Scope scope) {
        return new Automation(process, step, scope, true);
    }
}
