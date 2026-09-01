package cloud.jengu.dbo.policy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tenant's declared regulatory posture (§15): what is remembered about
 * every action, what may never be unwritten, and when data must go.
 * Validated at parse — a tenant with an incoherent policy never comes up.
 */
public record TenantPolicies(
        AuditLevel audit,
        Discipline writeDiscipline,
        Map<String, Discipline> perTypeDiscipline,
        Map<String, Retention> retention,
        Map<String, String> organisationPaths) {

    /**
     * Compatibility shape: no organisation partitioning declared.
     */
    public TenantPolicies(AuditLevel audit, Discipline writeDiscipline,
            Map<String, Discipline> perTypeDiscipline, Map<String, Retention> retention) {
        this(audit, writeDiscipline, perTypeDiscipline, retention, Map.of());
    }

    public enum AuditLevel { NONE, WRITES, FULL }

    public enum Discipline { STANDARD, APPEND_ONLY }

    /** Floor and ceiling (§15.3): keepAtLeast ≤ removeAfter, both optional. */
    public record Retention(Duration keepAtLeast, Duration removeAfter) {
        public Retention {
            if (keepAtLeast != null && removeAfter != null && keepAtLeast.compareTo(removeAfter) > 0) {
                throw new IllegalArgumentException(
                        "keepAtLeast exceeds removeAfter — the floor cannot outlast the ceiling");
            }
        }
    }

    public TenantPolicies {
        perTypeDiscipline = Map.copyOf(perTypeDiscipline);
        retention = Map.copyOf(retention);
        organisationPaths = Map.copyOf(organisationPaths);
    }

    /**
     * Which reference element says whose a record is: type name to the
     * reference path holding its owning {@code Organization} — the name the
     * envelope's reference edges carry, which is the <b>SearchParameter
     * code</b> with hyphens as underscores: {@code "service_provider"} for an
     * Encounter, {@code "organization"} for a PractitionerRole. Element names
     * never reach the index, so declaring one would silently partition
     * nothing.
     *
     * <p><b>A type with no declared path is not partitioned</b> and every
     * caller sees it, org-bound or not. Deliberate rather than fail-closed:
     * the undeclared types are the shared ones — terminology, config, the
     * organisation tree itself — and an org-bound clinician who cannot read a
     * CodeSystem cannot work. The declaration is where a tenant says which
     * types are somebody's rather than everybody's.
     */
    public String organisationPathFor(String typeName) {
        return organisationPaths.get(typeName);
    }

    public static TenantPolicies defaults() {
        return new TenantPolicies(AuditLevel.NONE, Discipline.STANDARD, Map.of(), Map.of());
    }

    public Discipline disciplineFor(String typeName) {
        return perTypeDiscipline.getOrDefault(typeName, writeDiscipline);
    }

    public boolean auditsWrites() {
        return audit != AuditLevel.NONE;
    }

    public boolean auditsReads() {
        return audit == AuditLevel.FULL;
    }

    /** Human-readable summary for the capability statement (§15.4). */
    public String describe() {
        return "audit=" + audit.name().toLowerCase()
                + "; writeDiscipline=" + writeDiscipline.name().toLowerCase().replace('_', '-')
                + (retention.isEmpty() ? "" : "; retention=" + retention.size() + " type rule(s)");
    }

    /**
     * Parses the spec's policy blocks:
     * {@code "audit":{"level":"writes"}, "writeDiscipline":{"default":"append-only",
     * "perType":{"Task":"standard"}}, "retention":{"perType":{"Encounter":
     * {"keepAtLeast":"P10Y","removeAfter":"P30Y"}}}} — all optional.
     */
    @SuppressWarnings("unchecked")
    public static TenantPolicies parse(Object specRoot) {
        Map<String, Object> root = (Map<String, Object>) specRoot;
        AuditLevel audit = AuditLevel.NONE;
        if (root.get("audit") instanceof Map<?, ?> auditBlock) {
            audit = switch (String.valueOf(auditBlock.get("level"))) {
                case "none" -> AuditLevel.NONE;
                case "writes" -> AuditLevel.WRITES;
                case "full" -> AuditLevel.FULL;
                default -> throw new IllegalArgumentException(
                        "unknown audit level: " + auditBlock.get("level"));
            };
        }
        Discipline discipline = Discipline.STANDARD;
        Map<String, Discipline> perType = new LinkedHashMap<>();
        if (root.get("writeDiscipline") instanceof Map<?, ?> wd) {
            if (wd.get("default") != null) {
                discipline = disciplineOf(String.valueOf(wd.get("default")));
            }
            if (wd.get("perType") instanceof Map<?, ?> overrides) {
                overrides.forEach((type, value) ->
                        perType.put(String.valueOf(type), disciplineOf(String.valueOf(value))));
            }
        }
        Map<String, Retention> retention = new LinkedHashMap<>();
        if (root.get("retention") instanceof Map<?, ?> rt
                && rt.get("perType") instanceof Map<?, ?> perTypeRetention) {
            perTypeRetention.forEach((type, value) -> {
                Map<String, Object> rule = (Map<String, Object>) value;
                retention.put(String.valueOf(type), new Retention(
                        rule.get("keepAtLeast") != null
                                ? period(String.valueOf(rule.get("keepAtLeast"))) : null,
                        rule.get("removeAfter") != null
                                ? period(String.valueOf(rule.get("removeAfter"))) : null));
            });
        }
        Map<String, String> organisationPaths = new LinkedHashMap<>();
        if (root.get("organisations") instanceof Map<?, ?> org
                && org.get("perType") instanceof Map<?, ?> perTypeOrg) {
            perTypeOrg.forEach((type, path) ->
                    organisationPaths.put(String.valueOf(type), String.valueOf(path)));
        }
        return new TenantPolicies(audit, discipline, perType, retention, organisationPaths);
    }

    /**
     * ISO-8601, time- or date-based: {@code PT1H}, {@code P30D}, {@code P10Y}.
     * Calendar units normalise conservatively (year=365d, month=30d) —
     * retention ceilings are policy horizons, not calendar arithmetic.
     */
    private static Duration period(String value) {
        try {
            return Duration.parse(value);
        } catch (java.time.format.DateTimeParseException notADuration) {
            try {
                java.time.Period p = java.time.Period.parse(value);
                return Duration.ofDays(p.getYears() * 365L + p.getMonths() * 30L + p.getDays());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("invalid retention period: " + value, e);
            }
        }
    }

    private static Discipline disciplineOf(String value) {
        return switch (value) {
            case "standard" -> Discipline.STANDARD;
            case "append-only" -> Discipline.APPEND_ONLY;
            default -> throw new IllegalArgumentException("unknown write discipline: " + value);
        };
    }
}
