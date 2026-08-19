package cloud.jengu.dbo.fhir.common;

/**
 * Validation could not reach a verdict — which is not the same as a verdict of
 * invalid, and must not be answered as one.
 *
 * <p>The case this exists for: HL7's ReDoS guard runs every primitive-type
 * regex on a thread of its own against a 500 ms wall clock, so on a busy
 * machine it expires on inputs that take microseconds to match. The value that
 * produced it here was {@code rest-hook}. Reported as invalid, that becomes a
 * caller told their perfectly good resource is malformed, with a diagnostic
 * naming a regular expression — and, being load-dependent, it goes away when
 * anybody investigates.
 *
 * <p>The guard is not wrong to exist: FHIR's own {@code code} pattern
 * backtracks badly, so ignoring a timeout would wave through what it defends
 * against. The store therefore asks again, once — a timeout is not evidence of
 * invalidity — and if the second attempt also runs out of clock, says so as
 * <b>this</b>: the machine could not answer, ask again later. A retry is the
 * caller's to make, and it will succeed.
 *
 * <p>The distinction is the same one a store has to draw everywhere: <em>the
 * record is wrong</em> and <em>the store could not tell</em> are different
 * answers, and collapsing them into the first is how a busy afternoon becomes
 * a bug report about data quality.
 */
public class ValidationUnavailableException extends RuntimeException {

    public ValidationUnavailableException(String typeName, String detail) {
        super("validation could not complete for %s: %s".formatted(typeName, detail));
    }
}
