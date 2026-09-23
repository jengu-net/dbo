package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One tenant's declaration: code, FHIR version, configured types.
 * In production these specs come from configuration (git / operator-managed
 * mounts); the manager watches them as files.
 *
 * <p>{@code zone} names the jurisdiction this tenant is a member of;
 * {@code zoneRoot} says this tenant <b>is</b> one. They are two fields because
 * being a jurisdiction and being in one are not exclusive — a jurisdiction
 * holds ordinary records too — and because a zone that is only ever named by
 * its members is a zone no file records.
 */
public record TenantSpec(String code, String face, List<FhirTypeConfig> types,
        boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
        String zone, String broker, List<String> acceptedBrokers,
        List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
        String managedBy, boolean faceRoot, List<Step> steps, boolean zoneRoot) {

    /**
     * A step this tenant offers, and the documents a run of it is over.
     *
     * <p>Declared here rather than stored as a record because it is a
     * contract, not data: the same reason the types are here. A reader of the
     * file can see what the tenant may be asked to do and over what, without
     * querying it.
     *
     * <p>A slot names <b>a face type</b>, which is narrower than the engine's
     * own step declaration — there a slot is an opaque shape reference the
     * engine hands to a face. This is the narrower thing on purpose: what it
     * configures is a face surface, and the surface has to know which type a
     * slot admits in order to refuse the ones it does not.
     *
     * @param code  the step's own name, as a run of it will be addressed
     * @param slots slot name to the type it takes, in declaration order
     */
    public record Step(String code, java.util.Map<String, String> slots) {
        public Step {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("a step declares a code");
            }
            if (slots.isEmpty()) {
                throw new IllegalArgumentException(code + ": a step with no slots is over "
                        + "nothing, and a run of it would reach nothing — declare what it "
                        + "takes, or do not declare the step");
            }
            slots = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(slots));
        }
    }

    /**
     * Without a declared zone: what every tenant was while a zone was made by
     * its members rather than by itself.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot, List<Step> steps) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, steps, false);
    }

    /**
     * Without offered steps: every tenant before one could be reached through
     * the work it declares rather than through its records directly.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy, boolean faceRoot) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, faceRoot, List.of());
    }

    /**
     * Without a face root: what every tenant was before a version's
     * definitions could be held as records.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps,
            String managedBy) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, managedBy, false);
    }

    /**
     * Without a partner: the shape every tenant had before one tenant could
     * manage others. {@code managedBy} names the partner tenant that may
     * follow this tenant's work — declared here, at creation, never inferred
     * from who happens to be looking.
     */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim, List<String> mandatorySteps) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, mandatorySteps, null);
    }

    /** Compatibility: the pre-mandatory-steps shape. */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies, Scim scim) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, scim, List.of());
    }

    /** Compatibility: the pre-SCIM shape, still what most specs declare. */
    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers,
            List<Dependency> dependencies) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers,
                dependencies, null);
    }

    /**
     * The tenant's SCIM declaration (REQ-DBO-SCIM-DECLARED-PER-TENANT):
     * {@code system} is the identifier namespace externalId values are
     * claimed in. Absent the block, the endpoints do not exist.
     */
    public record Scim(String system) {
        public Scim {
            if (system == null || system.isBlank()) {
                throw new IllegalArgumentException(
                        "scim needs 'system' — the namespace externalId values live in");
            }
        }
    }

    /**
     * A declared content dependency (REQ-DBO-SYNC-SPEC-DECLARED):
     * {@code name} is the direct upstream tenant's code; only the declared
     * types stream. Declarations are configuration — the runtime wires the
     * stream at bring-up and removes it when the declaration disappears.
     */
    /**
     * @param face whether this dependency is the tenant's face chain — the
     *             root it takes its version's definitions from. At most one,
     *             and never the same chain as a jurisdiction: a zone says what
     *             is true here, a face says what a resource is, and a tenant
     *             chooses each on its own.
     */
    public record Dependency(String name, Set<String> types, boolean face) {

        public Dependency(String name, Set<String> types) {
            this(name, types, false);
        }
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

    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies,
            String zone, String broker, List<String> acceptedBrokers) {
        this(code, face, types, pdi, policies, zone, broker, acceptedBrokers, List.of());
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types,
            boolean pdi, cloud.jengu.dbo.policy.TenantPolicies policies) {
        this(code, face, types, pdi, policies, null, null, List.of(), List.of());
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types) {
        this(code, face, types, false, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    public TenantSpec(String code, String face, List<FhirTypeConfig> types, boolean pdi) {
        this(code, face, types, pdi, cloud.jengu.dbo.policy.TenantPolicies.defaults());
    }

    // The platform's tenant-code contract: lowercase label, hyphens, no
    // fixed length cap on the platform side (story tenants carry
    // story+timestamp+nonce and reach ~70 chars).
    // 128 is generous headroom; databaseName() folds ANY length into a
    // Postgres-safe identifier. Underscores stay accepted for old specs.
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]{0,127}");

    /**
     * Whether a string is a tenant code at all.
     *
     * <p>Said once, here, because a second opinion about what a code may look
     * like is a tenant that can be made and not unmade: the drop refused
     * every code with a hyphen in it — most of them — while the spec had
     * accepted them all along.
     */
    public static boolean isCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    public TenantSpec {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid tenant code: " + code);
        }
        // (databaseName() below derives a Postgres-safe name; the code
        // itself only has to be URL- and file-name-safe)
        // Which faces exist is not a spec's business and never was: a closed
        // check here rejected R6 by the name of the requirement asking for it,
        // and a face that is not a FHIR version at all could not be declared.
        // What a face has to be here is a name; whether anything serves it is
        // answered at bring-up by what is installed
        // (REQ-DBO-VER-CONCURRENT-VERSIONS, R6).
        if (face == null || face.isBlank()) {
            throw new IllegalArgumentException(code + ": face is required");
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
        // Each entry must be a well-formed step id NOW, at parse: a typo
        // refused by name here beats one that silently never matches any
        // installed step and holds the tenant down with no visible cause.
        mandatorySteps = List.copyOf(mandatorySteps);
        for (String stepId : mandatorySteps) {
            try {
                cloud.jengu.dbo.core.process.StepId.of(stepId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        code + ": mandatory step — " + e.getMessage());
            }
        }
        // Scim declared without what scim is made of, refused here for the
        // reason a malformed step id is: what a spec can be wrong about on its
        // own, the spec answers for, and a file somebody has to change is
        // better named now than at a bring-up that got further.
        //
        // Two of the three things that door needs are the spec's own — the
        // vault it enumerates through, and the person types its mapping
        // writes. The third is an authority, which is how the deployment is
        // configured rather than anything this file says, so it is not asked
        // about here and cannot be.
        if (scim != null) {
            List<String> missing = new java.util.ArrayList<>();
            if (!pdi) {
                missing.add("pdi (the enumeration answering the user list is a vault method, "
                        + "and without a vault there is nothing to enumerate)");
            }
            Set<String> declared = new java.util.HashSet<>();
            for (FhirTypeConfig type : types) {
                declared.add(type.typeName());
            }
            if (!declared.contains("Person") || !declared.contains("Practitioner")) {
                missing.add("declared Person and Practitioner types (the mapping writes them, "
                        + "and a User is the person with a capacity beside it)");
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(code + ": scim is declared and this spec "
                        + "does not carry what it is made of — " + String.join("; ", missing));
            }
        }
    }

    /**
     * Parses the spec file format: {"code":..,"face":..,
     * "types":[{name,identity,systems?,handling?}],"dependencies":[{name,types}],
     * "mandatorySteps":["&lt;module&gt;.&lt;process&gt;.&lt;step&gt;"]}.
     *
     * <p>{@code mandatorySteps} is the catalogue's one consistency claim
     *: the steps whose absence is an incident rather than normal
     * elasticity. The tenant serves and its runs queue either way — the list
     * classifies ({@link StepIncidents}), it never gates — and every step not
     * listed is non-critical by construction.
     *
     * <p><b>{@code handling} is required</b>. A type that
     * has not said what kind of data it is stops the tenant coming up, named,
     * rather than being guessed at — the same rule
     * {@link cloud.jengu.dbo.core.api.TypeRegistration} applies to types
     * declared in code, now applied to types declared in a spec.
     *
     * <p>There is no default because a default here is a silent decision about
     * a hospital's data: guessing {@code operational} would let a tenant edit a
     * vocabulary it does not own, and guessing anything stricter would refuse
     * writes nobody could explain. An unknown value is refused for the same
     * reason a missing one is — a typo must not become a classification.
     */
    public static TenantSpec parse(String json) {
        Object root = Json.parse(json);
        String code = Json.str(root, "code");
        // The field selects a face, and a face need not be a FHIR version, so
        // the old name is refused rather than honoured. Accepting both would
        // be the kindness that survives a decade: two names for one field,
        // and a reader with no way to know which one this deployment obeys.
        if (Json.strOpt(root, "fhirVersion") != null) {
            throw new IllegalArgumentException(code + ": 'fhirVersion' selects a face and is "
                    + "named 'face'; a face need not be a FHIR version at all");
        }
        String face = Json.str(root, "face");
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
            if (handling == null) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": no declared handling — say what kind of data this is (who may "
                        + "write it, whether it may change, whether it is kept, whether it "
                        + "may leave). There is no default: guessing would be a silent "
                        + "decision about somebody's data");
            }
            String extractor = Json.strOpt(t, "extractor");
            if (extractor != null && !"database".equals(extractor)
                    && !"in-process".equals(extractor)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown extractor " + extractor + " — say 'database' to have the "
                        + "envelope computed where the bytes are, or leave it out");
            }
            FhirTypeConfig placed = "database".equals(extractor)
                    ? config.inTheDatabase() : config;
            String definition = Json.strOpt(t, "definition");
            if (definition != null && !"none".equals(definition)
                    && !"by-the-face".equals(definition)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown definition " + definition + " — say 'none' for a type "
                        + "this face has no definition for, or leave it out");
            }
            if ("none".equals(definition)) {
                placed = placed.withoutADefinition();
            }
            String unknown = Json.strOpt(t, "unknown");
            if (unknown != null && !"refused".equals(unknown) && !"kept".equals(unknown)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown unknown " + unknown + " — say 'kept' to hold an element "
                        + "this type's definition does not declare, or leave it out and it "
                        + "is refused");
            }
            if ("kept".equals(unknown)) {
                placed = placed.keepingWhatItCannotRead();
            }
            String verdict = Json.strOpt(t, "verdict");
            if (verdict != null && !"database".equals(verdict)
                    && !"toolchain".equals(verdict)) {
                throw new IllegalArgumentException(code + "/" + name
                        + ": unknown verdict " + verdict + " — say 'database' to have this "
                        + "store's own checks decide a write of this type, or leave it out");
            }
            if ("database".equals(verdict)) {
                placed = placed.decidedByTheDatabase();
            }
            return placed.handledAs(switch (handling) {
                case "operational" -> Handling.operational();
                case "projected-config" -> Handling.projectedConfig();
                case "replicated" -> Handling.replicated();
                case "mirrored" -> Handling.mirrored();
                case "store-authored" -> Handling.storeAuthored();
                case "audit" -> Handling.audit();
                case "ephemeral" -> Handling.ephemeral();
                default -> throw new IllegalArgumentException(
                        code + "/" + name + ": unknown handling " + handling);
            });
        }).toList();
        List<Dependency> dependencies = Json.array(root, "dependencies").stream()
                .map(d -> new Dependency(Json.str(d, "name"),
                        Set.copyOf(Json.strings(d, "types")), Json.bool(d, "face")))
                .toList();
        if (dependencies.stream().filter(Dependency::face).count() > 1) {
            throw new IllegalArgumentException(code + ": two dependencies are declared as the "
                    + "face chain, and a tenant is one version — name one");
        }
        Object scimNode = Json.objOpt(root, "scim");
        Scim scim = scimNode == null ? null : new Scim(Json.str(scimNode, "system"));
        boolean pdi = Json.bool(root, "pdi");
        if (scim != null && !pdi) {
            // A staff directory is identifying data by definition; serving it
            // from a store that keeps identity in the clear would be a quiet
            // decision about everybody in it.
            throw new IllegalArgumentException(code + ": scim requires pdi");
        }
        if (scim != null) {
            // What identifies a person is declared ONCE, on the type. This
            // only says which of Person's systems the directory speaks — a
            // selector, not a second declaration.
            //
            // They used to be two. `scim.system` named a namespace nowhere
            // else mentioned, the membrane read only the type's own systems,
            // and the directory's uniqueness quietly went missing: a second
            // User claiming one employee's externalId was created rather than
            // refused, and lookup by it answered nothing. A rule that has to
            // be remembered in two vocabularies is one that gets remembered in
            // one of them.
            // A tenant declaring no Person at all is left to bring-up, which
            // already records that scim is declared and unservable. This is
            // about the declaration disagreeing with itself, not about the one
            // that is missing.
            java.util.Optional<cloud.jengu.dbo.fhir.common.FhirTypeConfig> person = types.stream()
                    .filter(t -> "Person".equals(t.typeName()))
                    .findFirst();
            if (person.isPresent() && !person.get().identitySystems().contains(scim.system())) {
                throw new IllegalArgumentException(code + ": scim speaks '" + scim.system()
                        + "' and Person is not declared identified by it. Declare Person "
                        + "with \"identity\":\"identifier\" and that system among its "
                        + "\"systems\" — what identifies somebody is said once, on the type, "
                        + "and scim names which of those the directory uses");
            }
        }
        // The steps this tenant offers, and what each is over. A slot naming a
        // type the tenant does not hold is refused here rather than at the
        // first run of it: the spec is where a declaration disagreeing with
        // itself is cheapest to find.
        List<Step> steps = new java.util.ArrayList<>();
        {
            java.util.Set<String> held = types.stream()
                    .map(cloud.jengu.dbo.fhir.common.FhirTypeConfig::typeName)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object one : Json.array(root, "steps")) {
                String stepCode = Json.str(one, "code");
                // A step's name is the engine's, so it is checked against the
                // engine's rule here rather than at the first run of it: the
                // spec is read once and the run is attempted under load.
                cloud.jengu.dbo.core.process.StepId.of(stepCode);
                if (!seen.add(stepCode)) {
                    throw new IllegalArgumentException(code + ": step '" + stepCode
                            + "' is declared twice, and a run addressed by that name could "
                            + "not say which was meant");
                }
                java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
                if (Json.objOpt(one, "slots") instanceof java.util.Map<?, ?> named) {
                    named.forEach((slot, type) -> slots.put(String.valueOf(slot),
                            String.valueOf(type)));
                }
                for (java.util.Map.Entry<String, String> slot : slots.entrySet()) {
                    if (!held.contains(slot.getValue())) {
                        throw new IllegalArgumentException(code + ": step '" + stepCode
                                + "' takes '" + slot.getValue() + "' in slot '" + slot.getKey()
                                + "', and this tenant does not declare that type. It holds: "
                                + new java.util.TreeSet<>(held));
                    }
                }
                steps.add(new Step(stepCode, slots));
            }
        }
        return new TenantSpec(code, face, types, pdi,
                cloud.jengu.dbo.policy.TenantPolicies.parse(root),
                Json.strOpt(root, "zone"), Json.strOpt(root, "broker"),
                Json.strings(root, "acceptedBrokers"), dependencies, scim,
                Json.strings(root, "mandatorySteps"), Json.strOpt(root, "managedBy"),
                // A face root holds its version's definitions as records —
                // the one place the carried packages are ever read.
                Json.bool(root, "faceRoot"), List.copyOf(steps),
                // A zone says it is one. Members name it, and naming is not
                // appointing: see the check at bring-up.
                Json.bool(root, "zoneRoot"));
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
