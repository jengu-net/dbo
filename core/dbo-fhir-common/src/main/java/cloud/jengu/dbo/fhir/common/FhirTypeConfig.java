package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;

import java.util.Set;

/**
 * Per-type identity configuration (§12): which identity class the type
 * carries, and — for IDENTIFIER — its designated identity-bearing systems in
 * trust order. Supplied by tenant/zone configuration in production. Version-neutral: any personality consumes it.
 *
 * <p>Also carries the type's {@link Handling} — what kind of data it is.
 * The factories below state {@code operational()}
 * rather than defaulting to it silently: a clinical resource written by the
 * tenant's own people is the ordinary case, and everything else says so.
 * Terminology replicated from a zone uses {@link #replicatedCanonical}, and
 * types written from two directions at once — {@code Practitioner} above all —
 * wait on per-field authority rather than being forced
 * into one answer here.
 */
public record FhirTypeConfig(String typeName, IdentityClass identityClass,
                             Set<String> identitySystems, Handling handling,
                             Extraction extraction) {

    /** Where this type's envelope, claims and edges are computed. */
    public enum Extraction {

        /** In this process, by the face's own extractor. The ordinary answer. */
        IN_PROCESS,

        /**
         * In the database, by the function the release installs there.
         *
         * <p>Declared per type rather than switched on for a whole face, and
         * never a default. What a document is found by is the whole of what a
         * search answers, so a type computed one way where the other was meant
         * goes quietly unfindable rather than loudly wrong — and an empty
         * result is indistinguishable from there being nothing to find. A
         * tenant asks for this when the two sides have been compared over what
         * it holds, which is what config/envelope-baseline.txt records.
         */
        IN_THE_DATABASE
    }

    public FhirTypeConfig(String typeName, IdentityClass identityClass,
            Set<String> identitySystems, Handling handling) {
        this(typeName, identityClass, identitySystems, handling, Extraction.IN_PROCESS);
    }

    /** The same type, computed in the database instead of here. */
    public FhirTypeConfig inTheDatabase() {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, handling,
                Extraction.IN_THE_DATABASE);
    }

    public static FhirTypeConfig identifier(String typeName, String... systems) {
        return new FhirTypeConfig(typeName, IdentityClass.IDENTIFIER, Set.of(systems),
                Handling.operational());
    }

    public static FhirTypeConfig canonical(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.CANONICAL, Set.of(),
                Handling.operational());
    }

    public static FhirTypeConfig internal(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.INTERNAL, Set.of(),
                Handling.operational());
    }

    /** A vocabulary published by a zone: read in this tenant, never written here. */
    public static FhirTypeConfig replicatedCanonical(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.CANONICAL, Set.of(),
                Handling.replicated());
    }

    /**
     * The same type, handled differently — a zone's override, or a test's.
     *
     * <p>Carries the extraction forward. Rebuilding through the short
     * constructor would reset it to {@code IN_PROCESS}, and a tenant that
     * asked for the other one would be served the ordinary answer without
     * anything saying so.
     */
    public FhirTypeConfig handledAs(Handling replacement) {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, replacement,
                extraction);
    }
}
