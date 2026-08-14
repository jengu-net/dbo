package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.IdentityClass;

import java.util.Set;

/**
 * Per-type identity configuration (§12): which identity class the type
 * carries, and — for IDENTIFIER — its designated identity-bearing systems in
 * trust order. Supplied by tenant/zone configuration in production. Version-neutral: any personality consumes it.
 */
public record FhirTypeConfig(String typeName, IdentityClass identityClass, Set<String> identitySystems) {

    public static FhirTypeConfig identifier(String typeName, String... systems) {
        return new FhirTypeConfig(typeName, IdentityClass.IDENTIFIER, Set.of(systems));
    }

    public static FhirTypeConfig canonical(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.CANONICAL, Set.of());
    }

    public static FhirTypeConfig internal(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.INTERNAL, Set.of());
    }
}
