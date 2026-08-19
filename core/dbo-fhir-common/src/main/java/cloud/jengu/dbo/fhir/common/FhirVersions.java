package cloud.jengu.dbo.fhir.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The versions this container can serve, by code.
 *
 * <p>In the container it is backed by the service registry, so installing a
 * face bundle adds a version and uninstalling one removes it. On a plain
 * classpath — a test, the bench — it is the versions the caller passed. Both
 * answer the same question, which is the one that replaced a validator's list
 * of two strings: is anything registered under this code.
 */
public interface FhirVersions {

    Optional<FhirVersion> byCode(String code);

    /** What is registered now, for a refusal that says what would have worked. */
    Set<String> codes();

    /**
     * The version, or a refusal naming what is installed.
     *
     * <p>"unsupported" and "not installed" look identical to a caller who is
     * only told no, and they have different fixes.
     */
    default FhirVersion require(String code) {
        return byCode(code).orElseThrow(() -> new UnknownVersion(code, codes()));
    }

    /** A fixed set — what a test or a single-assembly runtime has. */
    static FhirVersions of(FhirVersion... versions) {
        Map<String, FhirVersion> byCode = new LinkedHashMap<>();
        for (FhirVersion version : versions) {
            if (byCode.putIfAbsent(version.code(), version) != null) {
                throw new IllegalArgumentException(
                        "two versions registered under '" + version.code()
                                + "' — which one serves a tenant would depend on ordering");
            }
        }
        Map<String, FhirVersion> fixed = Map.copyOf(byCode);
        return new FhirVersions() {
            @Override
            public Optional<FhirVersion> byCode(String code) {
                return Optional.ofNullable(fixed.get(code));
            }

            @Override
            public Set<String> codes() {
                return fixed.keySet();
            }
        };
    }

    /**
     * What the classpath declares, for a runtime that has no service registry
     * — a test, the bench, a single-jar assembly. Face bundles declare
     * themselves as {@link java.util.ServiceLoader} providers, so this asks the
     * same question the registry answers without anybody naming a version.
     *
     * <p><b>Not</b> the container's path. Inside OSGi a bundle's own loader
     * sees no other bundle's providers, and the answer would be "none" rather
     * than an error — so the container passes the registry-backed set
     * explicitly and never falls back to this.
     */
    static FhirVersions installed() {
        return of(java.util.ServiceLoader.load(FhirVersion.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .toArray(FhirVersion[]::new));
    }

    /** A tenant declared a version this container has no face for. */
    class UnknownVersion extends IllegalArgumentException {
        public UnknownVersion(String code, Set<String> installed) {
            super("no FHIR version is registered under '" + code + "' — this container serves "
                    + (installed.isEmpty() ? "none" : String.join(", ", installed))
                    + ". A version is a face bundle: install it, or correct the spec.");
        }
    }
}
