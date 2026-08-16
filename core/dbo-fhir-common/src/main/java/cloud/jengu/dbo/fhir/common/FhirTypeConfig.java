package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;

import java.util.Set;

/**
 * Per-type identity configuration (§12): which identity class the type
 * carries, and — for IDENTIFIER — its designated identity-bearing systems in
 * trust order. Supplied by tenant/zone configuration in production. Version-neutral: any personality consumes it.
 *
 * <p>Also carries the type's {@link Handling} — what kind of data it is
 * (jengu-platform#869). The factories below state {@code operational()}
 * rather than defaulting to it silently: a clinical resource written by the
 * tenant's own people is the ordinary case, and everything else says so.
 * Terminology replicated from a zone uses {@link #replicatedCanonical}, and
 * types written from two directions at once — {@code Practitioner} above all —
 * wait on per-field authority (jengu-platform#871) rather than being forced
 * into one answer here.
 */
public record FhirTypeConfig(String typeName, IdentityClass identityClass,
                             Set<String> identitySystems, Handling handling) {

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

    /** The same type, handled differently — a zone's override, or a test's. */
    public FhirTypeConfig handledAs(Handling replacement) {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, replacement);
    }
}
