package cloud.jengu.dbo.work;

/**
 * An automated executor, which is a rule that claims the cases it can handle —
 * the way a mailbox rule does. Everything it does not claim falls through to a
 * person, and that fall-through is the automation backlog stated as a number.
 */
public interface ExecutorCandidate {

    Executor executor();

    /**
     * Whether this candidate takes this work.
     *
     * <p>Pure and cheap: resolution asks every candidate more local than the
     * one it settles on, so a precondition with a side effect happens to work
     * that another executor then runs.
     */
    boolean willTake(Work work);
}
