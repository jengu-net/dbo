package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.sync.ConfigApplication;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A zone reaching a face it was not written in, converted once.
 *
 * <p>A zone publishes in one version. Its tenants are on whichever face each
 * of them chose, so a zone written in R5 has R4 tenants that must enforce its
 * profiles as R4 definitions, against R4 records. Every one of those tenants
 * could convert the zone for itself — the stream has always been able to — and
 * then a zone with twenty tenants on a face converts the same documents twenty
 * times, to twenty copies that had better agree.
 *
 * <p>So it is converted once, by a tenant that exists to do it: one projection
 * per zone per face, taking the zone and standing on the target face. The
 * zone's tenants on that face take it from there, already converted, and
 * convert nothing themselves.
 *
 * <p><b>Nobody declares these.</b> They follow from what is already declared —
 * a zone's version, and the faces of the tenants that asked for it — so
 * working them out is arithmetic rather than configuration. Made to be
 * declared, every deployment would owe a projection per zone per face, and the
 * failure of forgetting one is a tenant quietly converting for itself again.
 *
 * <p><b>And only when the versions differ.</b> A zone serving a face it was
 * written in needs nothing done to it, and putting a projection in the way
 * would add a hop, a database and a second copy for no conversion at all.
 */
final class ZoneProjections {

    private ZoneProjections() {}

    /** What a projection is called: the zone, as seen on a face. */
    static String codeFor(String zone, String face) {
        return zone + "-on-" + face;
    }

    /**
     * The projections these declarations imply, as declarations of their own.
     *
     * <p>Returned to be appended to what was declared, so a projection comes
     * up by the path every tenant comes up by: same provisioning, same
     * bring-up, same readiness. A thing that is a tenant should be brought up
     * as one rather than through a second mechanism that will drift.
     */
    static List<ConfigApplication.Declared> neededBy(List<ConfigApplication.Declared> declarations) {
        Map<String, TenantSpec> byCode = parsed(declarations);
        // zone + face -> what the tenants on that face ask the zone for
        Map<String, Wanted> wanted = new TreeMap<>();
        for (TenantSpec spec : byCode.values()) {
            for (TenantSpec.Dependency dependency : spec.dependencies()) {
                if (dependency.face()) {
                    continue; // a face chain does not convert
                }
                TenantSpec zone = byCode.get(dependency.name());
                if (zone == null || zone.face().equals(spec.face())) {
                    // Not declared here, or already on this face: nothing to
                    // convert, so nothing to convert once.
                    continue;
                }
                wanted.computeIfAbsent(codeFor(dependency.name(), spec.face()),
                                ignored -> new Wanted(dependency.name(), spec.face()))
                        .add(dependency.types(), spec.types());
            }
        }

        List<ConfigApplication.Declared> extra = new ArrayList<>();
        for (Map.Entry<String, Wanted> one : wanted.entrySet()) {
            if (byCode.containsKey(one.getKey())) {
                continue; // somebody declared it after all; theirs wins
            }
            extra.add(new ConfigApplication.Declared(TenantDeclarationModel.TYPE,
                    one.getKey() + ".json",
                    one.getValue().spec(one.getKey()).getBytes(StandardCharsets.UTF_8)));
        }
        return extra;
    }

    /**
     * Where a tenant's zone dependency actually reads from.
     *
     * <p>The declaration is left alone: a tenant asks for the zone, and which
     * projection serves it follows from its own face. A tenant that had to name
     * the projection would have to be re-declared whenever it changed face,
     * and would be naming something it does not own.
     */
    static String servedBy(TenantSpec dependent, TenantSpec.Dependency dependency,
            String upstreamFace) {
        if (dependency.face() || dependent.face().equals(upstreamFace)) {
            return dependency.name();
        }
        String projection = codeFor(dependency.name(), dependent.face());
        // The projection reads the zone itself: it IS the hop that converts,
        // and routing it through itself is a tenant waiting on its own
        // bring-up forever.
        return projection.equals(dependent.code()) ? dependency.name() : projection;
    }

    // ------------------------------------------------------------ the spec

    /** One projection's subject: the zone, the face, and what is asked of it. */
    private static final class Wanted {
        private final String zone;
        private final String face;
        private final Set<String> types = new TreeSet<>();
        private final Map<String, FhirTypeConfig> asDeclared = new LinkedHashMap<>();

        Wanted(String zone, String face) {
            this.zone = zone;
            this.face = face;
        }

        /**
         * The union of what the tenants on this face ask the zone for.
         *
         * <p>A projection carrying less than one of its dependents wants would
         * leave that tenant short of a zone it declared, and the tenant would
         * have no way to tell: it asked the zone and was answered by
         * something else.
         */
        void add(Set<String> dependencyTypes, List<FhirTypeConfig> dependentTypes) {
            types.addAll(dependencyTypes);
            for (FhirTypeConfig type : dependentTypes) {
                if (dependencyTypes.contains(type.typeName())) {
                    asDeclared.putIfAbsent(type.typeName(), type);
                }
            }
        }

        String spec(String code) {
            StringBuilder types = new StringBuilder();
            for (String name : this.types) {
                FhirTypeConfig declared = asDeclared.get(name);
                if (declared == null) {
                    continue; // asked for by a dependency and declared by nobody
                }
                if (!types.isEmpty()) {
                    types.append(",\n  ");
                }
                types.append(typeOf(declared));
            }
            return """
                    {"code":"%s","face":"%s","audit":{"level":"none"},
                     "dependencies":[{"name":"%s","types":[%s]}],
                     "types":[%s]}"""
                    .formatted(code, face, zone, quoted(this.types), types);
        }

        /**
         * Every type as its dependents declared it, with the handling a copy
         * has: a projection authors nothing, it carries.
         */
        private static String typeOf(FhirTypeConfig type) {
            StringBuilder systems = new StringBuilder();
            for (String system : new TreeSet<>(type.identitySystems())) {
                if (!systems.isEmpty()) {
                    systems.append(',');
                }
                systems.append('"').append(system).append('"');
            }
            return ("{\"name\":\"%s\",\"identity\":\"%s\",\"systems\":[%s],"
                    + "\"handling\":\"replicated\"}")
                    .formatted(type.typeName(),
                            type.identityClass().name().toLowerCase(), systems);
        }

        private static String quoted(Set<String> names) {
            StringBuilder out = new StringBuilder();
            for (String name : names) {
                if (!out.isEmpty()) {
                    out.append(',');
                }
                out.append('"').append(name).append('"');
            }
            return out.toString();
        }
    }

    private static Map<String, TenantSpec> parsed(List<ConfigApplication.Declared> declarations) {
        Map<String, TenantSpec> byCode = new LinkedHashMap<>();
        for (ConfigApplication.Declared declaration : declarations) {
            try {
                TenantSpec spec = TenantSpec.parse(
                        new String(declaration.payload(), StandardCharsets.UTF_8));
                byCode.put(spec.code(), spec);
            } catch (RuntimeException unreadable) {
                // Reported where declarations are read; a projection cannot be
                // worked out from a spec nobody can parse, and pretending
                // otherwise would turn one broken declaration into two.
            }
        }
        return byCode;
    }

    /** Whether a code names a projection this store made rather than a declared tenant. */
    static boolean isProjection(String code, Set<String> declaredCodes) {
        return !declaredCodes.contains(code) && code.contains("-on-");
    }

    /** Every distinct face named by these declarations, for tests and reports. */
    static Set<String> facesIn(List<ConfigApplication.Declared> declarations) {
        Set<String> faces = new LinkedHashSet<>();
        parsed(declarations).values().forEach(spec -> faces.add(spec.face()));
        return faces;
    }
}
