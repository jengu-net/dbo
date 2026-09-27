package cloud.jengu.dbo.spring.server;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a bean performing a fleet step is recorded as on every run it closes.
 *
 * <p>The step itself is not here: the bean names it, because that is the one
 * thing the bean cannot be wrong about without being wrong about what it does.
 * What is here is the pair a run has to carry and the code cannot supply —
 * which behaviour this is, and whose it is. A step is performed for every
 * tenant in the fleet at once, so "which version of this ran over that
 * tenant's work" is a question somebody will ask about a specific afternoon,
 * and it can only be answered if it was written down at the time.
 *
 * <p>Both are required for that reason, and refused at context refresh rather
 * than in a log: a default here would put the same placeholder on every run
 * in the fleet, which is the same as recording nothing while looking like a
 * record.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DboFleetStep {

    /**
     * Who performed it, as every run this bean closes will record.
     *
     * <p>Stated rather than taken from the application's name, because the
     * name is ambient: it is a property, it differs between a deployment and
     * a test of it, and a run recorded under whichever one happened to be on
     * the environment is a run whose executor cannot be relied on. This is
     * the field a question like "which of our things closed this" is answered
     * from, so it belongs beside the code it names.
     */
    String name();

    /** Which behaviour this is — what changed when the step's code changed. */
    String version();

    /** Whose code that is; a provider can be withdrawn, so a run names it. */
    String provider();
}
