package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One tenant's declaration (dbo#17): code, FHIR version, configured types.
 * In production these specs come from configuration (git / operator-managed
 * mounts); the manager watches them as files.
 */
public record TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types,
        boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
        String zone, String broker, List<String> acceptedBrokers,
        List<Dependency> dependencies) {

    /**
     * A declared content dependency (dbo#30, REQ-DBO-SYNC-SPEC-DECLARED):
     * {@code name} is the direct upstream tenant's code; only the declared
     * types stream. Declarations are configuration — the runtime wires the
     * stream at bring-up and removes it when the declaration disappears.
     */
    public record Dependency(String name, Set<String> types) {
        public Dependency {
            if (name == null || !CODE.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid dependency name: " + name);
            }
            types = Set.copyOf(types);
            if (types.isEmpty()) {
                throw new IllegalArgumentException(
                        name + ": a dependency must declare at least one type");
            }
        }
    }

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers) {
        this(code, fhirVersion, types, pdi, policies, zone, broker, acceptedBrokers, List.of());
    }

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies) {
        this(code, fhirVersion, types, pdi, policies, null, null, List.of(), List.of());
    }

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types) {
        this(code, fhirVersion, types, false, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types, boolean pdi) {
        this(code, fhirVersion, types, pdi, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    // The platform's tenant-code contract: lowercase label, hyphens, no
    // fixed length cap on the platform side (story tenants carry
    // story+timestamp+nonce and reach ~70 chars — jengu-platform#848).
    // 128 is generous headroom; databaseName() folds ANY length into a
    // Postgres-safe identifier. Underscores stay accepted for old specs.
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]{0,127}");

    public TenantSpec {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid tenant code: " + code);
        }
        // (databaseName() below derives a Postgres-safe name; the code
        // itself only has to be URL- and file-name-safe)
        if (!"r4".equals(fhirVersion) && !"r5".equals(fhirVersion)) {
            throw new IllegalArgumentException(code + ": unsupported fhirVersion " + fhirVersion);
        }
        types = List.copyOf(types);
        if (types.isEmpty()) {
            throw new IllegalArgumentException(code + ": at least one type required");
        }
        dependencies = List.copyOf(dependencies);
        for (Dependency dependency : dependencies) {
            if (dependency.name().equals(code)) {
                throw new IllegalArgumentException(code + ": cannot depend on itself");
            }
        }
    }

    /**
     * Parses the spec file format: {"code":..,"fhirVersion":..,
     * "types":[{name,identity,systems?,handling?}],"dependencies":[{name,types}]}.
     *
     * <p><b>{@code handling} is optional here and should not stay that way.</b>
     * A type that does not say what kind of data it is ought to stop the
     * platform (jengu-platform#869) — and does, for types declared in code,
     * where {@link cloud.jengu.dbo.core.api.TypeRegistration} refuses to build
     * without it. At this layer the declarations live in the configuration
     * repository, so requiring the field means updating those specs first;
     * until then an absent field means {@code operational}, which is stated
     * here rather than assumed silently. An <em>unknown</em> value is refused
     * outright: a typo must not fall through to the default.
     */
    public static TenantSpec parse(String json) {
        Object root = Json.parse(json);
        String code = Json.str(root, "code");
        String fhirVersion = Json.str(root, "fhirVersion");
        List<FhirTypeConfig> types = Json.array(root, "types").stream().map(t -> {
            String name = Json.str(t, "name");
            String identity = Json.str(t, "identity");
            Set<String> systems = Set.copyOf(Json.strings(t, "systems"));
            FhirTypeConfig config = switch (identity) {
                case "identifier" -> new FhirTypeConfig(name, IdentityClass.IDENTIFIER, systems,
                        Handling.operational());
                case "canonical" -> FhirTypeConfig.canonical(name);
                case "internal" -> FhirTypeConfig.internal(name);
                default -> throw new IllegalArgumentException(
                        code + "/" + name + ": unknown identity class " + identity);
            };
            String handling = Json.strOpt(t, "handling");
            return handling == null ? config : config.handledAs(switch (handling) {
                case "operational" -> Handling.operational();
                case "projected-config" -> Handling.projectedConfig();
                case "replicated" -> Handling.replicated();
                case "store-authored" -> Handling.storeAuthored();
                case "audit" -> Handling.audit();
                case "ephemeral" -> Handling.ephemeral();
                default -> throw new IllegalArgumentException(
                        code + "/" + name + ": unknown handling " + handling);
            });
        }).toList();
        List<Dependency> dependencies = Json.array(root, "dependencies").stream()
                .map(d -> new Dependency(Json.str(d, "name"),
                        Set.copyOf(Json.strings(d, "types"))))
                .toList();
        return new TenantSpec(code, fhirVersion, types, Json.bool(root, "pdi"),
                cloud.jengu.dbo.policy.TenantPolicies.parse(root),
                Json.strOpt(root, "zone"), Json.strOpt(root, "broker"),
                Json.strings(root, "acceptedBrokers"), dependencies);
    }

    /**
     * The Postgres database name for a tenant code: {@code tenant_<code>}
     * with hyphens folded to underscores, and — when that would exceed
     * Postgres' 63-byte identifier limit (which TRUNCATES silently, so two
     * long codes sharing a prefix would collide) — truncated with a
     * deterministic hash suffix. Pure function of the code: every
     * provisioner and every restart derives the same name.
     */
    public static String databaseName(String code) {
        String name = "tenant_" + code.replace('-', '_');
        if (name.length() <= 63) {
            return name;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hash.append(String.format("%02x", digest[i]));
            }
            return name.substring(0, 50) + "_" + hash;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String databaseName() {
        return databaseName(code);
    }
}
