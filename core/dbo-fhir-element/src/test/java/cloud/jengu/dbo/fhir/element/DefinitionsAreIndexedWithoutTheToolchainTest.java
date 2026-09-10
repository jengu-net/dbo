package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A definition's envelope read from its JSON is the envelope the toolchain
 * would have read from the element model — for every definition every
 * carried face publishes, not for a sample. The JSON path exists so that a
 * definition can be indexed before the tenant holds the version it belongs
 * to; it is only allowed to exist while this test says the two agree.
 */
class DefinitionsAreIndexedWithoutTheToolchainTest {

    @Test
    @DisplayName("every carried definition indexes the same from JSON as through the toolchain")
    @Proving(DboPromises.VER_DEFINITIONS_INDEXED_WITHOUT_THE_TOOLCHAIN)
    void everyCarriedDefinitionIndexesTheSame() {
        for (String face : CarriedDefinitions.versions()) {
            ElementVersion version = ElementVersion.of(face);
            ElementPayloads payloads = new ElementPayloads(version.context());
            List<FaceRootPackages.Definition> definitions =
                    FaceRootPackages.definitionsFor(face, DefinitionParameters.DEFINITION_TYPES);
            assertTrue(definitions.size() > 1000, face + " carries " + definitions.size());
            List<String> disagreements = new ArrayList<>();
            long began = System.currentTimeMillis();
            for (FaceRootPackages.Definition definition : definitions) {
                Envelope fromJson = DefinitionEnvelopes.extract(
                        DefinitionParameters.forType(face, definition.typeName()),
                        definition.typeName(), definition.document(), true);
                Envelope fromToolchain = ElementEnvelopes.extract(version.context(),
                        version.parametersFor(definition.typeName()),
                        payloads.read(definition.typeName(), definition.document()), true);
                // Edges are compared as the set the store keeps them as: the
                // two paths visit the parameters in different orders, and an
                // edge has no position.
                if (!fromJson.paths().equals(fromToolchain.paths())
                        || !fromJson.identifiers().equals(fromToolchain.identifiers())
                        || !edges(fromJson).equals(edges(fromToolchain))) {
                    disagreements.add(definition.typeName() + " " + definition.url()
                            + "\n  json:      " + differing(fromJson, fromToolchain)
                            + "\n  toolchain: " + differing(fromToolchain, fromJson));
                }
            }
            System.out.println("MEASURED " + face + ": " + definitions.size()
                    + " definitions compared in " + (System.currentTimeMillis() - began) + "ms");
            assertTrue(disagreements.isEmpty(), face + ": " + disagreements.size() + " of "
                    + definitions.size() + " definitions index differently, first:\n"
                    + String.join("\n", disagreements.subList(0, Math.min(5, disagreements.size()))));
            for (String type : DefinitionParameters.DEFINITION_TYPES) {
                assertEquals(version.parametersFor(type).stream().map(p -> p.getCode()).sorted().toList(),
                        DefinitionParameters.codesFor(face, type).stream().sorted().toList(),
                        face + " " + type + ": the parameters read from the package are not the "
                                + "parameters the toolchain reads from the context");
            }
        }
    }

    @Test
    @DisplayName("indexing a definition reads nothing through the toolchain")
    @Proving(DboPromises.VER_DEFINITIONS_INDEXED_WITHOUT_THE_TOOLCHAIN)
    void indexingADefinitionReadsNothingThroughTheToolchain() {
        ElementVersion version = ElementVersion.of("r4");
        FaceRootPackages.Definition patient = FaceRootPackages.definitionsFor("r4",
                        java.util.Set.of("StructureDefinition")).stream()
                .filter(d -> d.url().endsWith("/StructureDefinition/Patient")).findFirst().orElseThrow();
        long readsBefore = ElementPayloads.READS.get();
        Envelope envelope = version.extractor("StructureDefinition", true)
                .extract("StructureDefinition", patient.document());
        assertEquals(readsBefore, ElementPayloads.READS.get(),
                "the version's extractor parsed the definition through the toolchain");
        assertTrue(envelope.identifiers().stream().anyMatch(i -> i.value().equals(patient.url())),
                "and it still claimed the canonical: " + envelope.identifiers());
        assertTrue(envelope.paths().containsKey("kind") && envelope.paths().containsKey("base_path"),
                "and it still indexed the parameters: " + envelope.paths().keySet());
    }

    @Test
    @DisplayName("what the toolchain refuses to read, the JSON path refuses too, in the same words")
    @Proving(DboPromises.VER_DEFINITIONS_INDEXED_WITHOUT_THE_TOOLCHAIN)
    void whatTheToolchainRefusesIsRefusedToo() {
        var extractor = ElementVersion.of("r4").extractor("ValueSet", true);
        for (String body : List.of("{\"resourceType\":\"Nonesuch\"}", "{\"status\":\"active\"}",
                "not json at all", "{\"resourceType\":\"ValueSet\"")) {
            IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> extractor.extract("ValueSet", body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "indexed rather than refused: " + body);
            assertTrue(refused.getMessage().startsWith("body is not parseable FHIR JSON"),
                    refused.getMessage());
        }
    }

    @Test
    @DisplayName("what a face carries is the package folder, self-consistent: every shape's base is carried too")
    void whatAFaceCarriesIsSelfConsistent() {
        for (String face : CarriedDefinitions.versions()) {
            List<FaceRootPackages.Definition> structures = FaceRootPackages.definitionsFor(face,
                    java.util.Set.of("StructureDefinition"));
            java.util.Set<String> urls = new java.util.HashSet<>();
            structures.forEach(d -> urls.add(d.url()));
            List<String> orphans = new ArrayList<>();
            for (FaceRootPackages.Definition d : structures) {
                String json = new String(d.document(), java.nio.charset.StandardCharsets.UTF_8);
                if (json.matches("(?s).*\"kind\"\\s*:\\s*\"logical\".*")) {
                    continue; // a logical model is not a shape a resource is validated against
                }
                java.util.regex.Matcher base = java.util.regex.Pattern
                        .compile("\"baseDefinition\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (base.find() && !urls.contains(base.group(1))) {
                    orphans.add(d.url() + " on " + base.group(1));
                }
            }
            assertTrue(orphans.isEmpty(), face + ": a side folder of the package leaked in — "
                    + orphans);
        }
    }

    private static List<String> edges(Envelope envelope) {
        return envelope.references().stream().map(Object::toString).sorted().toList();
    }

    /** The paths, identifiers and references of {@code a} that {@code b} does not have the same. */
    private static String differing(Envelope a, Envelope b) {
        StringBuilder out = new StringBuilder();
        a.paths().forEach((path, values) -> {
            java.util.List<cloud.jengu.dbo.core.api.EnvelopeValue> other = b.paths().get(path);
            if (!values.equals(other)) {
                int at = 0;
                while (other != null && at < values.size() && at < other.size()
                        && values.get(at).equals(other.get(at))) {
                    at++;
                }
                out.append(path).append('[').append(values.size()).append(" vs ")
                        .append(other == null ? "none" : other.size()).append("] first differs at ")
                        .append(at).append(": ").append(at < values.size() ? values.get(at) : "-")
                        .append("; ");
            }
        });
        if (!a.identifiers().equals(b.identifiers())) {
            out.append("identifiers=").append(a.identifiers()).append(' ');
        }
        if (!edges(a).equals(edges(b))) {
            out.append("references=").append(a.references());
        }
        return out.toString();
    }
}
