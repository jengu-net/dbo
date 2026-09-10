package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The search parameters a version defines over its own definition types, read
 * from the carried package as JSON — with no worker context, because these
 * are what index a definition on arrival, and a definition arriving is the
 * one write that cannot wait for a context able to parse it.
 *
 * <p>The five types are the ones a face publishes and a tenant takes as its
 * version: what a subscriber drains before it is served, what a root loads
 * from the packages. Every other type is indexed by the toolchain over the
 * element model, as before.
 */
final class DefinitionParameters {

    static final Set<String> DEFINITION_TYPES = Set.of("StructureDefinition",
            "SearchParameter", "ValueSet", "CodeSystem", "StructureMap");

    /** One parameter as the package states it: code, type, FHIRPath expression. */
    record Parameter(String code, String type, String expression) {
    }

    private static final Map<String, Map<String, List<Parameter>>> BY_FACE =
            new ConcurrentHashMap<>();

    private DefinitionParameters() {
    }

    static boolean isDefinitionType(String typeName) {
        return DEFINITION_TYPES.contains(typeName);
    }

    /** The version's parameters over one definition type, expression first, one per code. */
    static List<Parameter> forType(String face, String typeName) {
        return BY_FACE.computeIfAbsent(face, DefinitionParameters::read)
                .getOrDefault(typeName, List.of());
    }

    private static Map<String, List<Parameter>> read(String face) {
        Map<String, Map<String, Parameter>> byType = new LinkedHashMap<>();
        for (String type : DEFINITION_TYPES) {
            byType.put(type, new LinkedHashMap<>());
        }
        for (CarriedDefinitions.Carried carried : CarriedDefinitions.forVersion(face)) {
            if (carried.name().startsWith("hl7.terminology")) {
                continue;
            }
            try {
                NpmPackage npm = NpmPackage.fromPackage(CarriedDefinitions.open(carried));
                for (String name : npm.listResources("SearchParameter")) {
                    JsonObject json;
                    try (var in = npm.loadResource(name)) {
                        json = JsonParser.parseObject(in);
                    }
                    String code = json.asString("code");
                    String expression = json.asString("expression");
                    // The same three rules the toolchain path applies when it
                    // reads the loaded context: nothing underscored, nothing
                    // without an expression, and the first definition of a
                    // code is the one that counts.
                    if (code == null || code.startsWith("_")
                            || expression == null || expression.isBlank()) {
                        continue;
                    }
                    Parameter parameter = new Parameter(code, json.asString("type"), expression);
                    if (json.hasArray("base")) {
                        for (String base : json.getJsonArray("base").asStrings()) {
                            Map<String, Parameter> ofType = byType.get(base);
                            if (ofType != null) {
                                ofType.putIfAbsent(code, parameter);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + carried.id(), e);
            }
        }
        Map<String, List<Parameter>> out = new LinkedHashMap<>();
        byType.forEach((type, parameters) -> out.put(type, List.copyOf(parameters.values())));
        return Map.copyOf(out);
    }

    /** What the five types are indexed under, for the version at hand. */
    static List<String> codesFor(String face, String typeName) {
        List<String> codes = new ArrayList<>();
        forType(face, typeName).forEach(p -> codes.add(p.code()));
        return codes;
    }
}
