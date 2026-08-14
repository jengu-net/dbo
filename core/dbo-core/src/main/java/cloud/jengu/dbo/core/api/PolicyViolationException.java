package cloud.jengu.dbo.core.api;

/**
 * A write the tenant's declared policy forbids (§15) — a rejected tombstone
 * under append-only discipline, for instance. The message names the policy
 * so the caller's error (and the REST OperationOutcome) explains itself.
 */
public class PolicyViolationException extends RuntimeException {

    public PolicyViolationException(String message) {
        super(message);
    }
}
