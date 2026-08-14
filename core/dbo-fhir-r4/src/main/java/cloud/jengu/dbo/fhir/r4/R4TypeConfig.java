package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.core.api.IdentityClass;

import java.util.Set;

/**
 * Per-type identity configuration (§12): which identity class the type
 * carries, and — for IDENTIFIER — its designated identity-bearing systems in
 * trust order. Supplied by tenant/zone configuration in production.
 */
public record R4TypeConfig(String typeName, IdentityClass identityClass, Set<String> identitySystems) {

    public static R4TypeConfig identifier(String typeName, String... systems) {
        return new R4TypeConfig(typeName, IdentityClass.IDENTIFIER, Set.of(systems));
    }

    public static R4TypeConfig canonical(String typeName) {
        return new R4TypeConfig(typeName, IdentityClass.CANONICAL, Set.of());
    }

    public static R4TypeConfig internal(String typeName) {
        return new R4TypeConfig(typeName, IdentityClass.INTERNAL, Set.of());
    }
}
