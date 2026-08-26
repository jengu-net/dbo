package cloud.jengu.dbo.promise;

/**
 * What stands behind a promise. The MODEL of status lives here; the
 * DERIVATION — reading proof sites and deciding which status a promise has
 * earned — is the scanner's, and status is always derived, never asserted.
 */
public enum PromiseStatus {

    /** Cited by passing tests: the promise is proven automatically. */
    PROVEN,

    /** Declared, cited by nothing yet: intent, parked where it is visible. */
    PLANNED,

    /** Assured by recorded review rather than by an executable test. */
    ASSURED,

    /** Ground nobody has stated: a {@link Promise#gap(String)}. */
    GAP
}
