package cloud.jengu.dbo.tenant;

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
        String zone, String broker, List<String> acceptedBrokers) {

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies) {
        this(code, fhirVersion, types, pdi, policies, null, null, List.of());
    }

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types) {
        this(code, fhirVersion, types, false, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    public TenantSpec(String code, String fhirVersion, List<FhirTypeConfig> types, boolean pdi) {
        this(code, fhirVersion, types, pdi, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,15}");

    public TenantSpec {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid tenant code: " + code);
        }
        if (!"r4".equals(fhirVersion) && !"r5".equals(fhirVersion)) {
            throw new IllegalArgumentException(code + ": unsupported fhirVersion " + fhirVersion);
        }
        types = List.copyOf(types);
        if (types.isEmpty()) {
            throw new IllegalArgumentException(code + ": at least one type required");
        }
    }

    /** Parses the spec file format: {"code":..,"fhirVersion":..,"types":[{name,identity,systems?}]}. */
    public static TenantSpec parse(String json) {
        Object root = Json.parse(json);
        String code = Json.str(root, "code");
        String fhirVersion = Json.str(root, "fhirVersion");
        List<FhirTypeConfig> types = Json.array(root, "types").stream().map(t -> {
            String name = Json.str(t, "name");
            String identity = Json.str(t, "identity");
            Set<String> systems = Set.copyOf(Json.strings(t, "systems"));
            return switch (identity) {
                case "identifier" -> new FhirTypeConfig(name, IdentityClass.IDENTIFIER, systems);
                case "canonical" -> FhirTypeConfig.canonical(name);
                case "internal" -> FhirTypeConfig.internal(name);
                default -> throw new IllegalArgumentException(
                        code + "/" + name + ": unknown identity class " + identity);
            };
        }).toList();
        return new TenantSpec(code, fhirVersion, types, Json.bool(root, "pdi"),
                cloud.jengu.dbo.policy.TenantPolicies.parse(root),
                Json.strOpt(root, "zone"), Json.strOpt(root, "broker"),
                Json.strings(root, "acceptedBrokers"));
    }
}
