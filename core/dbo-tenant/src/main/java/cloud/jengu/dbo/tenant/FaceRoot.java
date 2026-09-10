package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
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
    static Map<String, Integer> load(TenantSpec spec, ObjectStore engine, FhirStoreFacade store) {
        Set<String> declared = new LinkedHashSet<>();
        for (FhirTypeConfig type : spec.types()) {
            declared.add(type.typeName());
        }
        if (alreadyHoldsTheVersion(engine)) {
            return Map.of();
        }
        List<FaceRootPackages.Definition> definitions =
                FaceRootPackages.definitionsFor(spec.face(), declared);
        Map<String, Integer> loaded = new TreeMap<>();
        List<PutRequest> batch = new ArrayList<>(BATCH);
        for (FaceRootPackages.Definition definition : definitions) {
            batch.add(PutRequest.create(definition.typeName(), definition.document()));
            loaded.merge(definition.typeName(), 1, Integer::sum);
            if (batch.size() == BATCH) {
                engine.transact(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.transact(batch);
        }
        selfConsistent(definitions);
        // The view was built before the records existed; it is rebuilt once,
        // not once per definition, which is why the batch went past the
        // facade.
        store.shapesChanged();
        LOG.info("face root {} holds {} as records: {}", spec.code(), spec.face(), loaded);
        return loaded;
    }

    /**
     * Whether the version is here already — asked of its root definition by
     * canonical url, not of the type. A tenant holds definitions of its own
     * before any package is read: the face writes what its vocabulary needs at
     * bring-up. Asking "is there any StructureDefinition" answered yes to those
     * and the packages were never loaded, silently, which is the shape of a
     * marker that guards the wrong thing.
     */
    private static boolean alreadyHoldsTheVersion(ObjectStore engine) {
        try {
            return !engine.getByIdentifier("StructureDefinition", List.of(
                    new cloud.jengu.dbo.core.api.Identifier(
                            cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                            "http://hl7.org/fhir/StructureDefinition/Resource"))).isEmpty();
        } catch (RuntimeException undeclared) {
            return false;
        }
    }

    /** Every base a definition names is a definition that was loaded. */
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
