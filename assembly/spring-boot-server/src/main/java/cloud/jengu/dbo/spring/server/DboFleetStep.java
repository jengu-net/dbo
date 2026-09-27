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

    /** Which behaviour this is — what changed when the step's code changed. */
    String version();

    /** Whose code that is; a provider can be withdrawn, so a run names it. */
    String provider();
}
