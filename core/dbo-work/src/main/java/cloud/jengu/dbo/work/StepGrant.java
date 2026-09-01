package cloud.jengu.dbo.work;

/**
 * What a step says about being executed by somebody else.
 *
 * <p><b>Precedence selects; the step grants the right to override.</b> Being
 * narrow is not a way to acquire authority: under precedence alone any party
 * could displace a national rule by declaring itself more specific, silently
 * and with no record that anything was replaced.
 *
 * <p><b>Not overridable is the default</b>, so opening a step to local
 * variation is a deliberate act by whoever owns the step rather than an
 * omission by whoever wrote it in a hurry.
 *
 * <p>Where the grant comes from is not resolution's business — a catalogue
 * declares it (#71), and until there is one it arrives from whoever knows.
 * What resolution needs is the answer, not its provenance.
 *
 * @param overridableBy the <b>most local</b> class that may override, and
 *                      everything wider than it may too: a step that lets an
 *                      organisation vary it has already accepted that a zone
 *                      may. Null means nobody may.
 */
public record StepGrant(String process, String step, ScopeClass overridableBy) {

    /** The default: nobody overrides it. */
    public static StepGrant of(String process, String step) {
        return new StepGrant(process, step, null);
    }

    /** Opened, deliberately, to that class and everything wider. */
    public StepGrant overridableBy(ScopeClass mostLocal) {
        return new StepGrant(process, step, mostLocal);
    }

    /**
     * Whether a candidate declared at this scope may run this step.
     *
     * <p>The baseline always may — it is not an override, it is the rule.
     */
    boolean admits(Scope scope) {
        if (scope.at() == ScopeClass.BASELINE) {
            return true;
        }
        return overridableBy != null && overridableBy.asLocalAs(scope.at());
    }
}
