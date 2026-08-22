package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.terminology.Concept;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The carried terminology packages, imported once into a tenant's store.
 *
 * <p>This is where the terminology baseline STOPS being parsed into heap on
 * every boot (#83) and starts being what it is: data, in the tenant's
 * database, next to the tenant's own systems and aliases, answered by the
 * same lookup validation already consults (#50). The packages stay carried in
 * the bundle — they are the source this import reads — but the worker context
 * no longer loads them, which was 40–50% of its build.
 *
 * <p>Idempotent per package: a marker row in the system registry names the
 * package id and version, so every later bring-up costs one SELECT. A new
 * package version has a new marker and imports again — replace-all per
 * system, which is what {@code importSystem} already does.
 *
 * <p>Parsed as plain JSON. A CodeSystem's concept tree has had the same shape
 * in every FHIR release this store serves, and reading it without a typed
 * model is what lets one importer serve every version's baseline.
 */
final class TerminologyBaseline {

    private TerminologyBaseline() {
    }

    /** The marker a completed import leaves: a held "system" no code claims. */
    private static String marker(CarriedDefinitions.Carried pkg) {
        return "urn:dbo:terminology-baseline:" + pkg.name();
    }

    static void ensure(TerminologyStore store, String fhirVersion) {
        for (CarriedDefinitions.Carried pkg : CarriedDefinitions.forVersion(fhirVersion)) {
            if (!pkg.name().startsWith("hl7.terminology")) {
                continue;
            }
            if (pkg.version().equals(store.systemVersion(marker(pkg)).orElse(null))) {
                continue;
            }
            importPackage(store, pkg);
            store.importSystem(marker(pkg), pkg.version(), List.<Concept>of().iterator());
        }
    }

    /**
     * One package, in ONE transaction.
     *
     * <p>Per system it was one transaction each, and a baseline package
     * carries some nine hundred of them: 2704ms of writing against 28ms of
     * parsing, which is round-trip overhead rather than work. Applying a face
     * is a bulk load, so it is written as one (#93).
     */
    private static void importPackage(TerminologyStore store, CarriedDefinitions.Carried pkg) {
        try {
            NpmPackage npm = NpmPackage.fromPackage(CarriedDefinitions.open(pkg));
            List<TerminologyStore.System> systems = new ArrayList<>();
            for (String file : npm.list("package")) {
                if (!file.startsWith("CodeSystem-") || !file.endsWith(".json")) {
                    continue;
                }
                JsonObject codeSystem = org.hl7.fhir.utilities.json.parser.JsonParser
                        .parseObject(npm.load("package", file));
                String url = codeSystem.asString("url");
                if (url == null || !codeSystem.has("concept")) {
                    continue;
                }
                List<Concept> flat = new ArrayList<>();
                flatten(codeSystem.getJsonArray("concept"), null, flat);
                systems.add(new TerminologyStore.System(url, codeSystem.asString("version"),
                        flat));
            }
            store.importSystems(systems);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot import the terminology baseline from " + pkg.id(), e);
        }
    }

    private static void flatten(JsonArray concepts, String parent, List<Concept> out) {
        for (JsonElement element : concepts) {
            if (!(element instanceof JsonObject concept)) {
                continue;
            }
            String code = concept.asString("code");
            if (code == null) {
                continue;
            }
            Map<String, String> designations = new LinkedHashMap<>();
            if (concept.has("designation")) {
                for (JsonElement d : concept.getJsonArray("designation")) {
                    if (d instanceof JsonObject designation
                            && designation.asString("language") != null
                            && designation.asString("value") != null) {
                        designations.put(designation.asString("language"),
                                designation.asString("value"));
                    }
                }
            }
            Map<String, String> properties = new LinkedHashMap<>();
            if (concept.has("property")) {
                for (JsonElement p : concept.getJsonArray("property")) {
                    if (p instanceof JsonObject property && property.asString("code") != null) {
                        // primitive value[x] only: a valueCoding has no single
                        // string, and a null value is not a property
                        property.getProperties().stream()
                                .filter(e -> e.getName().startsWith("value"))
                                .map(e -> e.getValue().asString())
                                .filter(java.util.Objects::nonNull)
                                .findFirst()
                                .ifPresent(v -> properties.put(property.asString("code"), v));
                    }
                }
            }
            out.add(new Concept(code, concept.asString("display"), parent,
                    designations, properties));
            if (concept.has("concept")) {
                flatten(concept.getJsonArray("concept"), code, out);
            }
        }
    }
}
