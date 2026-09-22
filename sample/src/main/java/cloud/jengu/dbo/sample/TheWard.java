package cloud.jengu.dbo.sample;

import cloud.jengu.dbo.asking.Across;
import cloud.jengu.dbo.asking.Questions;
import cloud.jengu.dbo.work.Holder;

/**
 * The screens this application puts in front of people.
 *
 * <p>Each one is a question, and each question is a method rather than a
 * search this code composes. What is stuck, what needs somebody, who read
 * this record — those are the sentences a ward clerk and an operator say, and
 * a vocabulary that answered them by handing out a query builder would be the
 * store's engine with a longer name.
 *
 * <p><b>It holds the vocabulary and not the transport.</b> This application
 * is across a network from its tenant, so what it builds is the binding that
 * speaks over the tenant's surface — and a lifecycle callback running inside
 * the deployment would build the other one and write everything below
 * unchanged.
 */
public final class TheWard {

    private final Questions asking;

    public TheWard(Surface tenant) {
        // The door is one line: what this application already does to reach
        // its tenant, handed to the vocabulary.
        this.asking = Across.through(pathAndQuery -> tenant.fhir("GET", pathAndQuery, null).body());
    }

    /**
     * What the ward is holding that nobody has finished with.
     *
     * <p>A count rather than a list, because a number beside a filter is what
     * a screen shows first and fetching the rows to produce it is what makes
     * a dashboard slow.
     */
    public long outstanding() {
        return asking.work().open().count();
    }

    /**
     * The work automation could not finish, which is what somebody came for.
     *
     * <p>A list of runs that worked silently omits exactly this.
     */
    public java.util.List<String> waitingForAPerson() {
        try (var waiting = asking.work().heldBy(Holder.PERSON).stream()) {
            return waiting.map(run -> run.step() + " " + run.key()).toList();
        }
    }

    /** Who has touched this record, most recent first. */
    public java.util.List<String> whoTouched(String type, String id) {
        try (var entries = asking.trail().about(type, id).stream()) {
            return entries.map(entry ->
                    new String(entry.payload(), java.nio.charset.StandardCharsets.UTF_8))
                    .toList();
        }
    }
}
