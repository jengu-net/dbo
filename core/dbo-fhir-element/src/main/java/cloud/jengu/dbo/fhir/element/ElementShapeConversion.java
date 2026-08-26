package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.ShapeConversion;
import org.hl7.fhir.r5.conformance.profile.ProfileUtilities;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.model.Base;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.StructureMap;
import org.hl7.fhir.r5.utils.structuremap.ITransformerServices;
import org.hl7.fhir.r5.utils.structuremap.StructureMapUtilities;

import java.util.List;
import java.util.Optional;

/**
 * The FHIR face's shape conversion: pack-shipped StructureMaps, executed in
 * process (#133).
 *
 * <p>FHIR can express its own converters as data — a StructureMap is a
 * published resource, authored in FHIR Mapping Language — so converters ship
 * in the tenant's pack beside the shapes they convert, sync with it, and are
 * versioned by it. That is why this capability exists at all: the engine
 * owns the reshape loop and knows nothing about transformation, and the face
 * answers because its model has a standard for the answer.
 *
 * <p><b>Map selection is FHIR's own versioned canonical, and nothing else.</b>
 * A shape that breaks keeps its canonical and bumps its version — that is
 * what makes {@code meta.profile} stable while the stamp moves — so a
 * converter declares the hop the way FHIR spells a versioned reference:
 * {@code structure} with mode {@code source} at {@code <canonical>|2.1.0}
 * and mode {@code target} at {@code <canonical>|3.0.0}. The map is selected
 * by the canonical it converts and the major its target names. Nothing
 * bespoke is invented, no side registry is kept, and because the canonical
 * does not change, a converted object still claims the profile it always
 * claimed — the conversion moves its shape, never its identity.
 *
 * <p>A hop no map covers is {@link Optional#empty()} — an ordinary answer
 * the loop reports and moves past, not a failure.
 */
final class ElementShapeConversion implements ShapeConversion {

    private final SimpleWorkerContext context;
    private final ElementPayloads payloads;

    /**
     * Built from the TENANT's payloads, not the version's: a pack's
     * converters are the tenant's own content, so a conversion resolved
     * against the shared definitions would find none and answer "no
     * converter" about maps the tenant is holding.
     */
    ElementShapeConversion(ElementPayloads payloads) {
        this.context = payloads.context();
        this.payloads = payloads;
    }

    @Override
    public Optional<byte[]> convert(String typeName, byte[] payload, String profile,
            int targetMajor) {
        Optional<StructureMap> map = mapFor(profile, targetMajor);
        if (map.isEmpty()) {
            return Optional.empty();
        }
        Element source = payloads.read(typeName, payload);
        Element target = targetOf(map.get());
        if (target == null) {
            return Optional.empty();
        }
        new StructureMapUtilities(context, new Services()).transform(null, source, map.get(),
                target);
        return Optional.of(payloads.write(target));
    }

    /**
     * The pack's map for this hop, or empty.
     *
     * <p>Fetched from the tenant's own context on every call rather than
     * cached: a pack that gains a converter must take effect without a
     * restart, exactly as a profile does (#87), and a conversion run is
     * page-paced work where one definition lookup is not the cost.
     */
    private Optional<StructureMap> mapFor(String profile, int targetMajor) {
        for (StructureMap candidate : context.fetchResourcesByType(StructureMap.class)) {
            boolean fromThisShape = candidate.getStructure().stream().anyMatch(structure ->
                    StructureMap.StructureMapModelMode.SOURCE.equals(structure.getMode())
                            && profile.equals(canonicalOf(structure.getUrl())));
            boolean toThisMajor = candidate.getStructure().stream().anyMatch(structure ->
                    StructureMap.StructureMapModelMode.TARGET.equals(structure.getMode())
                            && profile.equals(canonicalOf(structure.getUrl()))
                            && targetMajor == majorOf(structure.getUrl()));
            if (fromThisShape && toThisMajor) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** The canonical without its version: {@code url|3.0.0} names {@code url}. */
    private static String canonicalOf(String reference) {
        if (reference == null) {
            return null;
        }
        int bar = reference.indexOf('|');
        return bar < 0 ? reference : reference.substring(0, bar);
    }

    /** The major a versioned canonical names, or -1 when it names none. */
    private static int majorOf(String reference) {
        int bar = reference == null ? -1 : reference.indexOf('|');
        if (bar < 0) {
            return -1;
        }
        String major = reference.substring(bar + 1).split("\\.", 2)[0];
        return major.matches("[0-9]+") ? Integer.parseInt(major) : -1;
    }

    /**
     * An empty document of the map's declared target shape.
     *
     * <p>Resolved by the bare canonical rather than the versioned one: the
     * pack holds one definition per canonical — the current one — and the
     * target version is precisely the one it now carries. Asking for the
     * versioned form would fail on the very pack the conversion is
     * converging on.
     */
    private Element targetOf(StructureMap map) {
        return map.getStructure().stream()
                .filter(structure ->
                        StructureMap.StructureMapModelMode.TARGET.equals(structure.getMode()))
                .map(structure -> context.fetchResource(StructureDefinition.class,
                        canonicalOf(structure.getUrl())))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(definition -> Manager.build(context, definition))
                .orElse(null);
    }

    /**
     * The transform's host. Deliberately minimal: a converter that reaches
     * outside the document it was handed — resolving a reference, running a
     * search — would make a conversion depend on what else is in the store
     * at that instant, and two runs over one object could then disagree.
     * Shape conversion is a pure function of the payload or it is not this.
     */
    private final class Services implements ITransformerServices {

        @Override
        public void log(String message) {
            // The map's own log statements: a converter author's debugging
            // aid, not the store's business.
        }

        @Override
        public Base createType(Object appInfo, String name, ProfileUtilities profiles) {
            StructureDefinition definition = context.fetchTypeDefinition(name);
            if (definition == null) {
                throw new IllegalStateException("a map asked for the type '" + name
                        + "', which this tenant's pack does not define");
            }
            return Manager.build(context, definition);
        }

        @Override
        public Base createResource(Object appInfo, Base resource, boolean atRoot) {
            return resource;
        }

        @Override
        public Coding translate(Object appInfo, Coding source, String conceptMapUrl) {
            throw new IllegalStateException("translate() inside a conversion map is not served: "
                    + "code-system lifecycle is its own decision, and a silent passthrough "
                    + "would convert a code into one this tenant never agreed to");
        }

        @Override
        public Base resolveReference(Object appInfo, String url) {
            throw new IllegalStateException("a conversion map may not resolve '" + url
                    + "': a conversion that depends on what else is stored would give two "
                    + "different answers for one object");
        }

        @Override
        public List<Base> performSearch(Object appInfo, String url) {
            throw new IllegalStateException("a conversion map may not search: a conversion "
                    + "that depends on what else is stored would give two different answers "
                    + "for one object");
        }
    }
}
