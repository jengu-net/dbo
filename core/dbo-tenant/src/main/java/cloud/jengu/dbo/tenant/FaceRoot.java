package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.face.GrainCodec;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A tenant that holds its version's definitions as records, so that other
 * tenants can subscribe to them the way they subscribe to a zone.
 *
 * <p>A version used to be an object graph a node loaded: two hundred
 * megabytes per version, copied per tenant, regenerated at every bring-up.
 * A face root turns it into what everything else definitional already is
 * here — records, in one tenant's store, replicated to whoever declares a
 * dependency on it, and held in a tenant's own database rather than in the
 * heap of whichever node happens to be serving it.
 *
 * <p>Loaded once. A root that already holds a definition is not loaded again:
 * the carried packages are a release's bytes, and a second boot reading them
 * over the records would either duplicate what is there or overwrite what a
 * later package delivered through the feed.
 */
final class FaceRoot {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FaceRoot.class);

    /** How many definitions travel in one transaction. */
    private static final int BATCH = 200;

    private FaceRoot() {
    }

    /**
     * Fills the root from its face's carried packages, unless it is filled.
     *
     * <p>Written through the engine and not through the facade, on purpose:
     * these are the definitions everything else is validated against, and
     * there is nothing yet to validate them against. That is the fixed point,
     * and it is closed the other way round — after the batch, every
     * definition's base must be among what was loaded. A package whose
     * profiles are built on definitions it does not carry is inconsistent
     * with itself, which is a fact about the package.
     *
     * @return what was loaded, by type; empty when the root was already full
     */
    static Map<String, Integer> load(TenantSpec spec, ObjectStore engine, FhirStoreFacade store,
            cloud.jengu.dbo.core.face.GrainCodec grain) {
        Set<String> declared = new LinkedHashSet<>();
        for (FhirTypeConfig type : spec.types()) {
            declared.add(type.typeName());
        }
        // A root holds the version, and a version is the four: a root that
        // declared structures without the code systems their bindings name
        // would publish a chain whose subscribers accept `gender: unicorn`.
        for (String critical : TenantRuntimeManager.CRITICAL_ON_THE_FACE) {
            if (!declared.contains(critical)) {
                throw new IllegalStateException(spec.code() + " is declared a face root and does "
                        + "not declare " + critical + ", which the version it holds is made of");
            }
        }
        List<FaceRootPackages.Definition> definitions =
                FaceRootPackages.definitionsFor(spec.face(), declared);
        // Checked before a row is written. Checked after, a refused package
        // left every row behind, and the next attempt found the version
        // "already held" and came up over the refusal — the check had been
        // failing on every boot and nobody had seen it.
        selfConsistent(definitions);
        // What is here already is not written again, definition by
        // definition: a load is resumed where it stopped rather than skipped
        // because something of it is there. A marker — the version's root
        // definition — said "held" after any attempt that got past the
        // structures, and a root whose terminology had failed to land came up
        // on the next boot serving a version it held half of.
        Map<String, Set<String>> held = new java.util.HashMap<>();
        for (String type : declared) {
            Set<String> urls = new HashSet<>();
            try {
                for (cloud.jengu.dbo.core.api.Held one : engine.inventory(type, List.of())) {
                    one.identifiers().stream()
                            .filter(i -> cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM.equals(i.system()))
                            .forEach(i -> urls.add(i.value()));
                }
            } catch (RuntimeException notRegistered) {
                // a declared type the engine does not serve is refused elsewhere
            }
            held.put(type, urls);
        }
        definitions = definitions.stream()
                .filter(d -> d.url() == null || !held.getOrDefault(d.typeName(), Set.of()).contains(d.url()))
                .toList();
        if (definitions.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> loaded = new TreeMap<>();
        // Everything but the code systems first, whole — a value set's record
        // is its stored form — then the view is rebuilt from them, and only
        // then the code systems, taken apart by the face's own grain, which
        // reads one through the view. In that order the view is built from
        // the records just written and never from the carried packages; the
        // base the version's tenants share is built with the value sets in
        // it, so a binding can be resolved to the value set it names; and a
        // root's code systems land where its bindings are answered from: the
        // concepts natively, the shell as a record. The value sets' composes
        // are kept natively after the view exists, for the same reason.
        List<FaceRootPackages.Definition> shapes = new ArrayList<>();
        List<FaceRootPackages.Definition> codeSystems = new ArrayList<>();
        for (FaceRootPackages.Definition definition : definitions) {
            ("CodeSystem".equals(definition.typeName()) && grain != null ? codeSystems : shapes)
                    .add(definition);
        }
        List<PutRequest> batch = new ArrayList<>(BATCH);
        for (FaceRootPackages.Definition definition : shapes) {
            batch.add(PutRequest.create(definition.typeName(), definition.document()));
            loaded.merge(definition.typeName(), 1, Integer::sum);
            if (batch.size() == BATCH) {
                engine.transact(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.transact(batch);
            batch.clear();
        }
        store.shapesChanged();
        if (grain != null) {
            grain.keep(shapes.stream().filter(d -> grain.handles(d.typeName()))
                    .map(d -> new GrainCodec.Part(d.typeName(), d.document())).toList());
        }
        List<GrainCodec.Part> kept = new ArrayList<>(BATCH);
        for (FaceRootPackages.Definition definition : codeSystems) {
            batch.add(PutRequest.create(definition.typeName(),
                    grain.storedFormOf(definition.typeName(), definition.document())));
            kept.add(new GrainCodec.Part(definition.typeName(), definition.document()));
            loaded.merge(definition.typeName(), 1, Integer::sum);
            if (batch.size() == BATCH) {
                engine.transact(batch);
                grain.keep(kept);
                batch.clear();
                kept.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.transact(batch);
            grain.keep(kept);
        }
        LOG.info("face root {} holds {} as records: {}", spec.code(), spec.face(), loaded);
        return loaded;
    }

    /**
     * Every base a definition names is a definition that was loaded.
     *
     * <p>Logical models are exempt: a tooling package ships models of its
     * own — the CDS Hooks context models, built on a {@code Base} that the
     * R4 core does not have — and a logical model is not a shape any
     * resource is validated against, so a base it cannot reach costs the
     * version nothing it validates with.
     */
    private static void selfConsistent(List<FaceRootPackages.Definition> definitions) {
        Set<String> held = new HashSet<>();
        for (FaceRootPackages.Definition definition : definitions) {
            if (definition.url() != null) {
                held.add(definition.url());
            }
        }
        List<String> orphaned = new ArrayList<>();
        for (FaceRootPackages.Definition definition : definitions) {
            if (!"StructureDefinition".equals(definition.typeName())) {
                continue;
            }
            String document = new String(definition.document(), java.nio.charset.StandardCharsets.UTF_8);
            if (document.matches("(?s).*\"kind\"\\s*:\\s*\"logical\".*")) {
                continue;
            }
            java.util.regex.Matcher base = java.util.regex.Pattern
                    .compile("\"baseDefinition\"\\s*:\\s*\"([^\"]+)\"").matcher(document);
            if (base.find() && !held.contains(base.group(1))) {
                orphaned.add(definition.url() + " is built on " + base.group(1));
            }
        }
        if (!orphaned.isEmpty()) {
            throw new IllegalStateException("the face's packages are not consistent with "
                    + "themselves — " + orphaned.size() + " definitions are built on bases the "
                    + "packages do not carry, so nothing built on them could be validated: "
                    + String.join("; ", orphaned.subList(0, Math.min(5, orphaned.size()))));
        }
    }
}
