package cloud.jengu.dbo.promise;

import java.util.List;

/**
 * One classification: a view over the promises that fulfil it.
 *
 * <p>Links run DOWN only (REQ-DBO-PRM-DOWN-LINKS-ONLY): a classification
 * declares its promises, a promise never names its classifications, and the
 * inverse is derived by {@link Registry.Model}. One direction, one truth,
 * nothing to drift.
 *
 * <p>Coverage is a fold, never an assertion: a classification's coverage is
 * the statuses of the promises it declares — its gaps included.
 */
public interface Classified extends Coded {

    /** The classification, as one business-readable title. */
    String title();

    /** The promises fulfilling this classification — named constants and gaps. */
    List<Promise> promises();
}
